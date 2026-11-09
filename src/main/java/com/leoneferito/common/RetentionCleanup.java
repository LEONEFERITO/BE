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
 *   <li>아무 데서도 쓰지 않는 사진 · 영상: 올리고 {@value #ORPHAN_MEDIA_DAYS}일이 지났으면 지운다(파일까지, 2026-11-05).</li>
 *   <li>접속 통계(V51): {@value #VISIT_YEARS}년이 지나면 지운다. 방문자 해시의 소금은 그날이 지나면 바로 지운다 —
 *       지난 날짜의 해시를 누구였는지 되돌릴 수 없게.</li>
 * </ul>
 *
 * <p>주문 · 결제 기록(5년, 전자상거래법)은 첫 만기가 2031년이라 그때 맞춰 붙인다.
 * 매일 04:10(한국 시간). 실패해도 다음 날 다시 돈다 — 지우는 것뿐이라 두 번 돌아도 같다.
 */
@Component
public class RetentionCleanup {

    private static final Logger log = LoggerFactory.getLogger(RetentionCleanup.class);
    static final int ADMIN_LOG_YEARS = 2;
    static final int ORPHAN_MEDIA_DAYS = 7;
    static final int VISIT_YEARS = 2;

    private final JdbcTemplate jdbc;
    private final MediaStorage storage;

    public RetentionCleanup(JdbcTemplate jdbc, MediaStorage storage) {
        this.jdbc = jdbc;
        this.storage = storage;
    }

    public record Result(int photos, int resetTokens, int adminLogs, int media, int visits) {
    }

    @Scheduled(cron = "${app.retention.cron:0 10 4 * * *}", zone = "Asia/Seoul")
    public void daily() {
        Result r = run(Instant.now());
        log.info("정리 작업 끝 반품사진={} 재설정링크={} 관리자기록={} 안쓰는사진={} 접속통계={}",
                r.photos(), r.resetTokens(), r.adminLogs(), r.media(), r.visits());
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
        deleteFiles(keys, "반품 사진");

        // 2. 지난 재설정 링크
        int tokens = jdbc.update("DELETE FROM password_reset_token WHERE created_at < ?", dayAgo);

        // 3. 오래된 관리자 접속 · 처리 기록
        Timestamp yearsAgo = Timestamp.from(now.minus(Duration.ofDays(365L * ADMIN_LOG_YEARS)));
        int logs = jdbc.update("DELETE FROM admin_access_log WHERE created_at < ?", yearsAgo)
                + jdbc.update("DELETE FROM member_admin_log WHERE created_at < ?", yearsAgo);

        // 4. 아무 데서도 쓰지 않는 사진 · 영상
        List<String> orphanKeys = deleteOrphanMedia(now);
        deleteFiles(orphanKeys, "사진");

        // 5. 오래된 접속 통계 · 지난 날의 방문자 해시 소금(한국 날짜 기준 오늘 것만 남긴다)
        int visits = jdbc.update("DELETE FROM site_visit WHERE visited_at < ?",
                Timestamp.from(now.minus(Duration.ofDays(365L * VISIT_YEARS))));
        jdbc.update("DELETE FROM site_visit_salt WHERE day < ?", java.time.LocalDate.ofInstant(now, java.time.ZoneId.of("Asia/Seoul")));

        return new Result(keys.size(), tokens, logs, orphanKeys.size(), visits);
    }

    /**
     * 아무 데서도 쓰지 않는 사진 · 영상을 지우고 그 저장 키를 돌려준다(파일은 부르는 쪽이 줄을 지운 뒤에 지운다 — 1번과 같다).
     *
     * <p>상품을 지우거나 사진 · 배너를 바꾸면 파일은 남는다 — 다른 상품 · 배너가 같은 사진을 쓸 수 있어서다.
     * 그렇게 어느 상품 · 배너 · 사이트 사진 칸도 가리키지 않게 된 것을 여기서 치운다.
     *
     * <ul>
     *   <li>가리키는 곳은 DB 의 외래키에서 직접 읽는다 — 사진 칸이 새로 생겨도 이 코드를 고칠 필요가 없다.
     *       외래키를 하나도 못 찾으면(있을 수 없는 일) 전부 지우게 되므로 아무것도 하지 않는다.</li>
     *   <li>외래키가 아닌 곳이 하나 있다 — 주문 항목의 사진 주소(order_item.image_url, 주문 당시 복사본).
     *       지운 상품의 사진이라도 주문 내역에 보이므로 남긴다.</li>
     *   <li>올리고 {@value #ORPHAN_MEDIA_DAYS}일은 기다린다 — 관리자 화면은 사진을 먼저 올리고 저장할 때 붙인다.</li>
     *   <li>반품 사진(private)은 1번이 따로 다룬다.</li>
     * </ul>
     *
     * <p>고르는 것과 지우는 것을 한 문장으로 한다 — 그 사이에 누가 붙인 사진을 지우지 않게.
     */
    private List<String> deleteOrphanMedia(Instant now) {
        List<String> notUsed = jdbc.query("""
                SELECT c.conrelid::regclass::text, quote_ident(a.attname)
                FROM pg_constraint c
                JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = ANY (c.conkey)
                WHERE c.contype = 'f' AND c.confrelid = 'media_asset'::regclass
                """, (rs, i) -> "NOT EXISTS (SELECT 1 FROM " + rs.getString(1) + " r WHERE r." + rs.getString(2) + " = m.id)");
        if (notUsed.isEmpty()) {
            log.warn("사진을 가리키는 외래키를 찾지 못함 — 사진 정리를 건너뜀");
            return List.of();
        }
        return jdbc.queryForList("DELETE FROM media_asset m WHERE NOT m.private AND m.created_at < ? AND "
                        + String.join(" AND ", notUsed)
                        + " AND NOT EXISTS (SELECT 1 FROM order_item oi"
                        + " WHERE right(oi.image_url, char_length(m.storage_key)) = m.storage_key)"
                        + " RETURNING m.storage_key",
                String.class, Timestamp.from(now.minus(Duration.ofDays(ORPHAN_MEDIA_DAYS))));
    }

    private void deleteFiles(List<String> keys, String what) {
        for (String key : keys) {
            try {
                storage.delete(key);
            } catch (IOException e) {
                log.warn("{} 파일을 지우지 못함 key={}", what, key, e);
            }
        }
    }
}