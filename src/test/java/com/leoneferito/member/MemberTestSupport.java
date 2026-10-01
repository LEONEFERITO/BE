package com.leoneferito.member;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * 회원 테스트 공용 — 진짜 로그인(세션 쿠키)과 CSRF.
 *
 * <p>{@code with(user(...))} 로 가짜 인증을 쓰지 않는다. 여기 테스트들은 "세션이 서버에서 끊겼는가" 를
 * 보기 때문에, 세션이 실제로 DB(SPRING_SESSION)에 있어야 한다.
 */
public final class MemberTestSupport {

    private MemberTestSupport() {
    }

    /** 테스트 사이 정리. FK 순서대로 지운다. */
    public static void cleanDatabase(JdbcTemplate jdbc) {
        jdbc.update("DELETE FROM spring_session");
        // 주문·장바구니가 회원을 참조한다 (V13). 주문은 운영에서는 지우지 않지만 테스트는 매번 비운다.
        jdbc.update("DELETE FROM return_photo");
        jdbc.update("DELETE FROM return_event");
        jdbc.update("DELETE FROM return_request_item");
        jdbc.update("DELETE FROM return_request");
        jdbc.update("DELETE FROM order_event");
        jdbc.update("DELETE FROM order_item");
        jdbc.update("DELETE FROM orders");
        jdbc.update("DELETE FROM cart_item");
        jdbc.update("DELETE FROM notice");
        jdbc.update("DELETE FROM faq");
        jdbc.update("DELETE FROM member_admin_log");
        jdbc.update("DELETE FROM password_reset_token");
        jdbc.update("DELETE FROM member");
    }

    /** 로그인해서 세션 쿠키를 돌려받는다. 브라우저 하나 = 쿠키 하나. */
    public static Cookie login(MockMvc mockMvc, String email, String password) throws Exception {
        MvcResult result = mockMvc.perform(csrf(mockMvc, post("/api/auth/login"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password)))
                .andExpect(status().isOk())
                .andReturn();
        Cookie session = result.getResponse().getCookie("LFSESSION");
        if (session == null) {
            throw new AssertionError("no session cookie: " + result.getResponse().getHeaders("Set-Cookie"));
        }
        return session;
    }

    /** CSRF 토큰을 받아 요청에 붙인다. 세션 쿠키가 있으면 같이 싣는다. */
    public static MockHttpServletRequestBuilder csrf(MockMvc mockMvc, MockHttpServletRequestBuilder builder,
                                              Cookie... cookies) throws Exception {
        MvcResult tokenResult = mockMvc.perform(get("/api/auth/csrf")).andReturn();
        String token = com.jayway.jsonpath.JsonPath.read(
                tokenResult.getResponse().getContentAsString(), "$.token");
        Cookie csrfCookie = tokenResult.getResponse().getCookie("XSRF-TOKEN");
        List<Cookie> all = new ArrayList<>(List.of(cookies));
        all.add(csrfCookie == null ? new Cookie("XSRF-TOKEN", token) : csrfCookie);
        return builder.header("X-XSRF-TOKEN", token).cookie(all.toArray(Cookie[]::new));
    }

    public static long sessionCount(JdbcTemplate jdbc, String email) {
        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM spring_session WHERE principal_name = ?", Long.class, email);
        return count == null ? 0 : count;
    }
}
