package com.leoneferito.auth;

import com.leoneferito.member.Member;
import com.leoneferito.member.MemberRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 임시 비밀번호 관리자는 비밀번호를 바꾸기 전까지 관리자 API 를 못 쓴다.
 *
 * <p>화면도 변경 화면으로 보내지만 그건 안내다 — 여기서 서버가 막는다. 세션에 든 값(로그인 시점)이 아니라
 * DB 를 매번 본다. 비밀번호를 바꾼 직후 같은 세션으로 바로 들어갈 수 있어야 하고,
 * 관리자 요청은 많지 않아서 조회 한 번은 싸다.
 */
@Configuration
public class AdminPasswordGate implements WebMvcConfigurer, HandlerInterceptor {

    private final MemberRepository members;

    public AdminPasswordGate(MemberRepository members) {
        this.members = members;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this).addPathPatterns("/api/admin/**");
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof MemberPrincipal principal
                && members.findById(principal.getId()).map(Member::isMustChangePassword).orElse(false)) {
            throw new PasswordChangeRequiredException();
        }
        return true;
    }

    /** 403 PASSWORD_CHANGE_REQUIRED. */
    public static class PasswordChangeRequiredException extends RuntimeException {
        public PasswordChangeRequiredException() {
            super("임시 비밀번호");
        }
    }
}
