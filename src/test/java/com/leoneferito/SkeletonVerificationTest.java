package com.leoneferito;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

/**
 * Phase 1(뼈대)이 실제로 동작하는지 검증한다.
 *
 * <p>여기서 확인하는 것:
 * <ol>
 *   <li>애플리케이션이 뜨고 헬스체크가 200 / UP 을 반환한다</li>
 *   <li>Flyway V1 이 실제 PostgreSQL 에 적용됐다</li>
 *   <li>V1 이 만든 공용 트리거 함수가 DB 에 존재한다</li>
 *   <li><b>필드별 검증 메시지가 관리자 API 에만 나간다</b> — 공개 API 는 내부 필드명을 숨긴다</li>
 * </ol>
 *
 * <p>DB 는 Testcontainers 가 띄운 실제 PostgreSQL 18 이다. H2 같은 대체 DB 를 쓰지 않는다 —
 * 방언이 달라서 "테스트는 통과하는데 운영에서 깨지는" 문제가 생긴다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({ TestcontainersConfiguration.class, SkeletonVerificationTest.ValidationProbeController.class })
class SkeletonVerificationTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Test
	@DisplayName("헬스체크가 200 과 UP 을 반환한다")
	void healthEndpointIsUp() throws Exception {
		mockMvc.perform(get("/actuator/health"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("UP"));
	}

	@Test
	@DisplayName("Flyway V1 이 성공적으로 적용되어 있다")
	void flywayV1IsApplied() throws Exception {
		Boolean success = jdbcTemplate.queryForObject(
				"SELECT success FROM flyway_schema_history WHERE version = '1'", Boolean.class);

		assertThat(success)
				.as("V1__baseline.sql 이 적용되지 않았다면 마이그레이션 파이프라인이 동작하지 않는 것이다")
				.isTrue();
	}

	@Test
	@DisplayName("V1 이 만든 공용 updated_at 트리거 함수가 DB 에 존재한다")
	void sharedUpdatedAtTriggerFunctionExists() throws Exception {
		Integer count = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM pg_proc WHERE proname = 'set_updated_at'", Integer.class);

		assertThat(count)
				.as("Phase 2 의 모든 테이블이 이 함수에 트리거를 붙인다")
				.isEqualTo(1);
	}

	@Test
	@DisplayName("공개 API 의 검증 실패는 필드명을 노출하지 않는다")
	void publicApiHidesFieldErrors() throws Exception {
		mockMvc.perform(post("/api/public/__probe/validate")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
				// 이게 핵심이다. fieldErrors 가 나가면 내부 필드명·제약조건이 공개된다.
				.andExpect(jsonPath("$.fieldErrors").doesNotExist())
				// traceId 는 있어야 한다 — CS 문의 때 로그를 찾는 유일한 단서다.
				.andExpect(jsonPath("$.traceId").exists());
	}

	@Test
	@DisplayName("관리자 API 의 검증 실패는 필드별 메시지를 내려준다")
	void adminApiExposesFieldErrors() throws Exception {
		mockMvc.perform(post("/api/admin/__probe/validate")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
				// 운영자는 어느 칸이 잘못됐는지 알아야 고칠 수 있다.
				.andExpect(jsonPath("$.fieldErrors[0].field").value("name"));
	}

	/**
	 * 검증 분기를 확인하기 위한 테스트 전용 컨트롤러. 운영 코드에는 들어가지 않는다.
	 *
	 * <p>공개/관리자 경로에 같은 요청을 보내 응답 차이를 확인하는 것이 목적이다.
	 */
	@RestController
	static class ValidationProbeController {

		record Body(@NotBlank String name) {
		}

		@PostMapping("/api/public/__probe/validate")
		void publicEndpoint(@Valid @RequestBody Body body) {
		}

		@PostMapping("/api/admin/__probe/validate")
		void adminEndpoint(@Valid @RequestBody Body body) {
		}
	}
}
