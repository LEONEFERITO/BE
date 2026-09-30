package com.leoneferito.auth.social;

import com.leoneferito.auth.MemberPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Component;

@Component
public class SocialLoginHandlers implements AuthenticationSuccessHandler, AuthenticationFailureHandler {

    private static final Logger log = LoggerFactory.getLogger(SocialLoginHandlers.class);
    private final SecurityContextRepository contextRepository;
    private final String frontBaseUrl;
    public SocialLoginHandlers(SecurityContextRepository contextRepository, @Value("${app.front.base-url}") String frontBaseUrl) {
        this.contextRepository = contextRepository;
        this.frontBaseUrl = frontBaseUrl;
    }
    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response, Authentication authentication) throws IOException {
        MemberPrincipal principal = ((SocialLoginService.SocialUser) authentication.getPrincipal()).principal();
        Authentication ours = UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities());
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(ours);
        SecurityContextHolder.setContext(context);
        contextRepository.saveContext(context, request, response);
        response.sendRedirect(frontBaseUrl + "/mypage");
    }
    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
                                        AuthenticationException exception) throws IOException {
        String code = exception instanceof OAuth2AuthenticationException e
                ? e.getError().getErrorCode()
                : "failed";
        if (!code.matches("[a-z_]{1,32}")) {
            code = "failed";
        }
        log.info("간편 로그인 실패 code={}", code);
        response.sendRedirect(frontBaseUrl + "/login?social=" + code);
    }
}