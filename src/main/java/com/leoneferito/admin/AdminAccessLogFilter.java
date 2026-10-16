package com.leoneferito.admin;

import com.leoneferito.auth.MemberPrincipal;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class AdminAccessLogFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(AdminAccessLogFilter.class);

    private final AdminAccessLogRepository logs;

    public AdminAccessLogFilter(AdminAccessLogRepository logs) {
        this.logs = logs;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !path.startsWith("/api/admin/") || "OPTIONS".equals(request.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            chain.doFilter(request, response);
        } finally {
            record(request, response);
        }
    }

    private void record(HttpServletRequest request, HttpServletResponse response) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof MemberPrincipal me)) {
            return;
        }
        try {
            logs.save(new AdminAccessLog(me.getId(), me.getEmail(), request.getMethod(), request.getRequestURI(),
                    response.getStatus(), clientIp(request), request.getHeader("User-Agent")));
        } catch (RuntimeException e) {
            log.error("관리자 접속 기록 저장 실패 {} {} by={}", request.getMethod(), request.getRequestURI(), me.getEmail(), e);
        }
    }

    static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}