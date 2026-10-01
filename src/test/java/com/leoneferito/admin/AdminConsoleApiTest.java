package com.leoneferito.admin;

import static com.leoneferito.member.MemberTestSupport.csrf;
import static com.leoneferito.member.MemberTestSupport.login;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.leoneferito.TestMailConfiguration;
import com.leoneferito.TestcontainersConfiguration;
import com.leoneferito.auth.AuthService;
import com.leoneferito.member.AdminMemberService;
import com.leoneferito.member.MemberRole;
import com.leoneferito.member.MemberTestSupport;
import jakarta.servlet.http.Cookie;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** 관리자 아이디 로그인 · 임시 비밀번호 · 공지 · FAQ · 대시보드. */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestMailConfiguration.class})
class AdminConsoleApiTest {

    private static final String PASSWORD = "long enough secret 9";
    private static final String TEMP = "temp1234";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private AuthService authService;

    @Autowired
    private AdminMemberService adminMembers;

    private Cookie admin;

    @BeforeEach
    void setUp() throws Exception {
        MemberTestSupport.cleanDatabase(jdbc);
        authService.signup("ops@example.com", PASSWORD, "운영자", null);
        adminMembers.createOrPromoteByCommand("ops@example.com", "운영자", MemberRole.ADMIN);
        authService.signup("buyer@example.com", PASSWORD, "손님", null);
        admin = login(mockMvc, "ops@example.com", PASSWORD);
    }

    @AfterEach
    void cleanUp() {
        MemberTestSupport.cleanDatabase(jdbc);
    }

    private ResultActions send(Cookie who, MockHttpServletRequestBuilder b, String body) throws Exception {
        return mockMvc.perform((who == null ? csrf(mockMvc, b) : csrf(mockMvc, b, who))
                .contentType(MediaType.APPLICATION_JSON).content(body == null ? "{}" : body));
    }

    private ResultActions adminLogin(String id, String password) throws Exception {
        return send(null, post("/api/auth/admin-login"),
                "{\"loginId\":\"%s\",\"password\":\"%s\"}".formatted(id, password));
    }

    @Nested
    @DisplayName("관리자 로그인")
    class AdminLogin {

        @Test
        @DisplayName("아이디 + 임시 비밀번호로 들어오면, 비밀번호를 바꾸기 전에는 관리자 API 가 막힌다")
        void temporaryPasswordGate() throws Exception {
            adminMembers.createWithLoginId("MasterAdmin", null, "마스터", MemberRole.SUPER_ADMIN, TEMP);

            var res = adminLogin("masteradmin", TEMP)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.mustChangePassword").value(true))
                    .andExpect(jsonPath("$.email").value("masteradmin@local.invalid"))
                    .andReturn();
            Cookie master = res.getResponse().getCookie("LFSESSION");

            mockMvc.perform(get("/api/admin/orders").cookie(master))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("PASSWORD_CHANGE_REQUIRED"));
            mockMvc.perform(get("/api/auth/me").cookie(master)).andExpect(jsonPath("$.mustChangePassword").value(true));

            // 새 비밀번호도 규칙을 지켜야 한다
            send(master, post("/api/me/password"),
                    "{\"currentPassword\":\"%s\",\"newPassword\":\"short\"}".formatted(TEMP))
                    .andExpect(status().isBadRequest());
            send(master, post("/api/me/password"),
                    "{\"currentPassword\":\"%s\",\"newPassword\":\"%s\"}".formatted(TEMP, PASSWORD))
                    .andExpect(status().isNoContent());

            // 같은 세션으로 바로 열린다
            mockMvc.perform(get("/api/admin/orders").cookie(master)).andExpect(status().isOk());
            mockMvc.perform(get("/api/auth/me").cookie(master)).andExpect(jsonPath("$.mustChangePassword").value(false));
        }

        @Test
        @DisplayName("이메일로도 들어온다. 관리자가 아니면 비밀번호가 맞아도 같은 실패 답이고 세션이 없다")
        void emailAndNonAdmin() throws Exception {
            adminLogin("ops@example.com", PASSWORD).andExpect(status().isOk())
                    .andExpect(jsonPath("$.roles").value(org.hamcrest.Matchers.hasItem("ROLE_ADMIN")));

            var wrong = adminLogin("ops@example.com", "wrong password 1").andReturn();
            var notAdmin = adminLogin("buyer@example.com", PASSWORD).andReturn();
            assertThat(notAdmin.getResponse().getStatus()).isEqualTo(wrong.getResponse().getStatus());
            assertThat(JsonPath.<String>read(notAdmin.getResponse().getContentAsString(), "$.code"))
                    .isEqualTo(JsonPath.<String>read(wrong.getResponse().getContentAsString(), "$.code"));
            assertThat(notAdmin.getResponse().getCookie("LFSESSION")).isNull();
            assertThat(MemberTestSupport.sessionCount(jdbc, "buyer@example.com")).isZero();
        }

        @Test
        @DisplayName("다섯 번 틀리면 잠긴다 — 아이디 로그인도 같은 잠금 규칙")
        void lockout() throws Exception {
            adminMembers.createWithLoginId("masteradmin", null, "마스터", MemberRole.SUPER_ADMIN, TEMP);
            for (int i = 0; i < 5; i++) {
                adminLogin("masteradmin", "nope nope " + i).andExpect(status().isUnauthorized());
            }
            adminLogin("masteradmin", TEMP).andExpect(jsonPath("$.code").value("ACCOUNT_LOCKED"));
        }
    }

    @Nested
    @DisplayName("공지 · FAQ")
    class Content {

        @Test
        @DisplayName("손님은 공개한 공지만, 고정 먼저 본다. 내린 공지는 404")
        void notices() throws Exception {
            send(admin, post("/api/admin/notices"), "{\"title\":\"배송 안내\",\"body\":\"설 연휴\\n발송 없음\",\"pinned\":false,\"published\":true}")
                    .andExpect(status().isCreated());
            send(admin, post("/api/admin/notices"), "{\"title\":\"중요\",\"body\":\"고정\",\"pinned\":true,\"published\":true}")
                    .andExpect(status().isCreated());
            String draft = JsonPath.read(send(admin, post("/api/admin/notices"),
                    "{\"title\":\"초안\",\"body\":\"아직\",\"pinned\":false,\"published\":false}")
                    .andReturn().getResponse().getContentAsString(), "$.id");

            mockMvc.perform(get("/api/notices"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalElements").value(2))
                    .andExpect(jsonPath("$.items[0].title").value("중요"));
            mockMvc.perform(get("/api/notices/" + draft)).andExpect(status().isNotFound());
            mockMvc.perform(get("/api/admin/notices").cookie(admin)).andExpect(jsonPath("$.length()").value(3));

            send(admin, delete("/api/admin/notices/" + draft), null).andExpect(status().isNoContent());
            Cookie buyer = login(mockMvc, "buyer@example.com", PASSWORD);
            send(buyer, post("/api/admin/notices"), "{\"title\":\"x\",\"body\":\"x\"}").andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("FAQ: 숨긴 질문은 손님에게 안 나간다. 순서 바꾸기는 지금 목록 전체를 보내야 한다")
        void faqs() throws Exception {
            String a = JsonPath.read(send(admin, post("/api/admin/faqs"),
                    "{\"category\":\"SIZE\",\"question\":\"A?\",\"answer\":\"a\",\"published\":true}")
                    .andReturn().getResponse().getContentAsString(), "$.id");
            String b = JsonPath.read(send(admin, post("/api/admin/faqs"),
                    "{\"category\":\"ORDER\",\"question\":\"B?\",\"answer\":\"b\",\"published\":true}")
                    .andReturn().getResponse().getContentAsString(), "$.id");
            send(admin, post("/api/admin/faqs"), "{\"category\":\"ORDER\",\"question\":\"숨김\",\"answer\":\"c\",\"published\":false}")
                    .andExpect(status().isCreated());

            mockMvc.perform(get("/api/faqs")).andExpect(jsonPath("$.length()").value(2))
                    .andExpect(jsonPath("$[0].question").value("A?"));

            send(admin, put("/api/admin/faqs/order"), "{\"ids\":[\"%s\",\"%s\"]}".formatted(b, a))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("STALE_LIST"));

            List<String> all = JsonPath.read(mockMvc.perform(get("/api/admin/faqs").cookie(admin))
                    .andReturn().getResponse().getContentAsString(), "$[*].id");
            send(admin, put("/api/admin/faqs/order"), "{\"ids\":[\"%s\",\"%s\",\"%s\"]}".formatted(all.get(2), b, a))
                    .andExpect(status().isOk());
            mockMvc.perform(get("/api/faqs")).andExpect(jsonPath("$[0].question").value("B?"));
        }
    }

    @Test
    @DisplayName("대시보드: 할 일 칸과 최근 14일이 빈 날까지 채워져 나온다")
    void dashboard() throws Exception {
        mockMvc.perform(get("/api/admin/dashboard").cookie(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.days.length()").value(14))
                .andExpect(jsonPath("$.todo.PAID").value(0))
                .andExpect(jsonPath("$.todo.RETURN_REQUESTED").value(0));
        Cookie buyer = login(mockMvc, "buyer@example.com", PASSWORD);
        mockMvc.perform(get("/api/admin/dashboard").cookie(buyer)).andExpect(status().isForbidden());
    }
}
