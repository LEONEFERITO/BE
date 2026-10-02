package com.leoneferito.auth;

import com.leoneferito.auth.social.SocialLoginHandlers;
import com.leoneferito.auth.social.SocialLoginService;
import com.leoneferito.common.error.ErrorResponse;
// Jackson 3 부터 databind 패키지가 tools.jackson 으로 옮겨졌다.
// 애너테이션(com.fasterxml.jackson.annotation)은 그대로라 둘이 섞여 보인다.
import tools.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.session.web.http.DefaultCookieSerializer;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * 보안 설정.
 *
 * <p>인증 방식은 <b>세션 쿠키</b>다. 토큰을 localStorage 에 두지 않는다 —
 * 스크립트가 읽을 수 있는 곳에 자격증명을 두면 XSS 한 번에 계정이 통째로 넘어간다.
 * 세션 쿠키는 {@code HttpOnly} 라서 스크립트가 못 읽는다.
 *
 * <h2>쿠키를 쓰기로 한 대가: CSRF 와 도메인</h2>
 * 쿠키는 브라우저가 <b>알아서</b> 붙인다. 그래서 다른 사이트가 우리 서버로 요청을 보내도
 * 쿠키가 따라간다(CSRF). 그걸 막는 게 아래 CSRF 설정이다.
 *
 * <p>그리고 프론트(Vercel)와 API 가 <b>같은 상위 도메인</b> 아래 있어야 한다.
 * 예: 화면 {@code leoneferito.com} · API {@code api.leoneferito.com}.
 * 서로 다른 사이트면 브라우저가 {@code SameSite=None} 을 요구하고, 그건 CSRF 방어를
 * 스스로 낮추는 선택이다. 도메인 연결이 이 설계의 전제다.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /**
     * BCrypt 를 쓰되 <b>위임형</b>으로 감싼다.
     *
     * <p>저장된 해시에 {@code {bcrypt}} 접두사가 붙어서, 나중에 더 나은 알고리즘으로
     * 옮길 때 기존 해시를 그대로 둔 채 새 가입자부터 새 알고리즘을 쓸 수 있다.
     * 접두사 없이 저장하면 그날 전체 회원의 비밀번호를 한 번에 옮길 방법이 없다
     * (해시는 되돌릴 수 없으므로 재설정을 요구해야 한다).
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    /**
     * 세션 쿠키.
     *
     * <p><b>설정 파일이 아니라 여기서 정한다.</b> 세션을 Spring Session(DB)이 맡으면 쿠키도 그쪽이 쓰는데,
     * {@code server.servlet.session.cookie.*} 설정이 거기까지 전달되지 않았다 — 실제로
     * {@code SESSION=…; Path=/} 만 나가고 HttpOnly · SameSite 가 빠져 있었다(테스트로 확인).
     * 보안 속성은 설정 전달 경로에 기대지 않고 코드로 못박는다.
     *
     * <p>Secure 만 설정에서 읽는다 — 로컬은 http 라 꺼야 한다 (application.yml local 프로필).
     */
    @Bean
    public CookieSerializer cookieSerializer(
            @Value("${server.servlet.session.cookie.secure:true}") boolean secure) {
        DefaultCookieSerializer serializer = new DefaultCookieSerializer();
        serializer.setCookieName("LFSESSION");
        serializer.setCookiePath("/");
        serializer.setUseHttpOnlyCookie(true);  // 스크립트가 못 읽는다
        serializer.setSameSite("Lax");          // 다른 사이트에서 시작된 요청에는 안 붙는다
        serializer.setUseSecureCookie(secure);
        /*
         * base64 를 끈다. 켜 두면 쿠키 값을 base64 로 풀어서 세션을 찾는데, 아무 쿠키나 풀면
         * NUL(0x00) 같은 바이트가 나오고 PostgreSQL 이 그 조회를 거부해 **500** 이 났다
         * (DB 세션 전환 전의 옛 쿠키를 가진 브라우저에서 실제로 났다). 쿠키만 조작하면 누구나
         * 서버 오류와 에러 로그를 만들 수 있었다. 세션 id 는 UUID 라 인코딩할 이유가 없다 —
         * 그대로 두면 이상한 값은 그냥 "없는 세션" 이 된다.
         */
        serializer.setUseBase64Encoding(false);
        return serializer;
    }

    /** 로그인 성공 시 인증 정보를 세션에 저장하는 경로. {@code AuthController} 가 직접 쓴다. */
    @Bean
    public SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http,
                                           SecurityContextRepository contextRepository,
                                           ObjectMapper objectMapper,
                                           SocialLoginService socialLoginService,
                                           SocialLoginHandlers socialHandlers,
                                           OAuth2AuthorizationRequestResolver authorizationRequestResolver)
            throws Exception {

        /*
         * CSRF 토큰을 쿠키로 내려 주고 헤더로 돌려받는다 (XSRF-TOKEN → X-XSRF-TOKEN).
         * 이 쿠키만은 HttpOnly 가 아니다 — 프론트 스크립트가 읽어서 헤더에 실어야 한다.
         * 그래도 안전한 이유: 다른 사이트는 우리 도메인의 쿠키를 읽을 수 없다(동일 출처 정책).
         *
         * setCsrfRequestAttributeName(null) 은 토큰을 매 요청 즉시 해석하게 만든다.
         * 기본값(지연 해석)이면 쿠키가 실제로 내려가지 않아 첫 요청이 항상 실패한다.
         */
        CsrfTokenRequestAttributeHandler csrfHandler = new CsrfTokenRequestAttributeHandler();
        csrfHandler.setCsrfRequestAttributeName(null);

        http
                /*
                 * 아래 corsConfigurationSource 빈을 쓴다. 타입으로 주입받지 않는 이유:
                 * Spring MVC 의 mvcHandlerMappingIntrospector 도 CorsConfigurationSource 라서
                 * 타입이 둘이 되어 주입이 실패한다. Security 는 **이름이** corsConfigurationSource 인
                 * 빈을 찾으므로 기본 설정에 맡기는 게 맞다.
                 */
                .cors(org.springframework.security.config.Customizer.withDefaults())
                .csrf(csrf -> csrf
                        .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                        .csrfTokenRequestHandler(csrfHandler)
                        // 토스 웹훅은 브라우저가 아니라 토스 서버가 보낸다 — 쿠키도 CSRF 토큰도 없다.
                        // 본문을 믿지 않고 결제 키로 토스에 다시 물어 맞추므로(OrderService.reconcile) 위조해도 얻을 게 없다.
                        .ignoringRequestMatchers("/api/payments/toss/webhook"))

                // 폼 로그인·HTTP Basic 을 쓰지 않는다. 우리는 JSON API 다.
                // 끄지 않으면 인증 실패 시 로그인 HTML 페이지로 리다이렉트된다.
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .logout(logout -> logout.disable()) // 로그아웃도 우리 엔드포인트가 처리한다

                /*
                 * 간편 로그인 (카카오 · 네이버). 흐름은 전부 브라우저 리다이렉트다:
                 *   /oauth2/authorization/{kakao|naver} → 제공자 로그인 → /login/oauth2/code/{…} → 프론트
                 * 제공자가 준 사용자 정보를 회원으로 잇는 건 SocialLoginService,
                 * 그 뒤 우리 세션에 담고 프론트로 돌려보내는 건 SocialLoginHandlers 가 한다.
                 * 키가 없는 제공자는 등록이 없어서 시작 주소가 401 로 끝난다 — 사이트는 뜬다.
                 */
                .oauth2Login(oauth -> oauth
                        // 꺼진 제공자의 시작 주소는 500 이 아니라 404 로 (SocialLoginConfig 참고)
                        .authorizationEndpoint(endpoint ->
                                endpoint.authorizationRequestResolver(authorizationRequestResolver))
                        .userInfoEndpoint(user -> user.userService(socialLoginService))
                        .successHandler(socialHandlers)
                        .failureHandler(socialHandlers))

                /*
                 * 세션은 필요할 때만 만든다. ALWAYS 로 두면 상품 목록을 한 번 보기만 해도
                 * 세션 행이 생겨서, 세션 테이블이 익명 방문자로 가득 찬다.
                 */
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))

                .authorizeHttpRequests(auth -> auth
                        // 공개 영역
                        .requestMatchers(HttpMethod.GET, "/api/products", "/api/products/**").permitAll()
                        // 공지 · FAQ — 손님이 로그인 없이 읽는다 (공개된 것만 나간다)
                        .requestMatchers(HttpMethod.GET, "/api/notices", "/api/notices/*", "/api/faqs").permitAll()
                        // 메인 WHY 구간 — 손님 화면을 빌드할 때 받는다
                        .requestMatchers(HttpMethod.GET, "/api/why").permitAll()
                        .requestMatchers("/api/auth/signup", "/api/auth/login", "/api/auth/admin-login", "/api/auth/csrf",
                                "/api/auth/social", "/api/auth/password-reset/**").permitAll()
                        // 간편 로그인 시작·콜백. 인증 전에 오는 주소라 열어 둔다.
                        .requestMatchers("/oauth2/authorization/*", "/login/oauth2/code/*").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/payments/toss/webhook").permitAll()
                        /*
                         * 업로드된 이미지는 공개다. 상품 사진이라 손님이 봐야 한다.
                         * 대신 주소가 UUID 라 추측할 수 없고, 서빙 쪽이 nosniff 를 붙인다.
                         */
                        .requestMatchers(HttpMethod.GET, "/media/**").permitAll()
                        /*
                         * 관리자 영역. 여기 있는 건 전부 쓰기이고, 뚫리면 상품 정보와
                         * 이미지 저장소가 통째로 남의 것이 된다.
                         */
                        // 관리자 지정·해제는 최고 관리자만. 위에서부터 맞추므로 /api/admin/** 보다 먼저 와야 한다.
                        .requestMatchers(HttpMethod.PUT, "/api/admin/members/*/role").hasRole("SUPER_ADMIN")
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        // CORS 사전 요청은 인증 대상이 아니다
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        /*
                         * 나머지는 전부 인증. 기본값을 "막힘" 으로 두는 게 핵심이다 —
                         * 새 엔드포인트를 만들고 규칙을 깜빡해도 열리지 않는다.
                         */
                        .anyRequest().authenticated())

                /*
                 * 인증이 없을 때 302 가 아니라 401 JSON 을 돌려준다.
                 * API 클라이언트에게 로그인 페이지 HTML 을 주면 파싱하다 엉뚱한 곳에서 깨진다.
                 */
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((request, response, authException) ->
                                writeError(response, objectMapper, HttpServletResponse.SC_UNAUTHORIZED,
                                        "UNAUTHENTICATED", "로그인이 필요합니다."))
                        .accessDeniedHandler((request, response, deniedException) ->
                                writeError(response, objectMapper, HttpServletResponse.SC_FORBIDDEN,
                                        "FORBIDDEN", "권한이 없습니다.")));

        http.securityContext(context -> context.securityContextRepository(contextRepository));

        return http.build();
    }

    private void writeError(HttpServletResponse response, ObjectMapper objectMapper,
                            int status, String code, String message) throws java.io.IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        // traceId 는 필터 단계라 MDC 에 없을 수 있다. 형식을 맞추는 게 목적이므로 비워 둔다.
        objectMapper.writeValue(response.getWriter(), ErrorResponse.of(code, message, null));
    }

    /**
     * CORS.
     *
     * <p>허용 출처를 <b>설정으로 받는다.</b> 코드에 박아두면 도메인이 정해지는 날
     * 배포가 한 번 더 필요하다. 기본값은 로컬 개발 주소뿐이라,
     * 운영에서 환경변수를 깜빡하면 프론트가 붙지 못한다 — 조용히 아무 데나 열리는 것보다 낫다.
     *
     * <p>{@code allowCredentials=true} 라서 {@code *} 는 쓸 수 없다(브라우저가 거부한다).
     * 쿠키를 주고받는 이상 출처를 하나하나 적는 수밖에 없고, 그게 맞다.
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource(
            @Value("${app.cors.allowed-origins:http://localhost:3000}") List<String> allowedOrigins) {

        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(allowedOrigins);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Content-Type", "X-XSRF-TOKEN"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }
}
