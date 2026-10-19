package com.leoneferito.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.leoneferito.TestcontainersConfiguration;
import com.leoneferito.member.MemberProvider;
import com.leoneferito.member.MemberRepository;
import com.leoneferito.member.MemberSocialLink;
import com.leoneferito.member.MemberSocialLinkRepository;
import com.leoneferito.member.MemberTestSupport;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
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

/**
 * 아이디 찾기 — 이름 · 휴대폰 번호로 가린 이메일을 찾는다. 지키려는 것: 가린 모양, 관리자 · 정지 회원은 안 나옴,
 * 번호의 하이픈 · 이름의 대소문자 · 앞뒤 공백은 상관없음, 없으면 빈 목록, 로그인 없이 된다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class FindEmailApiTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private AuthService auth;
    @Autowired
    private MemberRepository members;
    @Autowired
    private MemberSocialLinkRepository links;

    @BeforeEach
    void setUp() {
        MemberTestSupport.cleanDatabase(jdbc);
    }

    private ResultActions find(String name, String phone) throws Exception {
        return mockMvc.perform(MemberTestSupport.csrf(mockMvc, post("/api/auth/find-email"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"%s\",\"phone\":\"%s\"}".formatted(name, phone)));
    }

    @Test
    @DisplayName("이름 · 번호가 같으면 가린 이메일 · 가입 방법 · 가입일 — 하이픈 · 대소문자 · 공백은 상관없다")
    void findsMasked() throws Exception {
        UUID id = auth.signup("hong.gildong@naver.com", "correct horse battery", "Hong Gildong", "010-1234-5678");
        links.save(new MemberSocialLink(id, MemberProvider.KAKAO, "kakao-1", Instant.now()));

        find("  hong gildong ", "01012345678")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accounts.length()").value(1))
                .andExpect(jsonPath("$.accounts[0].maskedEmail").value("ho**********@naver.com"))
                .andExpect(jsonPath("$.accounts[0].providers[0]").value("LOCAL"))
                .andExpect(jsonPath("$.accounts[0].providers[1]").value("KAKAO"))
                .andExpect(jsonPath("$.accounts[0].joinedOn").value(LocalDate.now(ZoneId.of("Asia/Seoul")).toString()));

        find("Hong Gildong", "010-1234-5678").andExpect(jsonPath("$.accounts.length()").value(1));
    }

    @Test
    @DisplayName("없으면 빈 목록(200) — 이름이 다르거나 번호가 다르면 안 나온다")
    void noMatch() throws Exception {
        auth.signup("kim@example.com", "correct horse battery", "김철수", "010-2222-3333");

        find("김철수", "010-2222-3334").andExpect(status().isOk()).andExpect(jsonPath("$.accounts.length()").value(0));
        find("김철", "010-2222-3333").andExpect(jsonPath("$.accounts.length()").value(0));
        find("김철수", "010-2222-3333").andExpect(jsonPath("$.accounts[0].maskedEmail").value("ki*@example.com"));
    }

    @Test
    @DisplayName("관리자 · 정지 회원은 이 길로 드러나지 않는다")
    void adminAndSuspendedHidden() throws Exception {
        auth.signup("boss@example.com", "correct horse battery", "이대표", "010-9999-0000");
        auth.signup("bad@example.com", "correct horse battery", "이대표", "010-9999-0000");
        jdbc.update("UPDATE member SET role = 'ADMIN' WHERE email = 'boss@example.com'");
        jdbc.update("UPDATE member SET status = 'SUSPENDED' WHERE email = 'bad@example.com'");

        find("이대표", "01099990000").andExpect(jsonPath("$.accounts.length()").value(0));
    }

    @Test
    @DisplayName("번호 형식이 틀리면 400 — 로그인 없이 부를 수 있다(401 이 아니다)")
    void validation() throws Exception {
        find("홍길동", "phone").andExpect(status().isBadRequest());
        find("", "010-1234-5678").andExpect(status().isBadRequest());
        assertThat(members.count()).isZero();
    }

    @Test
    @DisplayName("가리는 규칙 — 앞 두 글자(짧으면 한 글자)만, 도메인은 그대로")
    void maskRule() {
        assertThat(FindEmailService.mask("hong@gmail.com")).isEqualTo("ho**@gmail.com");
        assertThat(FindEmailService.mask("ab@x.kr")).isEqualTo("a*@x.kr");
        assertThat(FindEmailService.mask("a@x.kr")).isEqualTo("a@x.kr");
    }
}
