package com.leoneferito.member;

import static org.assertj.core.api.Assertions.assertThat;

import com.leoneferito.TestMailConfiguration;
import com.leoneferito.TestcontainersConfiguration;
import com.leoneferito.auth.AuthService;
import com.leoneferito.common.RetentionCleanup;
import com.leoneferito.media.MediaStorage;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** 매일 정리(2026-11-04) — 방침의 보관 기간이 지난 것만 지우고, 아직 쓰는 것은 남긴다. */
@SpringBootTest
@Import({TestcontainersConfiguration.class, TestMailConfiguration.class})
class RetentionCleanupTest {

    @TempDir
    static Path store;

    @DynamicPropertySource
    static void media(DynamicPropertyRegistry registry) {
        registry.add("app.media.storage-dir", () -> store.toString());
    }

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private AuthService auth;
    @Autowired
    private RetentionCleanup cleanup;
    @Autowired
    private MediaStorage storage;

    private UUID memberId;

    @BeforeEach
    void setUp() {
        MemberTestSupport.cleanDatabase(jdbc);
        memberId = auth.signup("keep@example.com", "long enough secret 9", "보관", "010-1234-5678");
    }

    @AfterEach
    void tearDown() {
        MemberTestSupport.cleanDatabase(jdbc);
        jdbc.update("UPDATE site_image SET media_id = NULL WHERE slot = 'OFFLINE_SHOP'");
        jdbc.update("DELETE FROM media_asset WHERE private");
    }

    /** 공개 사진 한 장(상품 · 배너용) — 파일까지 실제로 만든다. 돌려주는 값은 [id, 저장 키] */
    private Object[] media(Instant createdAt) throws Exception {
        String key = storage.store(new ByteArrayInputStream(new byte[] {1, 2, 3}), com.leoneferito.media.ImageFormat.JPEG);
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO media_asset (id, original_filename, content_type, byte_size, storage_key, created_at) "
                + "VALUES (?, 'p.jpg', 'image/jpeg', 3, ?, ?)", id, key, Timestamp.from(createdAt));
        return new Object[] {id, key};
    }

    @Test
    @DisplayName("아무 데서도 쓰지 않는 사진은 일주일 뒤 줄과 파일을 지우고, 쓰는 사진 · 방금 올린 사진은 남긴다")
    void orphanMedia() throws Exception {
        Instant now = Instant.now();
        Object[] orphan = media(now.minus(Duration.ofDays(10)));
        Object[] used = media(now.minus(Duration.ofDays(10)));
        Object[] fresh = media(now.minus(Duration.ofDays(2)));
        assertThat(jdbc.update("UPDATE site_image SET media_id = ? WHERE slot = 'OFFLINE_SHOP'", used[0])).isEqualTo(1);

        RetentionCleanup.Result r = cleanup.run(now);

        assertThat(r.media()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM media_asset WHERE id = ?", Long.class, orphan[0])).isZero();
        assertThat(Files.exists(store.resolve((String) orphan[1]))).isFalse();
        for (Object[] kept : List.of(used, fresh)) {
            assertThat(jdbc.queryForObject("SELECT count(*) FROM media_asset WHERE id = ?", Long.class, kept[0])).isEqualTo(1);
            assertThat(Files.exists(store.resolve((String) kept[1]))).isTrue();
        }
    }

    /** 반품 사진 한 장 — 파일까지 실제로 만든다. 돌려주는 값은 저장 키 */
    private String photo(Instant createdAt) throws Exception {
        String key = storage.store(new ByteArrayInputStream(new byte[] {1, 2, 3}), com.leoneferito.media.ImageFormat.JPEG);
        UUID media = UUID.randomUUID();
        jdbc.update("INSERT INTO media_asset (id, original_filename, content_type, byte_size, storage_key, private) "
                + "VALUES (?, 'r.jpg', 'image/jpeg', 3, ?, TRUE)", media, key);
        jdbc.update("INSERT INTO return_photo (id, member_id, media_id, url, created_at) VALUES (?, ?, ?, '/x', ?)",
                UUID.randomUUID(), memberId, media, Timestamp.from(createdAt));
        return key;
    }

    @Test
    @DisplayName("하루 지난 미첨부 반품 사진은 줄과 파일을 지우고, 방금 올린 사진은 남긴다")
    void photos() throws Exception {
        Instant now = Instant.now();
        String old = photo(now.minus(Duration.ofHours(30)));
        String fresh = photo(now.minus(Duration.ofHours(2)));

        RetentionCleanup.Result r = cleanup.run(now);

        assertThat(r.photos()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM return_photo", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM media_asset WHERE storage_key = ?", Long.class, old)).isZero();
        assertThat(Files.exists(store.resolve(old))).isFalse();
        assertThat(Files.exists(store.resolve(fresh))).isTrue();
    }

    @Test
    @DisplayName("하루 지난 재설정 링크 · 2년 지난 관리자 기록만 지운다")
    void tokensAndLogs() {
        Instant now = Instant.now();
        jdbc.update("INSERT INTO password_reset_token (id, member_id, token_hash, expires_at, created_at) VALUES (?, ?, 'old', ?, ?)",
                UUID.randomUUID(), memberId, Timestamp.from(now.minus(Duration.ofDays(2))), Timestamp.from(now.minus(Duration.ofDays(2))));
        jdbc.update("INSERT INTO password_reset_token (id, member_id, token_hash, expires_at, created_at) VALUES (?, ?, 'new', ?, ?)",
                UUID.randomUUID(), memberId, Timestamp.from(now.plus(Duration.ofMinutes(20))), Timestamp.from(now.minus(Duration.ofMinutes(10))));
        jdbc.update("INSERT INTO admin_access_log (actor_email, method, path, status, created_at) VALUES ('a@b.c', 'GET', '/api/admin/x', 200, ?)",
                Timestamp.from(now.minus(Duration.ofDays(800))));
        jdbc.update("INSERT INTO admin_access_log (actor_email, method, path, status, created_at) VALUES ('a@b.c', 'GET', '/api/admin/y', 200, ?)",
                Timestamp.from(now.minus(Duration.ofDays(30))));

        RetentionCleanup.Result r = cleanup.run(now);

        assertThat(r.resetTokens()).isEqualTo(1);
        assertThat(r.adminLogs()).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT token_hash FROM password_reset_token", String.class)).containsExactly("new");
        assertThat(jdbc.queryForList("SELECT path FROM admin_access_log", String.class)).containsExactly("/api/admin/y");
    }
}
