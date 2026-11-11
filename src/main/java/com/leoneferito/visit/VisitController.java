package com.leoneferito.visit;

import com.leoneferito.auth.RateLimiter;
import com.leoneferito.common.error.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
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
 *
 * <p>요청 제한(2026-11-11 보안 강화) — IP 하나가 10분에 {@code app.visit.rate-limit}(기본 120)번까지만 기록한다.
 * 사람이 둘러보면 10분에 수십 쪽이 고작이라, 그 위는 기계가 숫자를 부풀리거나 DB 를 채우려는 것이다.
 * 넘어도 204 로 답하고 기록만 건너뛴다 — 손님 화면은 결과를 보지 않고, 막힌 줄도 모르게 둔다(다시 시도해 봐야 소용없다).
 */
@RestController
public class VisitController {

    private final VisitService visits;
    private final String selfHost;
    private final RateLimiter limiter;

    public VisitController(VisitService visits, @Value("${app.front.base-url}") String frontBaseUrl,
                           @Value("${app.visit.rate-limit:120}") int limit,
                           @Value("${app.visit.rate-window:10m}") Duration window) {
        this.visits = visits;
        this.limiter = new RateLimiter("visit", limit, window, 50_000, Instant::now);
        String host = URI.create(frontBaseUrl).getHost();
        this.selfHost = host == null ? null : host.replaceFirst("^www\\.", "");
    }

    @PostMapping("/api/visits")
    public ResponseEntity<Void> record(@RequestBody VisitService.Hit hit, HttpServletRequest request) {
        String ip = request.getRemoteAddr();
        if (ip != null && !limiter.consume(ip).allowed()) {
            return ResponseEntity.noContent().build();
        }
        visits.record(hit, ip, request.getHeader("User-Agent"), selfHost);
        return ResponseEntity.noContent().build();
    }

    /**
     * 콘텐츠 보안 정책 위반 보고 (2026-11-11 보안 강화). 손님 화면의 Caddy 가 «보고만 하기»(Content-Security-Policy-Report-Only)로
     * 스크립트 출처 제한을 걸어 두고, 막혔을 «뻔한» 것을 브라우저가 여기로 보낸다. 1~2주 모은 뒤 빠진 출처가 없으면 실제로 켠다.
     * 저장하지 않고 로그 한 줄만(어느 쪽 · 무엇이 · 어느 페이지에서). 로그인 · CSRF 없이 받는다 — 브라우저가 쿠키 없이 보낸다.
     * 접속 통계와 같은 IP 당 제한을 쓴다(한 페이지가 위반을 여러 번 낼 수 있다). 언제나 204.
     */
    @PostMapping(value = "/api/csp-report", consumes = MediaType.ALL_VALUE)
    public ResponseEntity<Void> cspReport(@RequestBody(required = false) String body, HttpServletRequest request) {
        String ip = request.getRemoteAddr();
        if (body != null && (ip == null || limiter.consume(ip).allowed())) {
            log.warn("CSP 위반 보고 directive={} blocked={} page={}",
                    field(body, "effective-directive", "effectiveDirective", "violated-directive"),
                    field(body, "blocked-uri", "blockedURL"), field(body, "document-uri", "documentURL"));
        }
        return ResponseEntity.noContent().build();
    }

    /** 보고 JSON 에서 값 하나(두 형식: report-uri 의 하이픈 이름 · Reporting API 의 낙타 이름). 길이를 자르고 줄바꿈을 지운다 */
    private static String field(String body, String... names) {
        for (String n : names) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"" + n + "\"\s*:\s*\"([^\"]{0,300})").matcher(body);
            if (m.find()) {
                return m.group(1).replaceAll("[\r\n]", " ");
            }
        }
        return "-";
    }

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(VisitController.class);

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
