package com.leoneferito.home;

import static com.leoneferito.member.MemberTestSupport.csrf;
import static com.leoneferito.member.MemberTestSupport.login;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.leoneferito.TestMailConfiguration;
import com.leoneferito.TestcontainersConfiguration;
import com.leoneferito.auth.AuthService;
import com.leoneferito.media.ImageFormat;
import com.leoneferito.media.MediaAsset;
import com.leoneferito.media.MediaAssetRepository;
import com.leoneferito.member.AdminMemberService;
import com.leoneferito.member.MemberRole;
import com.leoneferito.member.MemberTestSupport;
import jakarta.servlet.http.Cookie;
import java.util.UUID;
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

/** 메인 WHY 구간 — 손님 읽기 · 관리자 저장 · 항목 수 · 권한. */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestMailConfiguration.class})
class WhyApiTest {

    private static final String PASSWORD = "long enough secret 9";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private AuthService authService;

    @Autowired
    private AdminMemberService adminMembers;

    @Autowired
    private MediaAssetRepository mediaAssets;

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

    private String body(int items, String mediaId) {
        StringBuilder list = new StringBuilder();
        for (int i = 0; i < items; i++) {
            if (i > 0) list.append(',');
            list.append("{\"title\":\"항목 ").append(i + 1).append("\",\"body\":\"설명 ").append(i + 1).append('"');
            if (i == 0 && mediaId != null) list.append(",\"mediaId\":\"").append(mediaId).append('"');
            list.append('}');
        }
        return "{\"eyebrow\":\"WHY\",\"title\":\"치수로 고르세요\",\"intro\":\"소개\",\"items\":[" + list + "]}";
    }

    @Test
    @DisplayName("V18 이 심은 기본 문구가 로그인 없이 읽힌다")
    void seededAndPublic() throws Exception {
        mockMvc.perform(get("/api/why"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("사진이 아니라 치수로 고르세요"))
                .andExpect(jsonPath("$.items.length()").value(3))
                .andExpect(jsonPath("$.items[0].title").value("두 개의 라인"))
                .andExpect(jsonPath("$.items[0].imageUrl").doesNotExist());
    }

    @Test
    @DisplayName("관리자가 통째로 저장하면 그 순서 · 사진 주소로 손님에게 나간다. 2개 미만 · 5개 초과는 거부")
    void saveReplacesAll() throws Exception {
        MediaAsset media = mediaAssets.save(new MediaAsset(UUID.randomUUID(), "why.webp", ImageFormat.WEBP,
                100_000L, 1600, 900, "test/" + UUID.randomUUID() + ".webp"));

        mockMvc.perform(csrf(mockMvc, put("/api/admin/why"), admin)
                        .contentType(MediaType.APPLICATION_JSON).content(body(4, media.getId().toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(4))
                .andExpect(jsonPath("$.items[0].imageUrl").isNotEmpty())
                .andExpect(jsonPath("$.items[1].imageUrl").doesNotExist());

        mockMvc.perform(get("/api/why"))
                .andExpect(jsonPath("$.title").value("치수로 고르세요"))
                .andExpect(jsonPath("$.items[*].title").value(org.hamcrest.Matchers.contains("항목 1", "항목 2", "항목 3", "항목 4")));

        mockMvc.perform(csrf(mockMvc, put("/api/admin/why"), admin)
                        .contentType(MediaType.APPLICATION_JSON).content(body(1, null)))
                .andExpect(status().isBadRequest());
        mockMvc.perform(csrf(mockMvc, put("/api/admin/why"), admin)
                        .contentType(MediaType.APPLICATION_JSON).content(body(6, null)))
                .andExpect(status().isBadRequest());
        // 없는 이미지 id 는 404 — 저장되지 않는다
        mockMvc.perform(csrf(mockMvc, put("/api/admin/why"), admin)
                        .contentType(MediaType.APPLICATION_JSON).content(body(2, UUID.randomUUID().toString())))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/why")).andExpect(jsonPath("$.items.length()").value(4));

        // 되돌려 둔다 — 다른 테스트가 기본 문구를 본다
        mockMvc.perform(csrf(mockMvc, put("/api/admin/why"), admin)
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"eyebrow":"WHY LEONE FERITO","title":"사진이 아니라 치수로 고르세요","intro":"소개",
                                 "items":[{"title":"두 개의 라인","body":"a"},{"title":"상세 실측","body":"b"},{"title":"모델 체형","body":"c"}]}
                                """))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("손님은 저장할 수 없다")
    void memberCannotSave() throws Exception {
        Cookie buyer = login(mockMvc, "buyer@example.com", PASSWORD);
        mockMvc.perform(csrf(mockMvc, put("/api/admin/why"), buyer)
                        .contentType(MediaType.APPLICATION_JSON).content(body(2, null)))
                .andExpect(status().isForbidden());
    }
}
