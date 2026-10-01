package com.leoneferito.member;

import static com.leoneferito.member.MemberTestSupport.csrf;
import static com.leoneferito.member.MemberTestSupport.login;
import static com.leoneferito.member.MemberTestSupport.sessionCount;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.leoneferito.TestMailConfiguration;
import com.leoneferito.TestMailConfiguration.RecordingMailer;
import com.leoneferito.TestcontainersConfiguration;
import com.leoneferito.auth.AuthService;
import jakarta.servlet.http.Cookie;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 회원 본인 기능 — 내 정보 · 비밀번호 변경 · 탈퇴 · 비밀번호 찾기.
 *
 * <p>지키려는 것: 세션이 <b>서버에서</b> 끊기는가, 링크를 두 번 쓸 수 없는가,
 * 가입 여부가 비밀번호 찾기로 새지 않는가, 탈퇴하면 개인정보가 남지 않는가.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestMailConfiguration.class})
class MemberAccountApiTest {

    private static final String EMAIL = "kim@example.com";
    private static final String PASSWORD = "first password 1";
    private static final String NEW_PASSWORD = "second password 2";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MemberRepository members;

    @Autowired
    private AuthService authService;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private RecordingMailer mailer;

    @Autowired
    private MemberAccountService accountService;

    @BeforeEach
    void reset() {
        MemberTestSupport.cleanDatabase(jdbc);
        mailer.clear();
        authService.signup(EMAIL, PASSWORD, "김레오", "010-1234-5678");
    }

    @Nested
    @DisplayName("내 정보")
    class Profile {

        @Test
        @DisplayName("이름을 바꾸면 세션의 이름도 바뀐다 — 다시 로그인하지 않아도 헤더가 새 이름이다")
        void updateRefreshesSession() throws Exception {
            Cookie session = login(mockMvc, EMAIL, PASSWORD);

            mockMvc.perform(csrf(mockMvc, patch("/api/me/profile"), session)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"김페리\",\"phone\":\"010-9999-0000\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.name").value("김페리"))
                    .andExpect(jsonPath("$.hasPassword").value(true));

            mockMvc.perform(get("/api/auth/me").cookie(session))
                    .andExpect(jsonPath("$.name").value("김페리"));
        }

        @Test
        @DisplayName("로그인하지 않으면 볼 수 없다")
        void requiresLogin() throws Exception {
            mockMvc.perform(get("/api/me/profile")).andExpect(status().isUnauthorized());
        }
    }

    @Nested
    @DisplayName("비밀번호 변경")
    class ChangePassword {

        @Test
        @DisplayName("현재 비밀번호가 틀리면 400 이다 — 401 이면 화면이 로그아웃으로 읽는다")
        void wrongCurrentPassword() throws Exception {
            Cookie session = login(mockMvc, EMAIL, PASSWORD);

            mockMvc.perform(csrf(mockMvc, post("/api/me/password"), session)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"currentPassword\":\"not it at all\",\"newPassword\":\"%s\"}"
                                    .formatted(NEW_PASSWORD)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("WRONG_PASSWORD"));

            // 틀린 횟수는 로그인 실패와 똑같이 센다.
            assertThat(members.findByEmail(EMAIL).orElseThrow().getFailedLoginAttempts()).isEqualTo((short) 1);
        }

        @Test
        @DisplayName("바꾸면 다른 기기의 세션은 끊기고 지금 세션은 남는다")
        void otherSessionsTerminated() throws Exception {
            Cookie here = login(mockMvc, EMAIL, PASSWORD);
            Cookie otherDevice = login(mockMvc, EMAIL, PASSWORD);

            mockMvc.perform(csrf(mockMvc, post("/api/me/password"), here)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"currentPassword\":\"%s\",\"newPassword\":\"%s\"}"
                                    .formatted(PASSWORD, NEW_PASSWORD)))
                    .andExpect(status().isNoContent());

            mockMvc.perform(get("/api/auth/me").cookie(here)).andExpect(status().isOk());
            mockMvc.perform(get("/api/auth/me").cookie(otherDevice)).andExpect(status().isUnauthorized());

            login(mockMvc, EMAIL, NEW_PASSWORD);
        }

        @Test
        @DisplayName("간편가입 계정은 바꿀 비밀번호가 없다")
        void socialHasNoPassword() throws Exception {
            members.save(Member.social(UUID.randomUUID(), MemberProvider.KAKAO, "k-1",
                    "social@example.com", "카카오회원"));
            // 간편가입 회원은 비밀번호 로그인이 안 되므로 서비스로 직접 확인한다.
            Member social = members.findByEmail("social@example.com").orElseThrow();
            org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                            accountService.changePassword(social.getId(), "x", NEW_PASSWORD, null))
                    .isInstanceOf(MemberAccountService.NoPasswordException.class);
        }
    }

    @Nested
    @DisplayName("탈퇴")
    class Withdraw {

        @Test
        @DisplayName("비밀번호가 틀리면 탈퇴되지 않는다")
        void requiresPassword() throws Exception {
            Cookie session = login(mockMvc, EMAIL, PASSWORD);

            mockMvc.perform(csrf(mockMvc, post("/api/me/withdraw"), session)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"password\":\"not it at all\"}"))
                    .andExpect(status().isBadRequest());

            assertThat(members.findByEmail(EMAIL)).isPresent();
        }

        @Test
        @DisplayName("탈퇴하면 개인정보가 지워지고, 모든 세션이 끊기고, 같은 이메일로 다시 가입할 수 있다")
        void anonymizesAndFreesEmail() throws Exception {
            Cookie session = login(mockMvc, EMAIL, PASSWORD);
            Cookie otherDevice = login(mockMvc, EMAIL, PASSWORD);
            UUID id = members.findByEmail(EMAIL).orElseThrow().getId();

            mockMvc.perform(csrf(mockMvc, post("/api/me/withdraw"), session)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"password\":\"%s\"}".formatted(PASSWORD)))
                    .andExpect(status().isNoContent());

            Member withdrawn = members.findById(id).orElseThrow();
            assertThat(withdrawn.getStatus()).isEqualTo(MemberStatus.WITHDRAWN);
            assertThat(withdrawn.getEmail()).doesNotContain("kim");
            assertThat(withdrawn.getName()).isEqualTo("탈퇴 회원");
            assertThat(withdrawn.getPhone()).isNull();
            assertThat(withdrawn.getPasswordHash()).isNull();
            assertThat(withdrawn.getWithdrawnAt()).isNotNull();

            mockMvc.perform(get("/api/auth/me").cookie(otherDevice)).andExpect(status().isUnauthorized());
            assertThat(sessionCount(jdbc, EMAIL)).isZero();

            authService.signup(EMAIL, PASSWORD, "새 김레오", null);
        }

        @Test
        @DisplayName("관리자는 스스로 탈퇴할 수 없다 — 마지막 관리자가 사라지지 않게")
        void adminCannotWithdraw() throws Exception {
            Member member = members.findByEmail(EMAIL).orElseThrow();
            member.changeRole(MemberRole.ADMIN);
            members.saveAndFlush(member);
            Cookie session = login(mockMvc, EMAIL, PASSWORD);

            mockMvc.perform(csrf(mockMvc, post("/api/me/withdraw"), session)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"password\":\"%s\"}".formatted(PASSWORD)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("ADMIN_CANNOT_WITHDRAW"));
        }
    }

    @Nested
    @DisplayName("비밀번호 찾기")
    class PasswordReset {

        private static final Pattern LINK = Pattern.compile("/reset/#token=([A-Za-z0-9_-]+)");

        private void request(String email) throws Exception {
            mockMvc.perform(csrf(mockMvc, post("/api/auth/password-reset/request"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"%s\"}".formatted(email)))
                    .andExpect(status().isAccepted());
        }

        private String tokenFromLastMail() {
            String text = mailer.sent().getLast().text();
            Matcher m = LINK.matcher(text);
            assertThat(m.find()).as("메일에 재설정 링크가 있어야 한다").isTrue();
            return m.group(1);
        }

        private org.springframework.test.web.servlet.ResultActions confirm(String token, String password)
                throws Exception {
            return mockMvc.perform(csrf(mockMvc, post("/api/auth/password-reset/confirm"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"token\":\"%s\",\"newPassword\":\"%s\"}".formatted(token, password)));
        }

        @Test
        @DisplayName("가입 안 된 이메일도 같은 답(202)이고, 메일은 가지 않는다 — 가입 여부가 새지 않는다")
        void noEnumeration() throws Exception {
            request("nobody@example.com");
            assertThat(mailer.sent()).isEmpty();
        }

        @Test
        @DisplayName("링크로 비밀번호를 바꾸면 모든 세션이 끊기고, 같은 링크는 다시 쓸 수 없다")
        void resetFlow() throws Exception {
            Cookie session = login(mockMvc, EMAIL, PASSWORD);
            request(EMAIL.toUpperCase());

            assertThat(mailer.sent()).hasSize(1);
            assertThat(mailer.sent().getFirst().to()).isEqualTo(EMAIL);
            String token = tokenFromLastMail();

            // DB 에는 원문이 없다.
            Long raw = jdbc.queryForObject(
                    "SELECT count(*) FROM password_reset_token WHERE token_hash = ?", Long.class, token);
            assertThat(raw).isZero();

            confirm(token, NEW_PASSWORD).andExpect(status().isNoContent());

            mockMvc.perform(get("/api/auth/me").cookie(session)).andExpect(status().isUnauthorized());
            login(mockMvc, EMAIL, NEW_PASSWORD);

            confirm(token, "third password 3")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_RESET_TOKEN"));
        }

        @Test
        @DisplayName("새 링크를 받으면 옛 링크는 죽는다")
        void newLinkKillsOld() throws Exception {
            request(EMAIL);
            String first = tokenFromLastMail();
            request(EMAIL);

            confirm(first, NEW_PASSWORD).andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("30분이 지난 링크는 쓸 수 없다")
        void expired() throws Exception {
            request(EMAIL);
            String token = tokenFromLastMail();
            jdbc.update("UPDATE password_reset_token SET expires_at = now() - interval '1 minute'");

            confirm(token, NEW_PASSWORD).andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("재설정에도 비밀번호 규칙이 똑같이 걸린다 — 뒷문이 되지 않게")
        void samePolicy() throws Exception {
            request(EMAIL);
            confirm(tokenFromLastMail(), "short")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("WEAK_PASSWORD"));
        }

        @Test
        @DisplayName("한 시간에 5통까지만 보낸다")
        void rateLimited() throws Exception {
            for (int i = 0; i < 7; i++) {
                request(EMAIL);
            }
            assertThat(mailer.sent()).hasSize(PasswordResetLimit.MAX);
        }

        @Test
        @DisplayName("간편가입 계정에는 링크 대신 로그인 방법을 보낸다")
        void socialGetsGuide() throws Exception {
            members.save(Member.social(UUID.randomUUID(), MemberProvider.NAVER, "n-1",
                    "naver@example.com", "네이버회원"));
            request("naver@example.com");

            assertThat(mailer.sent()).hasSize(1);
            assertThat(mailer.sent().getFirst().text()).contains("네이버").doesNotContain("#token=");
        }

        @Test
        @DisplayName("정지된 계정에는 보내지 않는다 — 정지를 재설정으로 우회하지 못한다")
        void suspendedGetsNothing() throws Exception {
            Member member = members.findByEmail(EMAIL).orElseThrow();
            member.suspend();
            members.saveAndFlush(member);

            request(EMAIL);
            assertThat(mailer.sent()).isEmpty();
        }
    }

    /** 테스트가 서비스 상수를 직접 보게 한다(같은 패키지가 아니라서). */
    static final class PasswordResetLimit {
        static final int MAX = 5;
    }
}
