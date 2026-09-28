package com.leoneferito.common.error;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 모든 에러 응답의 단일 형식.
 *
 * <p>클라이언트가 에러를 다루는 방법이 하나여야 한다. 어떤 예외가 났든 이 모양으로 나간다.
 *
 * <p>{@code fieldErrors} 는 {@code null} 이면 JSON 에서 아예 빠진다
 * ({@link JsonInclude.Include#NON_NULL}). 공개 API 에서는 항상 빠지고,
 * 관리자 API 에서만 채워진다 — 이유는 {@link GlobalExceptionHandler} 참고.
 *
 * @param code      기계가 분기할 값 (예: {@code VALIDATION_FAILED})
 * @param message   사람이 읽을 한 줄. 내부 구현을 드러내지 않는다
 * @param traceId   서버 로그와 대조할 식별자. CS 문의 때 이 값으로 찾는다
 * @param fieldErrors 필드별 검증 실패 내역. 관리자 API 에만 채운다
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(
		String code,
		String message,
		String traceId,
		Instant timestamp,
		List<FieldError> fieldErrors) {

	public record FieldError(String field, String message) {
	}

	public static ErrorResponse of(String code, String message, String traceId) {
		return new ErrorResponse(code, message, traceId, Instant.now(), null);
	}

	public static ErrorResponse withFields(String code, String message, String traceId, List<FieldError> fieldErrors) {
		return new ErrorResponse(code, message, traceId, Instant.now(), fieldErrors);
	}
}
