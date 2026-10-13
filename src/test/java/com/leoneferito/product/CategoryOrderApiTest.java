package com.leoneferito.product;

import static com.leoneferito.member.MemberTestSupport.csrf;
import static com.leoneferito.member.MemberTestSupport.login;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.leoneferito.TestMailConfiguration;
import com.leoneferito.TestcontainersConfiguration;
import com.leoneferito.auth.AuthService;
import com.leoneferito.member.AdminMemberService;
import com.leoneferito.member.MemberRole;
import com.leoneferito.member.MemberTestSupport;
import jakarta.servlet.http.Cookie;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** 분류 메뉴 순서 (V27) — 손님 읽기 · 관리자 바꾸기 · 빠짐/겹침 거절 · 권한. */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestMailConfiguration.class})
class CategoryOrderApiTest {

    private static final String PASSWORD = "long enough secret 9";
    private static final String ADMIN_URL = "/api/admin/products/category-order";

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
        // 마이그레이션이 심은 처음 순서로 돌려 둔다 — 다른 테스트가 기본 순서를 본다
        String[] seed = {"SHIRT", "TROUSERS", "JACKET", "SUIT", "SHOES", "ACCESSORIES"};
        for (int i = 0; i < seed.length; i++) {
            jdbc.update("UPDATE category_order SET sort_order = ? WHERE category = ?", (i + 1) * 10, seed[i]);
        }
        MemberTestSupport.cleanDatabase(jdbc);
    }

    private ResultActions save(Cookie who, String categories) throws Exception {
        return mockMvc.perform(csrf(mockMvc, put(ADMIN_URL), who)
                .contentType(MediaType.APPLICATION_JSON).content("{\"categories\":[" + categories + "]}"));
    }

    @Test
    @DisplayName("처음 순서는 고객 구조표 순서이고 로그인 없이 읽힌다")
    void seededAndPublic() throws Exception {
        mockMvc.perform(get("/api/products/category-order"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.categories.length()").value(6))
                .andExpect(jsonPath("$.categories[0]").value("SHIRT"))
                .andExpect(jsonPath("$.categories[3]").value("SUIT"))
                .andExpect(jsonPath("$.categories[5]").value("ACCESSORIES"));
    }

    @Test
    @DisplayName("관리자가 바꾸면 손님 순서도 바뀐다")
    void adminReorders() throws Exception {
        save(admin, "\"SUIT\",\"JACKET\",\"SHIRT\",\"TROUSERS\",\"SHOES\",\"ACCESSORIES\"")
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/products/category-order"))
                .andExpect(jsonPath("$.categories[0]").value("SUIT"))
                .andExpect(jsonPath("$.categories[1]").value("JACKET"))
                .andExpect(jsonPath("$.categories[2]").value("SHIRT"));
        mockMvc.perform(get(ADMIN_URL).cookie(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.categories[0]").value("SUIT"));
    }

    @Test
    @DisplayName("분류가 빠지거나 겹치면 409, 모르는 분류는 400 — 순서는 그대로")
    void rejectsIncompleteList() throws Exception {
        save(admin, "\"SUIT\",\"JACKET\"").andExpect(status().isConflict());
        save(admin, "\"SUIT\",\"SUIT\",\"SHIRT\",\"TROUSERS\",\"SHOES\",\"ACCESSORIES\"").andExpect(status().isConflict());
        save(admin, "\"SUIT\",\"HAT\",\"SHIRT\",\"TROUSERS\",\"SHOES\",\"ACCESSORIES\"").andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/products/category-order"))
                .andExpect(jsonPath("$.categories[0]").value("SHIRT"));
    }

    @Test
    @DisplayName("손님은 바꿀 수 없다")
    void memberCannotReorder() throws Exception {
        Cookie buyer = login(mockMvc, "buyer@example.com", PASSWORD);
        save(buyer, "\"SUIT\",\"JACKET\",\"SHIRT\",\"TROUSERS\",\"SHOES\",\"ACCESSORIES\"")
                .andExpect(status().isForbidden());
    }
}
