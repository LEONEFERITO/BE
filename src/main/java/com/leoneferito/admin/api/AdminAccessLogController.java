package com.leoneferito.admin.api;

import com.leoneferito.admin.AdminAccessLog;
import com.leoneferito.admin.AdminAccessLogRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/access-logs")
public class AdminAccessLogController {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final int PAGE_SIZE = 50;
    private final AdminAccessLogRepository logs;
    public AdminAccessLogController(AdminAccessLogRepository logs) {this.logs = logs;}
    @GetMapping
    public PageView list(@RequestParam(required = false) UUID actorId, @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from, @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to, @RequestParam(required = false) String q, @RequestParam(defaultValue = "0") int page) {
        Instant fromAt = from == null ? null : from.atStartOfDay(SEOUL).toInstant();
        Instant toAt = to == null ? null : to.plusDays(1).atStartOfDay(SEOUL).toInstant();
        String path = q == null || q.isBlank() ? null : "%" + q.trim().replace("%", "\\%").replace("_", "\\_") + "%";
        Page<AdminAccessLog> found = logs.search(actorId, fromAt, toAt, path, PageRequest.of(Math.max(page, 0), PAGE_SIZE));
        return new PageView(found.getContent().stream().map(Row::of).toList(), found.getNumber(), found.getTotalPages(), found.getTotalElements());
    }
    public record Row(long id, UUID actorId, String actorEmail, String method, String path, int status, String ip, Instant createdAt) { static Row of(AdminAccessLog l) {return new Row(l.getId(), l.getActorId(), l.getActorEmail(), l.getMethod(), l.getPath(), l.getStatus(), l.getIp(), l.getCreatedAt());}}
    public record PageView(List<Row> items, int page, int totalPages, long total) { }
}