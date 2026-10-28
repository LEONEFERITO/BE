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

/** 푸터 사업자 정보 — 심은 값이 로그인 없이 읽히고, 관리자만 고치며, 형식이 틀리면 400. */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestMailConfiguration.class})
class SiteBusinessApiTest {

    private static final String PASSWORD = "long enough secret 9";
    private static final String SEEDED = """
            {"companyName":"레오네페리토 (LEONEFERITO)","representative":"조강현","registrationNumber":"881-15-02245",
             "mailOrderNumber":"2024-수원영통-0059","address":"수원시 영통구 매영로 425번길 1 1층 더맨리",
             "phone":"010-2434-7794 · 010-9291-0518","email":"sport0308@naver.com","privacyOfficer":"조강현 (대표)",
             "hosting":"Amazon Web Services (서울)"}
            """;
    private static final String SEEDED_GUIDE = """
            {"leadTimes":[{"items":"자켓 / 팬츠 / 베스트","time":"14~18일"},{"items":"셔츠 / 구두 / 로퍼","time":"7~10일"},
             {"items":"넥타이","time":"바로 출고"}]}
            """;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private AuthService authService;

    @Autowired
    private AdminMemberService adminMembers;

    private Cookie admin;
    private Cookie buyer;

    @BeforeEach
    void setUp() throws Exception {
        MemberTestSupport.cleanDatabase(jdbc);
        authService.signup("ops@example.com", PASSWORD, "운영자", null);
        adminMembers.createOrPromoteByCommand("ops@example.com", "운영자", MemberRole.ADMIN);
        authService.signup("buyer@example.com", PASSWORD, "손님", null);
        admin = login(mockMvc, "ops@example.com", PASSWORD);
        buyer = login(mockMvc, "buyer@example.com", PASSWORD);
    }

    @AfterEach
    void cleanUp() throws Exception {
        // 다른 테스트가 심은 값을 기대하므로 되돌린다
        mockMvc.perform(csrf(mockMvc, put("/api/admin/site-business"), admin).contentType(MediaType.APPLICATION_JSON).content(SEEDED));
        mockMvc.perform(csrf(mockMvc, put("/api/admin/site-guide"), admin).contentType(MediaType.APPLICATION_JSON).content(SEEDED_GUIDE));
        MemberTestSupport.cleanDatabase(jdbc);
    }

    @Test
    @DisplayName("V31 이 심은 값이 로그인 없이 읽힌다")
    void seededAndPublic() throws Exception {
        mockMvc.perform(get("/api/site-business"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.companyName").value("레오네페리토 (LEONEFERITO)"))
                .andExpect(jsonPath("$.registrationNumber").value("881-15-02245"))
                .andExpect(jsonPath("$.mailOrderNumber").value("2024-수원영통-0059"))
                .andExpect(jsonPath("$.hosting").value("Amazon Web Services (서울)"));
    }

    @Test
    @DisplayName("관리자가 고치면 바로 읽힌다. 손님은 못 고친다. 사업자등록번호 · 이메일 형식이 틀리면 400")
    void adminSaves() throws Exception {
        String changed = SEEDED.replace("수원시 영통구", "경기도 수원시 영통구").replace("010-2434-7794 · 010-9291-0518", "010-2434-7794");
        mockMvc.perform(csrf(mockMvc, put("/api/admin/site-business"), buyer).contentType(MediaType.APPLICATION_JSON).content(changed))
                .andExpect(status().isForbidden());
        mockMvc.perform(csrf(mockMvc, put("/api/admin/site-business"), admin).contentType(MediaType.APPLICATION_JSON).content(changed))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.address").value("경기도 수원시 영통구 매영로 425번길 1 1층 더맨리"));
        mockMvc.perform(get("/api/site-business"))
                .andExpect(jsonPath("$.phone").value("010-2434-7794"));

        mockMvc.perform(csrf(mockMvc, put("/api/admin/site-business"), admin).contentType(MediaType.APPLICATION_JSON)
                        .content(SEEDED.replace("881-15-02245", "8811502245")))
                .andExpect(status().isBadRequest());
        mockMvc.perform(csrf(mockMvc, put("/api/admin/site-business"), admin).contentType(MediaType.APPLICATION_JSON)
                        .content(SEEDED.replace("sport0308@naver.com", "not-an-email")))
                .andExpect(status().isBadRequest());
        mockMvc.perform(csrf(mockMvc, put("/api/admin/site-business"), admin).contentType(MediaType.APPLICATION_JSON)
                        .content(SEEDED.replace("\"조강현\"", "\"\"")))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("가이드 제작 기간 — V42 가 심은 표가 로그인 없이 읽히고, 관리자만 고치며, 빈 표 · 줄바꿈 · 빈 기간은 400")
    void guideLeadTimes() throws Exception {
        mockMvc.perform(get("/api/site-guide"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.leadTimes.length()").value(3))
                .andExpect(jsonPath("$.leadTimes[0].items").value("자켓 / 팬츠 / 베스트"))
                .andExpect(jsonPath("$.leadTimes[0].time").value("14~18일"))
                .andExpect(jsonPath("$.leadTimes[2].time").value("바로 출고"));

        String changed = """
                {"leadTimes":[{"items":" 자켓 ","time":" 20~25일 "},{"items":"넥타이","time":"바로 출고"}]}
                """;
        mockMvc.perform(csrf(mockMvc, put("/api/admin/site-guide"), buyer).contentType(MediaType.APPLICATION_JSON).content(changed))
                .andExpect(status().isForbidden());
        mockMvc.perform(csrf(mockMvc, put("/api/admin/site-guide"), admin).contentType(MediaType.APPLICATION_JSON).content(changed))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.leadTimes.length()").value(2));
        mockMvc.perform(get("/api/site-guide"))
                .andExpect(jsonPath("$.leadTimes[0].items").value("자켓"))
                .andExpect(jsonPath("$.leadTimes[0].time").value("20~25일"));
        // 같은 줄(site_business)의 사업자 정보는 그대로
        mockMvc.perform(get("/api/site-business"))
                .andExpect(jsonPath("$.registrationNumber").value("881-15-02245"));

        mockMvc.perform(csrf(mockMvc, put("/api/admin/site-guide"), admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"leadTimes\":[]}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(csrf(mockMvc, put("/api/admin/site-guide"), admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"leadTimes\":[{\"items\":\"자켓\\n셔츠\",\"time\":\"7일\"}]}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(csrf(mockMvc, put("/api/admin/site-guide"), admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"leadTimes\":[{\"items\":\"자켓\",\"time\":\"\"}]}"))
                .andExpect(status().isBadRequest());
    }
}
