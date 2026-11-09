package com.leoneferito.visit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.leoneferito.TestcontainersConfiguration;
import com.leoneferito.common.RetentionCleanup;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

/** 접속 통계 (V51) — 기록(쿠키 · CSRF 없이) · 거르기 · 관리자 묶음 · 보관 기간 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Transactional
class VisitApiTest {

    private static final String CHROME = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36";
    private static final String IPHONE = "Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Mobile/15E148";
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private RetentionCleanup cleanup;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM site_visit");
    }

    private ResultActions hit(String json, String ua, String ip) throws Exception {
        return mockMvc.perform(post("/api/visits").contentType(MediaType.APPLICATION_JSON).content(json)
                .header("User-Agent", ua).with(r -> {
                    r.setRemoteAddr(ip);
                    return r;
                }));
    }

    private List<Map<String, Object>> rows() {
        return jdbc.queryForList("SELECT * FROM site_visit ORDER BY id");
    }

    @Test
    @DisplayName("로그인 · CSRF 없이 기록된다 — 유입 경로는 referrer 로 가르고, IP 원본은 어디에도 없다")
    void records() throws Exception {
        hit("{\"path\":\"/products/a/?x=1\",\"referrer\":\"https://search.naver.com/search.naver?query=수트\",\"entry\":true}", CHROME, "203.0.113.7")
                .andExpect(status().isNoContent());
        hit("{\"path\":\"/category/suit/\",\"entry\":false}", CHROME, "203.0.113.7").andExpect(status().isNoContent());

        List<Map<String, Object>> rows = rows();
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0)).containsEntry("path", "/products/a/").containsEntry("source", "NAVER")
                .containsEntry("referrer_host", "search.naver.com").containsEntry("device", "DESKTOP").containsEntry("entry", true);
        assertThat((String) rows.get(0).get("visitor")).hasSize(16).isEqualTo(rows.get(1).get("visitor"));
        assertThat(rows.toString()).doesNotContain("203.0.113.7");
        // 이어 본 페이지는 유입 경로를 갖지 않는다
        assertThat(rows.get(1)).containsEntry("source", "DIRECT").containsEntry("referrer_host", null);
    }

    @Test
    @DisplayName("utm_source 가 referrer 보다 먼저 · 우리 사이트 안에서 온 referrer 는 직접 · 휴대폰은 MOBILE")
    void sources() throws Exception {
        hit("{\"path\":\"/\",\"referrer\":\"https://l.facebook.com/\",\"entry\":true,\"utmSource\":\"instagram\",\"utmMedium\":\"story\"}", IPHONE, "198.51.100.1");
        hit("{\"path\":\"/\",\"referrer\":\"http://localhost:3000/cart/\",\"entry\":true}", IPHONE, "198.51.100.2");
        hit("{\"path\":\"/\",\"entry\":true}", CHROME, "198.51.100.3");
        hit("{\"path\":\"/\",\"referrer\":\"https://blog.example.org/post\",\"entry\":true}", CHROME, "198.51.100.4");

        List<Map<String, Object>> rows = rows();
        assertThat(rows).extracting(r -> r.get("source")).containsExactly("INSTAGRAM", "DIRECT", "DIRECT", "OTHER");
        assertThat(rows.get(0)).containsEntry("utm_medium", "story").containsEntry("device", "MOBILE");
        assertThat(rows.get(3)).containsEntry("referrer_host", "blog.example.org");
    }

    @Test
    @DisplayName("로봇 · 관리자 화면 · 이상한 주소 · 브라우저 정보 없음은 세지 않는다(그래도 204)")
    void ignores() throws Exception {
        hit("{\"path\":\"/\",\"entry\":true}", "Mozilla/5.0 (compatible; Googlebot/2.1)", "192.0.2.1").andExpect(status().isNoContent());
        hit("{\"path\":\"/admin/orders/\",\"entry\":true}", CHROME, "192.0.2.1").andExpect(status().isNoContent());
        hit("{\"path\":\"https://evil.example/\",\"entry\":true}", CHROME, "192.0.2.1").andExpect(status().isNoContent());
        hit("{\"path\":\"/\",\"entry\":true}", "", "192.0.2.1").andExpect(status().isNoContent());
        assertThat(rows()).isEmpty();
    }

    private void insert(ZonedDateTime at, String visitor, boolean entry, String path, String source, String host, String device) {
        jdbc.update("INSERT INTO site_visit (visited_at, visitor, entry, path, source, referrer_host, device) VALUES (?, ?, ?, ?, ?, ?, ?)",
                Timestamp.from(at.toInstant()), visitor, entry, path, source, host, device);
    }

    @Test
    @DisplayName("관리자 묶음 — 한국 날짜 · 요일 · 시간대로, 빈 날 · 시간은 0. 방문자는 날마다 다른 사람 수")
    void stats() throws Exception {
        // 11/2(월) 23:30 한국 = UTC 14:30 — 한국 날짜로 묶여야 한다
        insert(ZonedDateTime.of(2026, 11, 2, 23, 30, 0, 0, SEOUL), "aaaaaaaaaaaaaaaa", true, "/", "NAVER", "search.naver.com", "MOBILE");
        insert(ZonedDateTime.of(2026, 11, 2, 23, 31, 0, 0, SEOUL), "aaaaaaaaaaaaaaaa", false, "/category/suit/", "DIRECT", null, "MOBILE");
        insert(ZonedDateTime.of(2026, 11, 2, 23, 40, 0, 0, SEOUL), "aaaaaaaaaaaaaaaa", true, "/products/x/", "INSTAGRAM", "l.instagram.com", "MOBILE");
        insert(ZonedDateTime.of(2026, 11, 4, 9, 5, 0, 0, SEOUL), "bbbbbbbbbbbbbbbb", true, "/", "NAVER", "search.naver.com", "DESKTOP");
        // 기간 밖
        insert(ZonedDateTime.of(2026, 11, 5, 0, 0, 0, 0, SEOUL), "cccccccccccccccc", true, "/", "DIRECT", null, "DESKTOP");

        mockMvc.perform(get("/api/admin/visit-stats").param("from", "2026-11-02").param("to", "2026-11-04")
                        .with(user("ops").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totals.visitors").value(2))
                .andExpect(jsonPath("$.totals.sessions").value(3))
                .andExpect(jsonPath("$.totals.pageViews").value(4))
                .andExpect(jsonPath("$.daily.length()").value(3))
                .andExpect(jsonPath("$.daily[0].date").value("2026-11-02"))
                .andExpect(jsonPath("$.daily[0].sessions").value(2))
                .andExpect(jsonPath("$.daily[1].pageViews").value(0))
                .andExpect(jsonPath("$.weekdays.length()").value(7))
                .andExpect(jsonPath("$.weekdays[0].sessions").value(2)) // 월요일
                .andExpect(jsonPath("$.weekdays[2].sessions").value(1)) // 수요일
                .andExpect(jsonPath("$.hours.length()").value(24))
                .andExpect(jsonPath("$.hours[23].sessions").value(2))
                .andExpect(jsonPath("$.hours[9].sessions").value(1))
                .andExpect(jsonPath("$.sources[0].key").value("NAVER"))
                .andExpect(jsonPath("$.sources[0].sessions").value(2))
                .andExpect(jsonPath("$.referrers[0].key").value("search.naver.com"))
                .andExpect(jsonPath("$.landings[0].key").value("/"))
                .andExpect(jsonPath("$.devices[0].key").value("MOBILE"));
    }

    @Test
    @DisplayName("관리자만 본다 · 거꾸로 된 기간이나 366일을 넘는 기간은 400 · 기간을 비우면 최근 30일")
    void statsGuard() throws Exception {
        mockMvc.perform(get("/api/admin/visit-stats")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/admin/visit-stats").with(user("guest").roles("MEMBER"))).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/admin/visit-stats").param("from", "2026-11-04").param("to", "2026-11-01")
                .with(user("ops").roles("ADMIN"))).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/admin/visit-stats").param("from", "2025-01-01").param("to", "2026-11-01")
                .with(user("ops").roles("ADMIN"))).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/admin/visit-stats").with(user("ops").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.daily.length()").value(30))
                .andExpect(jsonPath("$.to").value(LocalDate.now(SEOUL).toString()));
    }

    @Test
    @DisplayName("매일 정리 — 2년 지난 기록과 지난 날의 해시 소금을 지운다")
    void retention() {
        Instant now = Instant.parse("2026-11-09T00:00:00Z");
        insert(ZonedDateTime.of(2024, 11, 1, 12, 0, 0, 0, SEOUL), "dddddddddddddddd", true, "/", "DIRECT", null, "DESKTOP");
        insert(ZonedDateTime.of(2026, 11, 8, 12, 0, 0, 0, SEOUL), "eeeeeeeeeeeeeeee", true, "/", "DIRECT", null, "DESKTOP");
        jdbc.update("INSERT INTO site_visit_salt (day, salt) VALUES (DATE '2026-11-08', 'old'), (DATE '2026-11-09', 'today') ON CONFLICT DO NOTHING");

        RetentionCleanup.Result r = cleanup.run(now);
        assertThat(r.visits()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM site_visit", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT day::text FROM site_visit_salt", String.class)).doesNotContain("2026-11-08");
    }
}
