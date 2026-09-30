package com.leoneferito.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.leoneferito.TestcontainersConfiguration;
import com.leoneferito.auth.social.SocialLoginService;
import com.leoneferito.auth.social.SocialProvider;
import com.leoneferito.member.Member;
import com.leoneferito.member.MemberProvider;
import com.leoneferito.member.MemberRepository;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * 인증 API 검증.
 *
 * <p>이 테스트가 지키려는 것은 기능이 아니라 <b>보안 속성</b>이다. 로그인이 되는지는
 * 손으로도 확인되지만, "없는 계정과 틀린 비밀번호의 응답이 같은가" 는 아무도 눈으로 못 본다.
 * 그런 것들이 조용히 무너지는 걸 막는 게 여기 있는 테스트들의 목적이다.
 *
 * <p>트랜잭션으로 감싸지 <b>않는다.</b> 세션은 별도 커넥션에서 읽고 쓰이고(Spring Session JDBC),
 * 로그인 실패 누적은 커밋되어야 다음 요청에서 보인다. 대신 테스트마다 직접 지운다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class AuthApiTest {

    private static final String EMAIL = "hong@example.com";
    private static final String PASSWORD = "correct horse battery";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MemberRepository members;

    @Autowired
    private AuthService authService;

    @Autowired
    private ApplicationContext context;

    @Test
    @DisplayName("기본 계정(user / 자동 비밀번호)이 만들어지지 않는다")
    void noGeneratedDefaultUser() {
        // 이게 있으면 기동 로그에 비밀번호가 찍힌다. 우리 회원은 member 테이블에만 있다.
        assertThat(context.getBeanNamesForType(UserDetailsService.class)).isEmpty();
    }

    @BeforeEach
    void reset() {
        members.deleteAll();
        authService.signup(EMAIL, PASSWORD, "홍길동", null);
    }

    /** CSRF 토큰을 받아 요청에 붙인다. 프론트가 하는 일과 같은 순서다. */
    private MockHttpServletRequestBuilder withCsrf(MockHttpServletRequestBuilder builder) throws Exception {
        MvcResult tokenResult = mockMvc.perform(get("/api/auth/csrf"))
                .andExpect(status().isOk())
                .andReturn();
        String token = com.jayway.jsonpath.JsonPath.read(
                tokenResult.getResponse().getContentAsString(), "$.token");
        Cookie csrfCookie = tokenResult.getResponse().getCookie("XSRF-TOKEN");
        return builder.header("X-XSRF-TOKEN", token)
                .cookie(csrfCookie == null ? new Cookie("XSRF-TOKEN", token) : csrfCookie);
    }

    private String loginBody(String email, String password) {
        return """
                {"email":"%s","password":"%s"}
                """.formatted(email, password);
    }

    @Nested
    @DisplayName("간편가입 (카카오 · 네이버)")
    class Social {

        /** 제공자 호출을 흉내 낸다. 진짜 카카오는 키가 있어야 해서 여기서는 응답 모양만 재현한다. */
        private SocialLoginService serviceReturning(Map<String, Object> attributes) {
            return new SocialLoginService(members, request -> new DefaultOAuth2User(
                    List.of(new SimpleGrantedAuthority("OAUTH2_USER")), attributes, "id"));
        }

        private OAuth2UserRequest kakaoRequest() {
            return new OAuth2UserRequest(
                    SocialProvider.KAKAO.registration("client-id-for-test", "not-a-secret"),
                    new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, "token",
                            Instant.now(), Instant.now().plusSeconds(60)));
        }

        private Map<String, Object> kakaoAttributes(String email) {
            Map<String, Object> account = new HashMap<>();
            if (email != null) account.put("email", email);
            account.put("profile", Map.of("nickname", "김레오"));
            return Map.of("id", 123456789L, "kakao_account", account);
        }

        @Test
        @DisplayName("키가 없으면 제공자 목록이 비어 있고, 시작 주소는 서버 오류가 아니라 거절이다")
        void disabledWithoutKeys() throws Exception {
            mockMvc.perform(get("/api/auth/social"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.providers").isEmpty());

            // 등록이 없으니 Spring 이 이 주소를 모른다 → 인증 필요(401). 500 이면 안 된다.
            mockMvc.perform(get("/oauth2/authorization/kakao"))
                    .andExpect(status().is4xxClientError());
        }

        @Test
        @DisplayName("처음 오면 가입되고, 두 번째부터는 같은 회원이다")
        void firstVisitSignsUp() {
            SocialLoginService service = serviceReturning(kakaoAttributes("kim@kakao.test"));

            var first = (SocialLoginService.SocialUser) service.loadUser(kakaoRequest());
            var second = (SocialLoginService.SocialUser) service.loadUser(kakaoRequest());

            assertThat(first.principal().getId()).isEqualTo(second.principal().getId());
            Member saved = members.findByEmail("kim@kakao.test").orElseThrow();
            assertThat(saved.getProvider()).isEqualTo(MemberProvider.KAKAO);
            assertThat(saved.getProviderUserId()).isEqualTo("123456789");
            assertThat(saved.hasPassword()).isFalse();
            assertThat(saved.getName()).isEqualTo("김레오");
        }

        @Test
        @DisplayName("이메일 제공에 동의하지 않으면 가입되지 않는다")
        void emailRequired() {
            SocialLoginService service = serviceReturning(kakaoAttributes(null));

            assertThatThrownBy(() -> service.loadUser(kakaoRequest()))
                    .isInstanceOf(OAuth2AuthenticationException.class)
                    .extracting(e -> ((OAuth2AuthenticationException) e).getError().getErrorCode())
                    .isEqualTo("email_required");
            assertThat(members.count()).isEqualTo(1); // reset() 이 만든 홍길동뿐
        }

        @Test
        @DisplayName("이메일로 이미 가입된 주소면 이어 붙이지 않는다")
        void doesNotLinkByEmail() {
            /*
             * 제공자가 준 이메일만 믿고 기존 계정에 붙이면, 그 이메일을 제공자 쪽에서
             * 확보한 사람이 남의 계정에 들어온다. 붙이지 않고 거절한다.
             */
            SocialLoginService service = serviceReturning(kakaoAttributes(EMAIL));

            assertThatThrownBy(() -> service.loadUser(kakaoRequest()))
                    .isInstanceOf(OAuth2AuthenticationException.class)
                    .extracting(e -> ((OAuth2AuthenticationException) e).getError().getErrorCode())
                    .isEqualTo("email_in_use");
        }

        @Test
        @DisplayName("간편가입 회원은 비밀번호로 로그인할 수 없다 — 응답은 틀린 비밀번호와 같다")
        void noPasswordLogin() throws Exception {
            serviceReturning(kakaoAttributes("kim@kakao.test")).loadUser(kakaoRequest());

            mockMvc.perform(withCsrf(post("/api/auth/login"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(loginBody("kim@kakao.test", "anything at all 12345")))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
        }
    }

    @Nested
    @DisplayName("회원가입")
    class Signup {

        @Test
        @DisplayName("가입하면 비밀번호가 평문으로 저장되지 않는다")
        void passwordIsHashed() {
            Member saved = members.findByEmail(EMAIL).orElseThrow();
            assertThat(saved.getPasswordHash())
                    .doesNotContain(PASSWORD)
                    // 위임형 인코더라 알고리즘 접두사가 붙는다. 나중에 알고리즘을 바꿀 길이 열려 있다.
                    .startsWith("{bcrypt}");
        }

        @Test
        @DisplayName("대소문자가 달라도 같은 계정이다")
        void emailIsCaseInsensitive() throws Exception {
            mockMvc.perform(withCsrf(post("/api/auth/signup"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"email":"HONG@Example.com","password":"another password here","name":"홍길동"}
                                    """))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("EMAIL_ALREADY_REGISTERED"));
        }

        @Test
        @DisplayName("에러 응답이 입력한 이메일을 되비추지 않는다")
        void errorDoesNotEchoInput() throws Exception {
            String body = mockMvc.perform(withCsrf(post("/api/auth/signup"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"email":"hong@example.com","password":"another password here","name":"홍길동"}
                                    """))
                    .andExpect(status().isConflict())
                    .andReturn().getResponse().getContentAsString();

            assertThat(body).doesNotContain("hong@example.com");
        }

        @Test
        @DisplayName("짧은 비밀번호는 이유를 알려주고 거부한다")
        void shortPasswordRejected() throws Exception {
            mockMvc.perform(withCsrf(post("/api/auth/signup"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"email":"new@example.com","password":"short","name":"김"}
                                    """))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("WEAK_PASSWORD"))
                    // 이 메시지만은 손님에게 그대로 보여준다 — 무엇을 고칠지 알려줘야 한다.
                    .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("10자")));
        }

        @Test
        @DisplayName("72바이트를 넘는 비밀번호는 자르지 않고 거부한다")
        void overlongPasswordRejected() throws Exception {
            /*
             * BCrypt 는 73번째 바이트부터 무시한다. 조용히 자르면 긴 암호를 쓴 사람이
             * 앞 72바이트만으로 인증되는데, 본인은 더 안전하다고 믿는다.
             */
            String tooLong = "a".repeat(73);
            mockMvc.perform(withCsrf(post("/api/auth/signup"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"email":"long@example.com","password":"%s","name":"김"}
                                    """.formatted(tooLong)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("WEAK_PASSWORD"));
        }

        @Test
        @DisplayName("이메일을 그대로 쓴 비밀번호는 거부한다")
        void passwordContainingEmailRejected() throws Exception {
            mockMvc.perform(withCsrf(post("/api/auth/signup"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"email":"minsu@example.com","password":"minsu12345678","name":"김"}
                                    """))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("WEAK_PASSWORD"));
        }
    }

    @Nested
    @DisplayName("로그인")
    class Login {

        @Test
        @DisplayName("성공하면 세션 쿠키가 생기고 내 정보를 볼 수 있다")
        void loginCreatesSession() throws Exception {
            MvcResult result = mockMvc.perform(withCsrf(post("/api/auth/login"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(loginBody(EMAIL, PASSWORD)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.email").value(EMAIL))
                    .andExpect(jsonPath("$.name").value("홍길동"))
                    .andReturn();

            MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
            assertThat(session).isNotNull();

            mockMvc.perform(get("/api/auth/me").session(session))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.email").value(EMAIL));
        }

        @Test
        @DisplayName("응답에 비밀번호 해시가 섞이지 않는다")
        void responseHasNoHash() throws Exception {
            String body = mockMvc.perform(withCsrf(post("/api/auth/login"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(loginBody(EMAIL, PASSWORD)))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();

            assertThat(body).doesNotContain("bcrypt").doesNotContain("password");
        }

        @Test
        @DisplayName("없는 이메일과 틀린 비밀번호의 응답이 완전히 같다")
        void noAccountEnumerationViaLogin() throws Exception {
            /*
             * 이게 이 파일에서 가장 중요한 테스트다. 두 응답이 달라지는 순간
             * 로그인 화면이 "이 이메일은 가입되어 있다" 를 알려주는 조회 도구가 된다.
             */
            String wrongPassword = mockMvc.perform(withCsrf(post("/api/auth/login"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(loginBody(EMAIL, "definitely wrong password")))
                    .andExpect(status().isUnauthorized())
                    .andReturn().getResponse().getContentAsString();

            String noSuchAccount = mockMvc.perform(withCsrf(post("/api/auth/login"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(loginBody("nobody@example.com", "definitely wrong password")))
                    .andExpect(status().isUnauthorized())
                    .andReturn().getResponse().getContentAsString();

            // traceId 와 timestamp 는 요청마다 다르다. 그 둘을 뺀 나머지가 같아야 한다.
            assertThat(codeAndMessage(wrongPassword)).isEqualTo(codeAndMessage(noSuchAccount));
        }

        @Test
        @DisplayName("탈퇴한 계정은 없는 계정과 같은 응답이다")
        void withdrawnLooksLikeNoAccount() throws Exception {
            Member member = members.findByEmail(EMAIL).orElseThrow();
            member.withdraw();
            members.saveAndFlush(member);

            mockMvc.perform(withCsrf(post("/api/auth/login"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(loginBody(EMAIL, PASSWORD)))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
        }

        @Test
        @DisplayName("5번 틀리면 잠기고, 맞는 비밀번호에만 잠김을 알려준다")
        void lockoutAfterRepeatedFailures() throws Exception {
            for (int i = 0; i < Member.MAX_FAILED_ATTEMPTS; i++) {
                mockMvc.perform(withCsrf(post("/api/auth/login"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(loginBody(EMAIL, "wrong password " + i)))
                        .andExpect(status().isUnauthorized());
            }

            assertThat(members.findByEmail(EMAIL).orElseThrow().isLocked(Instant.now())).isTrue();

            // 맞는 비밀번호 → 왜 못 들어가는지 알려준다 (이 사람은 이미 계정 주인이다)
            mockMvc.perform(withCsrf(post("/api/auth/login"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(loginBody(EMAIL, PASSWORD)))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("ACCOUNT_LOCKED"));

            /*
             * 틀린 비밀번호 → 잠김을 알려주지 않는다.
             * "잠겼습니다" 는 "그 계정은 존재합니다" 와 같은 말이다.
             */
            mockMvc.perform(withCsrf(post("/api/auth/login"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(loginBody(EMAIL, "still wrong")))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
        }

        @Test
        @DisplayName("성공하면 실패 누적이 0 으로 돌아간다")
        void successResetsFailureCount() throws Exception {
            mockMvc.perform(withCsrf(post("/api/auth/login"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(loginBody(EMAIL, "wrong")))
                    .andExpect(status().isUnauthorized());
            assertThat(members.findByEmail(EMAIL).orElseThrow().getFailedLoginAttempts()).isEqualTo((short) 1);

            mockMvc.perform(withCsrf(post("/api/auth/login"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(loginBody(EMAIL, PASSWORD)))
                    .andExpect(status().isOk());
            assertThat(members.findByEmail(EMAIL).orElseThrow().getFailedLoginAttempts()).isZero();
        }
    }

    @Nested
    @DisplayName("보호와 세션")
    class Protection {

        @Test
        @DisplayName("로그인하지 않으면 내 정보는 302 가 아니라 401 JSON 이다")
        void unauthenticatedGetsJson401() throws Exception {
            mockMvc.perform(get("/api/auth/me"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        }

        @Test
        @DisplayName("CSRF 토큰 없는 POST 는 거부된다")
        void postWithoutCsrfRejected() throws Exception {
            mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(loginBody(EMAIL, PASSWORD)))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("로그아웃하면 그 세션으로는 더 이상 조회되지 않는다")
        void logoutInvalidatesSession() throws Exception {
            MvcResult login = mockMvc.perform(withCsrf(post("/api/auth/login"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(loginBody(EMAIL, PASSWORD)))
                    .andExpect(status().isOk())
                    .andReturn();
            MockHttpSession session = (MockHttpSession) login.getRequest().getSession(false);

            mockMvc.perform(withCsrf(post("/api/auth/logout")).session(session))
                    .andExpect(status().isNoContent());

            mockMvc.perform(get("/api/auth/me").session(session))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("로그인하면 기존 세션 id 를 버린다 — 세션 고정 공격 방어")
        void sessionIdRotatesOnLogin() throws Exception {
            /*
             * 공격자가 피해자 브라우저에 세션 id 를 심어두고, 피해자가 그 세션으로
             * 로그인하면 공격자가 로그인 상태를 그대로 물려받는다.
             * 인증 시점에 id 를 갈아 끼우면 그 통로가 끊긴다.
             */
            MockHttpSession preLogin = new MockHttpSession();
            String before = preLogin.getId();

            mockMvc.perform(withCsrf(post("/api/auth/login")).session(preLogin)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(loginBody(EMAIL, PASSWORD)))
                    .andExpect(status().isOk());

            assertThat(preLogin.isInvalid()).as("로그인 전 세션이 폐기되어야 한다").isTrue();
            assertThat(before).isNotBlank();
        }

        @Test
        @DisplayName("상품 조회는 로그인 없이도 된다")
        void productsStayPublic() throws Exception {
            mockMvc.perform(get("/api/products"))
                    .andExpect(status().isOk());
        }
    }

    /** traceId·timestamp 를 뺀 응답 본문. 두 응답이 "같은가" 를 비교할 때 쓴다. */
    private String codeAndMessage(String json) {
        String code = com.jayway.jsonpath.JsonPath.read(json, "$.code");
        String message = com.jayway.jsonpath.JsonPath.read(json, "$.message");
        return code + "|" + message;
    }
}
