package com.leoneferito.member;

import static com.leoneferito.member.MemberTestSupport.csrf;
import static com.leoneferito.member.MemberTestSupport.login;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.leoneferito.TestMailConfiguration;
import com.leoneferito.TestcontainersConfiguration;
import com.leoneferito.auth.AuthService;
import com.leoneferito.auth.PasswordResetService;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.util.UUID;
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
import org.springframework.test.web.servlet.ResultActions;

/**
 * 관리자 회원 관리 · 관리자 지정 · 관리자 생성 명령.
 *
 * <p>여기서 가장 중요한 건 <b>권한이 한 단계라도 새지 않는가</b>다.
 * 일반 관리자가 관리자를 만들 수 없고, 누구도 화면으로 최고 관리자를 만들거나 내릴 수 없고,
 * 자기 자신을 정지·강등할 수 없다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestMailConfiguration.class})
class AdminMemberApiTest {

    private static final String PASSWORD = "long enough secret 9";

    private static final String SUPER = "super@example.com";
    private static final String ADMIN = "admin@example.com";
    private static final String MEMBER = "guest@example.com";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MemberRepository members;

    @Autowired
    private AuthService authService;

    @Autowired
    private AdminMemberService adminMembers;

    @Autowired
    private PasswordResetService passwordReset;

    @Autowired
    private JdbcTemplate jdbc;

    private UUID superId;
    private UUID adminId;
    private UUID memberId;

    @BeforeEach
    void setUp() {
        MemberTestSupport.cleanDatabase(jdbc);
        superId = authService.signup(SUPER, PASSWORD, "최고관리자", null);
        adminId = authService.signup(ADMIN, PASSWORD, "운영자", null);
        memberId = authService.signup(MEMBER, PASSWORD, "손님", "010-2222-3333");
        adminMembers.createOrPromoteByCommand(SUPER, "무시됨", MemberRole.SUPER_ADMIN);
        adminMembers.createOrPromoteByCommand(ADMIN, "무시됨", MemberRole.ADMIN);
    }

    private ResultActions postAs(Cookie session, String path, String body) throws Exception {
        return mockMvc.perform(csrf(mockMvc, post(path), session)
                .contentType(MediaType.APPLICATION_JSON).content(body == null ? "{}" : body));
    }

    private ResultActions changeRole(Cookie session, UUID target, String role) throws Exception {
        return mockMvc.perform(csrf(mockMvc, put("/api/admin/members/" + target + "/role"), session)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\":\"%s\"}".formatted(role)));
    }

    @Nested
    @DisplayName("권한")
    class Authorization {

        @Test
        @DisplayName("일반 회원은 회원 목록을 볼 수 없다")
        void memberForbidden() throws Exception {
            Cookie session = login(mockMvc, MEMBER, PASSWORD);
            mockMvc.perform(get("/api/admin/members").cookie(session)).andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("최고 관리자는 관리자 화면(상품)에도 그대로 들어간다")
        void superAdminIsAlsoAdmin() throws Exception {
            Cookie session = login(mockMvc, SUPER, PASSWORD);
            mockMvc.perform(get("/api/admin/products").cookie(session)).andExpect(status().isOk());
            mockMvc.perform(get("/api/auth/me").cookie(session))
                    .andExpect(jsonPath("$.roles").value(org.hamcrest.Matchers.hasItems(
                            "ROLE_ADMIN", "ROLE_SUPER_ADMIN")));
        }

        @Test
        @DisplayName("일반 관리자는 관리자를 지정할 수 없다 — 뚫린 관리자 계정이 제 편을 늘리지 못하게")
        void adminCannotChangeRole() throws Exception {
            Cookie session = login(mockMvc, ADMIN, PASSWORD);
            changeRole(session, memberId, "ADMIN").andExpect(status().isForbidden());
            assertThat(members.findById(memberId).orElseThrow().getRole()).isEqualTo(MemberRole.MEMBER);
        }
    }

    @Nested
    @DisplayName("검색 · 상세")
    class Search {

        @Test
        @DisplayName("이름 · 이메일 · 전화번호(하이픈 없이)로 찾고, 목록에서는 전화번호를 가린다")
        void searchAndMask() throws Exception {
            Cookie session = login(mockMvc, ADMIN, PASSWORD);

            mockMvc.perform(get("/api/admin/members").param("q", "손님").cookie(session))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalElements").value(1))
                    .andExpect(jsonPath("$.items[0].phone").value("010-****-3333"));

            mockMvc.perform(get("/api/admin/members").param("q", "22223333").cookie(session))
                    .andExpect(jsonPath("$.items[0].email").value(MEMBER));

            mockMvc.perform(get("/api/admin/members").param("q", "%").cookie(session))
                    .andExpect(jsonPath("$.totalElements").value(0));
        }

        @Test
        @DisplayName("상세를 열면 전체 전화번호가 보이고, 열어 본 기록이 남는다")
        void detailIsLogged() throws Exception {
            Cookie session = login(mockMvc, ADMIN, PASSWORD);

            mockMvc.perform(get("/api/admin/members/" + memberId).cookie(session))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.member.phone").value("010-2222-3333"))
                    .andExpect(jsonPath("$.logs[0].action").value("VIEWED"))
                    .andExpect(jsonPath("$.logs[0].createdAt").isNotEmpty())
                    .andExpect(jsonPath("$.logs[0].actor").value("운영자 (admin@example.com)"));
        }
    }

    @Nested
    @DisplayName("정지 · 잠금 해제")
    class Suspension {

        @Test
        @DisplayName("정지하면 그 회원의 세션이 끊기고, 비밀번호가 맞아도 정지 안내가 나온다")
        void suspendAndReactivate() throws Exception {
            Cookie admin = login(mockMvc, ADMIN, PASSWORD);
            Cookie guest = login(mockMvc, MEMBER, PASSWORD);

            postAs(admin, "/api/admin/members/" + memberId + "/suspend", "{\"reason\":\"결제 도용 신고\"}")
                    .andExpect(status().isNoContent());

            mockMvc.perform(get("/api/auth/me").cookie(guest)).andExpect(status().isUnauthorized());
            mockMvc.perform(csrf(mockMvc, post("/api/auth/login"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(MEMBER, PASSWORD)))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("ACCOUNT_SUSPENDED"));

            // 비밀번호가 틀리면 정지 여부를 알려주지 않는다.
            mockMvc.perform(csrf(mockMvc, post("/api/auth/login"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"%s\",\"password\":\"wrong password!\"}".formatted(MEMBER)))
                    .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));

            postAs(admin, "/api/admin/members/" + memberId + "/reactivate", null)
                    .andExpect(status().isNoContent());
            login(mockMvc, MEMBER, PASSWORD);
        }

        @Test
        @DisplayName("정지 사유는 필수다")
        void reasonRequired() throws Exception {
            Cookie admin = login(mockMvc, ADMIN, PASSWORD);
            postAs(admin, "/api/admin/members/" + memberId + "/suspend", "{\"reason\":\" \"}")
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("관리자는 정지할 수 없고, 자기 자신에게는 아무것도 못 한다")
        void guardRails() throws Exception {
            Cookie admin = login(mockMvc, ADMIN, PASSWORD);
            postAs(admin, "/api/admin/members/" + superId + "/suspend", "{\"reason\":\"x\"}")
                    .andExpect(status().isConflict());
            postAs(admin, "/api/admin/members/" + adminId + "/suspend", "{\"reason\":\"x\"}")
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("MEMBER_RULE"));
        }

        @Test
        @DisplayName("잠금 해제하면 바로 로그인할 수 있다")
        void unlock() throws Exception {
            Member guest = members.findById(memberId).orElseThrow();
            for (int i = 0; i < Member.MAX_FAILED_ATTEMPTS; i++) {
                guest.recordFailedLogin(Instant.now());
            }
            members.saveAndFlush(guest);

            Cookie admin = login(mockMvc, ADMIN, PASSWORD);
            postAs(admin, "/api/admin/members/" + memberId + "/unlock", null)
                    .andExpect(status().isNoContent());
            login(mockMvc, MEMBER, PASSWORD);
        }
    }

    @Nested
    @DisplayName("관리자 지정")
    class Roles {

        @Test
        @DisplayName("최고 관리자가 회원을 관리자로 올리면 기록이 남고, 그 회원은 다시 로그인해야 한다")
        void promote() throws Exception {
            Cookie superSession = login(mockMvc, SUPER, PASSWORD);
            Cookie guest = login(mockMvc, MEMBER, PASSWORD);

            changeRole(superSession, memberId, "ADMIN").andExpect(status().isNoContent());

            assertThat(members.findById(memberId).orElseThrow().getRole()).isEqualTo(MemberRole.ADMIN);
            mockMvc.perform(get("/api/auth/me").cookie(guest)).andExpect(status().isUnauthorized());

            Cookie fresh = login(mockMvc, MEMBER, PASSWORD);
            mockMvc.perform(get("/api/admin/members").cookie(fresh)).andExpect(status().isOk());

            mockMvc.perform(get("/api/admin/members/" + memberId).cookie(superSession))
                    .andExpect(jsonPath("$.logs[1].action").value("ROLE_CHANGED"))
                    .andExpect(jsonPath("$.logs[1].detail").value("MEMBER → ADMIN"));
        }

        @Test
        @DisplayName("관리자를 내리면 그 사람의 열린 관리자 세션이 바로 끊긴다")
        void demoteKillsSession() throws Exception {
            Cookie superSession = login(mockMvc, SUPER, PASSWORD);
            Cookie adminSession = login(mockMvc, ADMIN, PASSWORD);

            changeRole(superSession, adminId, "MEMBER").andExpect(status().isNoContent());

            mockMvc.perform(get("/api/admin/members").cookie(adminSession))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("화면으로는 최고 관리자를 만들 수도, 내릴 수도, 자기 권한을 바꿀 수도 없다")
        void superAdminOnlyByCommand() throws Exception {
            Cookie superSession = login(mockMvc, SUPER, PASSWORD);

            changeRole(superSession, memberId, "SUPER_ADMIN").andExpect(status().isConflict());
            changeRole(superSession, superId, "MEMBER").andExpect(status().isConflict());

            UUID another = adminMembers.createOrPromoteByCommand(
                    "super2@example.com", "둘째", MemberRole.SUPER_ADMIN).member().getId();
            changeRole(superSession, another, "MEMBER").andExpect(status().isConflict());
        }
    }

    @Nested
    @DisplayName("관리자 생성 명령")
    class Command {

        @Test
        @DisplayName("새 이메일이면 계정을 만들고, 명령이 준 링크로 본인이 비밀번호를 정한다")
        void createsWithResetLink() throws Exception {
            AdminMemberService.CommandResult result = adminMembers.createOrPromoteByCommand(
                    "ops@example.com", "운영팀", MemberRole.SUPER_ADMIN);
            assertThat(result.created()).isTrue();
            assertThat(result.member().getRole()).isEqualTo(MemberRole.SUPER_ADMIN);

            String link = passwordReset.issueLink(result.member());
            String token = link.substring(link.indexOf("#token=") + "#token=".length());
            passwordReset.reset(token, "chosen by the admin");

            Cookie session = login(mockMvc, "ops@example.com", "chosen by the admin");
            mockMvc.perform(get("/api/admin/members").cookie(session)).andExpect(status().isOk());

            Long logged = jdbc.queryForObject(
                    "SELECT count(*) FROM member_admin_log WHERE member_id = ? AND action = 'CREATED_BY_COMMAND' AND actor_id IS NULL",
                    Long.class, result.member().getId());
            assertThat(logged).isEqualTo(1);
        }

        @Test
        @DisplayName("일반 회원 역할로는 명령을 쓸 수 없다")
        void memberRoleRejected() {
            org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                            adminMembers.createOrPromoteByCommand("x@example.com", "x", MemberRole.MEMBER))
                    .isInstanceOf(AdminMemberService.MemberRuleException.class);
        }
    }
}
