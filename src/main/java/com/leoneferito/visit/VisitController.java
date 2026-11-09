package com.leoneferito.visit;

import com.leoneferito.common.error.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.time.LocalDate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 접속 통계 (V51).
 *
 * <ul>
 *   <li>POST /api/visits — 손님 화면이 페이지를 열 때 보낸다. 로그인 · 쿠키 · CSRF 토큰 없이 받는다(SecurityConfig).
 *       위조해도 숫자가 늘 뿐 얻을 것이 없고, 저장하는 값은 모두 길이를 자른다. 언제나 204 — 화면은 결과를 기다리지 않는다.</li>
 *   <li>GET /api/admin/visit-stats — 관리자 «통계 › 접속 통계». 기간(한국 날짜)을 받아 묶은 숫자를 준다.</li>
 * </ul>
 *
 * <p>IP 는 {@code getRemoteAddr()} — 운영에서는 프록시가 넘긴 실제 손님 주소다(AuthRateLimitFilter 주석 참고). 저장하지 않고 해시에만 쓴다.
 */
@RestController
public class VisitController {

    private final VisitService visits;
    private final String selfHost;

    public VisitController(VisitService visits, @Value("${app.front.base-url}") String frontBaseUrl) {
        this.visits = visits;
        String host = URI.create(frontBaseUrl).getHost();
        this.selfHost = host == null ? null : host.replaceFirst("^www\\.", "");
    }

    @PostMapping("/api/visits")
    public ResponseEntity<Void> record(@RequestBody VisitService.Hit hit, HttpServletRequest request) {
        visits.record(hit, request.getRemoteAddr(), request.getHeader("User-Agent"), selfHost);
        return ResponseEntity.noContent().build();
    }

    /** from · to — 한국 날짜(yyyy-MM-dd), 둘 다 포함. 비우면 최근 30일 */
    @GetMapping("/api/admin/visit-stats")
    public ResponseEntity<?> stats(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        LocalDate end = to != null ? to : LocalDate.now(VisitService.SEOUL);
        LocalDate start = from != null ? from : end.minusDays(29);
        try {
            return ResponseEntity.ok(visits.stats(start, end));
        } catch (IllegalArgumentException e) {
            // 거꾸로 된 기간 · 너무 긴 기간
            return ResponseEntity.badRequest().body(ErrorResponse.of("INVALID_RANGE", e.getMessage(), null));
        }
    }
}
