package com.leoneferito.auth.api;

import com.leoneferito.auth.AuthService;
import com.leoneferito.auth.MemberPrincipal;
import com.leoneferito.auth.social.SocialLoginConfig;
import com.leoneferito.member.Member;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 가입 · 로그인 · 로그아웃 · 내 정보.
 *
 * <p>로그인 성공 응답에 <b>토큰을 담지 않는다.</b> 인증은 세션 쿠키로 유지된다.
 * 프론트는 {@code credentials: "include"} 로 요청만 보내면 된다.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final SecurityContextRepository contextRepository;
    private final SocialLoginConfig.Enabled social;

    public AuthController(AuthService authService, SecurityContextRepository contextRepository,
                          SocialLoginConfig.Enabled social) {
        this.authService = authService;
        this.contextRepository = contextRepository;
        this.social = social;
    }

    /**
     * 켜진 간편가입 제공자.
     *
     * <p>키가 없는 제공자는 목록에 없다. 프론트는 있는 것만 버튼으로 그린다 —
     * 눌러도 아무 일이 없는 버튼을 두지 않기 위해서다.
     */
    @GetMapping("/social")
    public Map<String, List<String>> social() {
        return Map.of("providers",
                social.providers().stream().map(p -> p.registrationId).toList());
    }

    /**
     * CSRF 토큰 발급.
     *
     * <p>프론트가 로그인·가입을 하기 <b>전에</b> 한 번 호출해서 토큰을 받아 둔다.
     * 이 요청의 응답에 {@code XSRF-TOKEN} 쿠키가 실려 내려가고, 이후 POST 는 그 값을
     * {@code X-XSRF-TOKEN} 헤더에 담아 보낸다.
     */
    @GetMapping("/csrf")
    public Map<String, String> csrf(@RequestAttribute(name = "_csrf") CsrfToken token) {
        return Map.of("headerName", token.getHeaderName(), "token", token.getToken());
    }

    /**
     * 회원가입.
     *
     * <p>가입 직후 자동 로그인하지 않는다. 가입 폼을 대신 제출당하는 경우(CSRF 가 뚫렸다면)
     * 자동 로그인까지 이어지면 피해가 커진다. 한 단계 끊어 둔다.
     */
    @PostMapping("/signup")
    public ResponseEntity<Void> signup(@Valid @RequestBody AuthRequests.Signup request) {
        authService.signup(request.email(), request.password(), request.name(), request.phone());
        // 생성된 회원 id 를 돌려주지 않는다. 로그인 전에는 클라이언트가 쓸 일이 없고,
        // 식별자를 흘리면 나중에 그걸 키로 쓰는 코드가 생긴다.
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    /**
     * 로그인.
     *
     * <p><b>세션 고정 공격 방어:</b> 인증에 성공하면 기존 세션을 버리고 새로 만든다.
     * 공격자가 미리 심어둔 세션 id 로 피해자가 로그인하면, 그 id 를 아는 공격자가
     * 그대로 로그인 상태를 물려받는다. 세션 id 를 갈아 끼우면 그 통로가 끊긴다.
     */
    @PostMapping("/login")
    public MeResponse login(@Valid @RequestBody AuthRequests.Login request,
                            HttpServletRequest httpRequest,
                            HttpServletResponse httpResponse) {

        Member member = authService.login(request.email(), request.password());

        HttpSession existing = httpRequest.getSession(false);
        if (existing != null) {
            existing.invalidate();
        }
        httpRequest.getSession(true);

        MemberPrincipal principal = MemberPrincipal.from(member);
        Authentication authentication = UsernamePasswordAuthenticationToken.authenticated(
                principal, null, principal.getAuthorities());

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        contextRepository.saveContext(context, httpRequest, httpResponse);

        return MeResponse.from(principal);
    }

    /**
     * 로그아웃.
     *
     * <p>세션을 <b>서버에서</b> 지운다. 쿠키만 지우면 그 세션 id 를 이미 가로챈 쪽은
     * 계속 쓸 수 있다. 세션 테이블에서 사라져야 진짜 로그아웃이다.
     *
     * <p>이미 로그아웃 상태여도 204 다. 멱등해야 프론트가 재시도해도 안전하다.
     */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest httpRequest) {
        HttpSession session = httpRequest.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        SecurityContextHolder.clearContext();
        return ResponseEntity.noContent().build();
    }

    /** 현재 로그인한 회원. 인증이 없으면 필터 단계에서 401 이 나간다. */
    @GetMapping("/me")
    public MeResponse me(Authentication authentication) {
        return MeResponse.from((MemberPrincipal) authentication.getPrincipal());
    }

    /**
     * 로그인 응답 · 내 정보 응답.
     *
     * <p>이메일과 이름만 준다. 가입일·마지막 로그인 시각처럼 화면이 쓰지 않는 값은
     * 넣지 않는다 — 한 번 내보낸 필드는 없애기 어렵다.
     */
    public record MeResponse(String email, String name, List<String> roles) {

        static MeResponse from(MemberPrincipal principal) {
            return new MeResponse(
                    principal.getEmail(),
                    principal.getName(),
                    principal.getAuthorities().stream()
                            .map(a -> a.getAuthority())
                            .toList());
        }
    }
}
