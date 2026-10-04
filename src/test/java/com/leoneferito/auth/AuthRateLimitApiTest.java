package com.leoneferito.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.leoneferito.TestcontainersConfiguration;
import com.leoneferito.member.MemberTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 인증 주소의 IP 단위 요청 제한 — 필터가 실제로 끼워져 429 를 내는가.
 *
 * <p>{@link RateLimiterTest} 는 카운터 로직만 본다. 여기서 보는 것은 <b>배선</b>이다: 어느 주소가 어느 구간인지,
 * 실패한 로그인만 세는지, 응답 모양(429 · Retry-After · code)이 프론트가 기대하는 것인지.
 *
 * <p>다른 통합 테스트는 이 필터를 꺼 둔다(src/test/resources/application.properties — 컨텍스트를 공유해서
 * 카운터가 누적되기 때문). 여기만 {@code @TestPropertySource} 로 켜고 한도를 3 으로 낮춘다. 속성이 다르므로
 * 컨텍스트가 따로 뜨고, 카운터도 이 클래스에서 새로 시작한다.
 *
 * <p><b>구간마다 테스트가 하나다.</b> 카운터는 테스트 사이에 초기화되지 않고 MockMvc 는 전부 127.0.0.1 이라,
 * 같은 구간을 두 테스트가 쓰면 둘째는 처음부터 막혀 있다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "app.auth.rate-limit.enabled=true",
        "app.auth.rate-limit.credential-failures=3",
        "app.auth.rate-limit.costly-requests=3",
        "app.auth.rate-limit.window=10m",
})
class AuthRateLimitApiTest {

    private static final String EMAIL = "hong@example.com";
    private static final String PASSWORD = "correct horse battery";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AuthService authService;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ApplicationContext context;

    @BeforeEach
    void reset() {
        MemberTestSupport.cleanDatabase(jdbc);
        // 서비스 직접 호출 — HTTP 를 타지 않으므로 어느 구간에도 세어지지 않는다.
        authService.signup(EMAIL, PASSWORD, "홍길동", null);
    }

    private String loginBody(String email, String password) {
        return """
                {"email":"%s","password":"%s"}
                """.formatted(email, password);
    }

    private MvcResult login(String password) throws Exception {
        return mockMvc.perform(MemberTestSupport.csrf(mockMvc, post("/api/auth/login"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(EMAIL, password)))
                .andReturn();
    }

    @Test
    @DisplayName("이 컨텍스트에는 필터가 있다 (기본 테스트 컨텍스트는 꺼 둔다)")
    void filterIsWired() {
        assertThat(context.getBeansOfType(AuthRateLimitFilter.class)).hasSize(1);
    }

    @Test
    @DisplayName("로그인은 실패만 센다 — 성공 다섯 번은 공짜, 실패 셋 뒤 넷째부터 429")
    void credentialFailuresOnly() throws Exception {
        /*
         * 성공한 로그인은 한도에 쌓이지 않는다. 공유 IP(통신사 · 회사 NAT) 뒤의 정상 사용자들이
         * 서로의 한도를 먹어 치우지 않게 하려는 설계다. 이게 깨지면 아래 "실패 셋" 전에 이미 막힌다.
         */
        for (int i = 0; i < 5; i++) {
            assertThat(login(PASSWORD).getResponse().getStatus()).as("성공 %d번째", i + 1).isEqualTo(200);
        }

        // 한도(3) 안의 실패는 평소대로 401 — 계정 잠금(5회)보다 먼저라 ACCOUNT_LOCKED 가 아니다.
        for (int i = 0; i < 3; i++) {
            MvcResult r = login("wrong-password");
            assertThat(r.getResponse().getStatus()).as("실패 %d번째", i + 1).isEqualTo(401);
        }

        // 넷째 실패 — 토스 쪽이 아니라 우리 필터가 막는다. 핸들러까지 가지 않는다.
        MvcResult blocked = login("wrong-password");
        assertThat(blocked.getResponse().getStatus()).isEqualTo(429);
        assertThat(blocked.getResponse().getContentType()).startsWith(MediaType.APPLICATION_JSON_VALUE);
        assertThat(com.jayway.jsonpath.JsonPath.<String>read(blocked.getResponse().getContentAsString(), "$.code"))
                .isEqualTo("TOO_MANY_REQUESTS");
        String retryAfter = blocked.getResponse().getHeader("Retry-After");
        assertThat(retryAfter).as("Retry-After 헤더").isNotNull();
        // 창이 10분이니 1 ~ 600 초. 0 이면 프론트가 즉시 다시 와서 또 막힌다.
        assertThat(Long.parseLong(retryAfter)).isBetween(1L, 600L);

        /*
         * 막힌 뒤에는 맞는 비밀번호도 막힌다 — 판단은 요청 **전에** 하고, 비밀번호가 맞는지는
         * 그 뒤에야 알 수 있기 때문이다. 이 IP 에서는 창이 지나야 다시 받는다. 다른 IP 의 정상 사용자와는 무관하다.
         */
        assertThat(login(PASSWORD).getResponse().getStatus()).isEqualTo(429);
    }

    @Test
    @DisplayName("가입 · 재설정 메일은 요청 자체를 세고 한 구간을 나눠 쓴다 — 셋 뒤 넷째부터 429")
    void costlyRequestsShareOneBucket() throws Exception {
        /*
         * 없는 이메일로 보낸다. 가입 여부를 숨기려고 서버가 언제나 202 로 답하고(설계), 없는 계정이면
         * 메일도 토큰도 만들지 않으므로 이 테스트가 메일 설정에 기대지 않는다.
         * 그리고 바로 그 "언제나 202" 때문에 이 구간은 실패를 셀 수 없어 전부 센다.
         */
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(MemberTestSupport.csrf(mockMvc, post("/api/auth/password-reset/request"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"nobody-%d@example.com\"}".formatted(i)))
                    .andExpect(status().isAccepted());
        }

        mockMvc.perform(MemberTestSupport.csrf(mockMvc, post("/api/auth/password-reset/request"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"nobody-9@example.com\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.code").value("TOO_MANY_REQUESTS"));

        /*
         * 가입도 같은 구간이다 — 둘 다 "요청 하나가 비용(계정 · 메일)" 이라 하나로 묶었다.
         * 본문을 비워 보내도 429 여야 한다: 필터가 검증(400)보다 먼저 막는다. 필터가 안 끼워져 있으면 400 이 나와 이 단언이 깨진다.
         */
        mockMvc.perform(MemberTestSupport.csrf(mockMvc, post("/api/auth/signup"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isTooManyRequests());
    }
}
