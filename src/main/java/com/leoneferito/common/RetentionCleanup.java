package com.leoneferito.common;

import com.leoneferito.media.MediaStorage;
import java.io.IOException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 매일 정리 — 개인정보처리방침이 말한 보관 기간을 실제로 지킨다 (2026-11-04 오픈 전 점검).
 *
 * <ul>
 *   <li>반품 신청에 붙지 않은 사진: 올리고 하루가 지나면 지운다(파일까지). 신청을 그만둔 손님의 사진이 쌓이지 않게.</li>
 *   <li>비밀번호 재설정 링크: 만든 지 하루가 지나면 지운다(어차피 30분 뒤 쓸 수 없다).</li>
 *   <li>관리자 접속 · 처리 기록: {@value #ADMIN_LOG_YEARS}년이 지나면 지운다(방침의 보관 기간).</li>
 * </ul>
 *
 * <p>주문 · 결제 기록(5년, 전자상거래법)은 첫 만기가 2031년이라 그때 맞춰 붙인다.
 * 매일 04:10(한국 시간). 실패해도 다음 날 다시 돈다 — 지우는 것뿐이라 두 번 돌아도 같다.
 */
@Component
public class RetentionCleanup {

    private static final Logger log = LoggerFactory.getLogger(RetentionCleanup.class);
    static final int ADMIN_LOG_YEARS = 2;

    private final JdbcTemplate jdbc;
    private final MediaStorage storage;

    public RetentionCleanup(JdbcTemplate jdbc, MediaStorage storage) {
        this.jdbc = jdbc;
        this.storage = storage;
    }

    public record Result(int photos, int resetTokens, int adminLogs) {
    }

    @Scheduled(cron = "${app.retention.cron:0 10 4 * * *}", zone = "Asia/Seoul")
    public void daily() {
        Result r = run(Instant.now());
        log.info("정리 작업 끝 반품사진={} 재설정링크={} 관리자기록={}", r.photos(), r.resetTokens(), r.adminLogs());
    }

    @Transactional
    public Result run(Instant now) {
        Timestamp dayAgo = Timestamp.from(now.minus(Duration.ofDays(1)));

        // 1. 신청에 붙지 않은 반품 사진 — 줄을 지운 뒤 파일을 지운다(파일이 먼저 사라지면 깨진 사진이 남는다)
        List<Map<String, Object>> photos = jdbc.queryForList(
                "SELECT p.media_id, m.storage_key FROM return_photo p JOIN media_asset m ON m.id = p.media_id "
                        + "WHERE p.return_id IS NULL AND p.created_at < ?", dayAgo);
        List<String> keys = new ArrayList<>();
        for (Map<String, Object> p : photos) {
            jdbc.update("DELETE FROM return_photo WHERE media_id = ?", p.get("media_id"));
            jdbc.update("DELETE FROM media_asset WHERE id = ?", p.get("media_id"));
            keys.add((String) p.get("storage_key"));
        }
        for (String key : keys) {
            try {
                storage.delete(key);
            } catch (IOException e) {
                log.warn("반품 사진 파일을 지우지 못함 key={}", key, e);
            }
        }

        // 2. 지난 재설정 링크
        int tokens = jdbc.update("DELETE FROM password_reset_token WHERE created_at < ?", dayAgo);

        // 3. 오래된 관리자 접속 · 처리 기록
        Timestamp yearsAgo = Timestamp.from(now.minus(Duration.ofDays(365L * ADMIN_LOG_YEARS)));
        int logs = jdbc.update("DELETE FROM admin_access_log WHERE created_at < ?", yearsAgo)
                + jdbc.update("DELETE FROM member_admin_log WHERE created_at < ?", yearsAgo);

        return new Result(keys.size(), tokens, logs);
    }
}