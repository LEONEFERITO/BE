package com.leoneferito.auth;

import static com.leoneferito.member.MemberTestSupport.csrf;
import static com.leoneferito.member.MemberTestSupport.login;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.leoneferito.TestMailConfiguration;
import com.leoneferito.TestcontainersConfiguration;
import com.leoneferito.member.AdminMemberService;
import com.leoneferito.member.MemberRole;
import com.leoneferito.member.MemberTestSupport;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

/** 관리자 2단계 인증 (V54) — OTP 등록 · 로그인 · 틀림 · 재사용 · 만료 · 손님 로그인 우회 차단 · 초기화 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestMailConfiguration.class})
@TestPropertySource(properties = "app.admin.two-factor=true")
class AdminOtpApiTest {

    private static final String PASSWORD = "long enough secret 9";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private AuthService authService;
    @Autowired
    private AdminMemberService adminMembers;

    @BeforeEach
    void setUp() {
        MemberTestSupport.cleanDatabase(jdbc);
        authService.signup("ops@example.com", PASSWORD, "운영자", null);
        adminMembers.createOrPromoteByCommand("ops@example.com", "운영자", MemberRole.ADMIN);
        authService.signup("boss@example.com", PASSWORD, "대표", null);
        adminMembers.createOrPromoteByCommand("boss@example.com", "대표", MemberRole.SUPER_ADMIN);
    }

    @AfterEach
    void cleanUp() {
        MemberTestSupport.cleanDatabase(jdbc);
    }

    private ResultActions post(String path, String body, Cookie... cookies) throws Exception {
        return mockMvc.perform(csrf(mockMvc, org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path), cookies)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private MvcResult step1(String id) throws Exception {
        return post("/api/auth/admin-login", "{\"loginId\":\"%s\",\"password\":\"%s\"}".formatted(id, PASSWORD))
                .andExpect(status().isOk()).andReturn();
    }

    private ResultActions step2(String challengeId, String code) throws Exception {
        return post("/api/auth/admin-login/otp", "{\"challengeId\":\"%s\",\"code\":\"%s\"}".formatted(challengeId, code));
    }

    private static String now(String secret) {
        return AuthService.Totp.code(secret, AuthService.Totp.stepAt(Instant.now()));
    }

    /** 처음 로그인 — QR 로 등록하고 들어온다. 비밀 키를 돌려준다 */
    private String enroll(String id) throws Exception {
        String body = step1(id).getResponse().getContentAsString();
        String secret = JsonPath.read(body, "$.secret");
        step2(JsonPath.read(body, "$.challengeId"), now(secret)).andExpect(status().isOk());
        return secret;
    }

    @Test
    @DisplayName("처음 관리자 로그인은 비밀번호 뒤 OTP 등록(QR) — 세션은 6자리를 맞힌 뒤에만, 그때 관리자 API 가 열린다")
    void firstLoginEnrolls() throws Exception {
        MvcResult first = step1("ops@example.com");
        String body = first.getResponse().getContentAsString();
        assertThat(first.getResponse().getCookie("LFSESSION")).isNull();
        assertThat((String) JsonPath.read(body, "$.step")).isEqualTo("OTP_SETUP");
        String secret = JsonPath.read(body, "$.secret");
        assertThat((String) JsonPath.read(body, "$.otpauthUri")).startsWith("otpauth://totp/").contains("secret=" + secret);

        MvcResult ok = step2(JsonPath.read(body, "$.challengeId"), now(secret)).andExpect(status().isOk())
                .andExpect(jsonPath("$.roles[0]").value("ROLE_ADMIN")).andReturn();
        Cookie session = ok.getResponse().getCookie("LFSESSION");
        mockMvc.perform(get("/api/admin/dashboard").cookie(session)).andExpect(status().isOk());

        // 다음부터는 등록 없이 6자리만
        String again = step1("ops@example.com").getResponse().getContentAsString();
        assertThat((String) JsonPath.read(again, "$.step")).isEqualTo("OTP");
        assertThat(again).doesNotContain("secret");
    }

    @Test
    @DisplayName("틀린 6자리는 401 INVALID_OTP, 5번 틀리면 표가 사라져 OTP_EXPIRED — 비밀번호부터 다시")
    void wrongCodes() throws Exception {
        String secret = enroll("ops@example.com");
        String challenge = JsonPath.read(step1("ops@example.com").getResponse().getContentAsString(), "$.challengeId");
        String wrong = now(secret).equals("000000") ? "111111" : "000000";
        for (int i = 0; i < 5; i++) {
            step2(challenge, wrong).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_OTP"));
        }
        step2(challenge, now(secret)).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("OTP_EXPIRED"));
        step2("00000000-0000-0000-0000-000000000000", now(secret)).andExpect(jsonPath("$.code").value("OTP_EXPIRED"));
        step2(challenge, "12ab56").andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("한 번 쓴 6자리는 다시 받지 않는다 — 엿본 번호로 바로 들어오지 못하게")
    void noReplay() throws Exception {
        String secret = enroll("ops@example.com");
        String code = now(secret);
        // 등록 때 쓴 번호(같은 30초 칸)로 다시 로그인
        String challenge = JsonPath.read(step1("ops@example.com").getResponse().getContentAsString(), "$.challengeId");
        step2(challenge, code).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_OTP"));
        // 다음 칸 번호는 받는다(앞뒤 한 칸 허용)
        String next = AuthService.Totp.code(secret, AuthService.Totp.stepAt(Instant.now()) + 1);
        step2(challenge, next).andExpect(status().isOk());
    }

    @Test
    @DisplayName("5분이 지난 표는 받지 않는다")
    void expired() throws Exception {
        String secret = enroll("ops@example.com");
        String challenge = JsonPath.read(step1("ops@example.com").getResponse().getContentAsString(), "$.challengeId");
        jdbc.update("UPDATE admin_login_challenge SET expires_at = now() - interval '1 second'");
        step2(challenge, AuthService.Totp.code(secret, AuthService.Totp.stepAt(Instant.now()) + 1))
                .andExpect(jsonPath("$.code").value("OTP_EXPIRED"));
    }

    @Test
    @DisplayName("관리자 계정이 손님용 로그인으로 들어오면 손님 권한만 — 관리자 API 는 403")
    void customerLoginIsNotAdmin() throws Exception {
        Cookie session = login(mockMvc, "ops@example.com", PASSWORD);
        mockMvc.perform(get("/api/auth/me").cookie(session)).andExpect(jsonPath("$.roles[0]").value("ROLE_MEMBER"));
        mockMvc.perform(get("/api/admin/dashboard").cookie(session)).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("폰 분실 — 최고 관리자가 OTP 를 초기화하면 다음 로그인에서 새로 등록한다. 일반 관리자 · 자기 자신은 못 한다")
    void reset() throws Exception {
        enroll("ops@example.com");
        String bossSecret = enroll("boss@example.com");
        String opsId = jdbc.queryForObject("SELECT id::text FROM member WHERE email = 'ops@example.com'", String.class);
        String bossId = jdbc.queryForObject("SELECT id::text FROM member WHERE email = 'boss@example.com'", String.class);

        String c = JsonPath.read(step1("boss@example.com").getResponse().getContentAsString(), "$.challengeId");
        Cookie boss = step2(c, AuthService.Totp.code(bossSecret, AuthService.Totp.stepAt(Instant.now()) + 1))
                .andExpect(status().isOk()).andReturn().getResponse().getCookie("LFSESSION");
        mockMvc.perform(get("/api/admin/members/" + opsId).cookie(boss)).andExpect(jsonPath("$.otpEnabledAt").isNotEmpty());

        post("/api/admin/members/" + bossId + "/otp-reset", "{}", boss).andExpect(status().isConflict()); // 회원 관리 규칙 위반은 409(MemberRuleException)
        post("/api/admin/members/" + opsId + "/otp-reset", "{}", boss).andExpect(status().isNoContent());
        mockMvc.perform(get("/api/admin/members/" + opsId).cookie(boss)).andExpect(jsonPath("$.otpEnabledAt").doesNotExist());
        String again = step1("ops@example.com").getResponse().getContentAsString();
        assertThat((String) JsonPath.read(again, "$.step")).isEqualTo("OTP_SETUP");
    }

    @Test
    @DisplayName("TOTP 계산은 RFC 6238 시험값과 같다 (SHA1, 59초 → 287082)")
    void rfcVector() {
        String secret = AuthService.Totp.base32("12345678901234567890".getBytes());
        assertThat(AuthService.Totp.code(secret, 59 / 30)).isEqualTo("287082");
        assertThat(AuthService.Totp.code(secret, 1111111109L / 30)).isEqualTo("081804");
    }
}
