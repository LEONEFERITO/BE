package com.leoneferito.common.error;

import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import com.leoneferito.common.log.RequestLoggingFilter;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;

/**
 * 모든 예외를 {@link ErrorResponse} 하나의 형식으로 변환한다.
 *
 * <p><b>핵심 규칙 — 필드별 검증 메시지는 관리자 API 에만 내보낸다.</b>
 * 공개 API 가 "priceKrw 는 0 이상이어야 합니다" 처럼 알려주면 필드명·제약조건·내부 구조가
 * 그대로 노출된다. 공격자에게는 스키마 명세서가 된다. 그래서 공개 API 는 뭉뚱그린 한 줄만
 * 주고, 상세 내역은 서버 로그에만 남긴다. 관리자는 신뢰 경계 안이고 입력을 고쳐야 하는
 * 당사자라서 필드별 메시지가 실제로 필요하다.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

	/** 이 접두사로 시작하는 요청만 필드별 검증 메시지를 받는다. */
	static final String ADMIN_PATH_PREFIX = "/api/admin";

	private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

	/** {@code @Valid} 로 묶인 요청 본문이 검증에 실패한 경우. */
	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException e, HttpServletRequest request) {
		String traceId = currentTraceId();
		List<ErrorResponse.FieldError> fieldErrors = e.getBindingResult().getFieldErrors().stream()
				.map(fe -> new ErrorResponse.FieldError(fe.getField(), fe.getDefaultMessage()))
				.toList();

		// 상세 내역은 공개 여부와 무관하게 항상 로그에 남긴다 — 우리가 디버깅할 때 필요하다.
		log.warn("검증 실패 traceId={} path={} fields={}", traceId, request.getRequestURI(), fieldErrors);

		if (isAdminRequest(request)) {
			return ResponseEntity.badRequest()
					.body(ErrorResponse.withFields("VALIDATION_FAILED", "입력값을 확인해 주세요.", traceId, fieldErrors));
		}
		return ResponseEntity.badRequest()
				.body(ErrorResponse.of("VALIDATION_FAILED", "입력값을 확인해 주세요.", traceId));
	}

	/** {@code @Validated} 가 붙은 파라미터·경로변수가 제약을 위반한 경우. */
	@ExceptionHandler(ConstraintViolationException.class)
	public ResponseEntity<ErrorResponse> handleConstraintViolation(ConstraintViolationException e, HttpServletRequest request) {
		String traceId = currentTraceId();
		List<ErrorResponse.FieldError> fieldErrors = e.getConstraintViolations().stream()
				.map(v -> new ErrorResponse.FieldError(v.getPropertyPath().toString(), v.getMessage()))
				.toList();

		log.warn("제약 위반 traceId={} path={} violations={}", traceId, request.getRequestURI(), fieldErrors);

		if (isAdminRequest(request)) {
			return ResponseEntity.badRequest()
					.body(ErrorResponse.withFields("VALIDATION_FAILED", "입력값을 확인해 주세요.", traceId, fieldErrors));
		}
		return ResponseEntity.badRequest()
				.body(ErrorResponse.of("VALIDATION_FAILED", "입력값을 확인해 주세요.", traceId));
	}

	/** JSON 이 깨졌거나 타입이 맞지 않는 경우. 파싱 예외 메시지는 내부 클래스명을 담고 있어 그대로 내보내지 않는다. */
	@ExceptionHandler(HttpMessageNotReadableException.class)
	public ResponseEntity<ErrorResponse> handleUnreadableBody(HttpMessageNotReadableException e, HttpServletRequest request) {
		String traceId = currentTraceId();
		log.warn("요청 본문 해석 실패 traceId={} path={} reason={}", traceId, request.getRequestURI(), e.getMostSpecificCause().getMessage());
		return ResponseEntity.badRequest()
				.body(ErrorResponse.of("MALFORMED_REQUEST", "요청 형식이 올바르지 않습니다.", traceId));
	}

	/** 존재하지 않는 정적 리소스·경로. 404 는 흔하므로 스택트레이스를 남기지 않는다. */
	@ExceptionHandler(NoResourceFoundException.class)
	public ResponseEntity<ErrorResponse> handleNotFound(NoResourceFoundException e, HttpServletRequest request) {
		String traceId = currentTraceId();
		return ResponseEntity.status(HttpStatus.NOT_FOUND)
				.body(ErrorResponse.of("NOT_FOUND", "요청한 리소스를 찾을 수 없습니다.", traceId));
	}

	/**
	 * 위에서 걸리지 않은 모든 예외.
	 *
	 * <p>클라이언트에는 예외 메시지를 절대 내보내지 않는다 (SQL 문, 파일 경로, 라이브러리 버전이
	 * 메시지에 섞여 나오는 일이 흔하다). 대신 {@code traceId} 를 주고, 서버 로그에서 그 id 로
	 * 전체 스택트레이스를 찾는다.
	 */
	@ExceptionHandler(Exception.class)
	public ResponseEntity<ErrorResponse> handleUnexpected(Exception e, HttpServletRequest request) {
		String traceId = currentTraceId();
		log.error("처리되지 않은 예외 traceId={} path={}", traceId, request.getRequestURI(), e);
		return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
				.body(ErrorResponse.of("INTERNAL_ERROR", "일시적인 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.", traceId));
	}

	private boolean isAdminRequest(HttpServletRequest request) {
		return request.getRequestURI().startsWith(ADMIN_PATH_PREFIX);
	}

	/**
	 * traceId 는 {@link RequestLoggingFilter} 가 요청 진입 시 MDC 에 넣어둔 값을 그대로 쓴다.
	 * 그래야 요청 로그 한 줄과 에러 응답이 같은 id 를 가리킨다.
	 *
	 * <p>필터를 타지 않는 경로(헬스체크)나 테스트에서는 MDC 가 비어 있으므로 그때만 새로 만든다.
	 */
	private String currentTraceId() {
		String fromMdc = MDC.get(RequestLoggingFilter.TRACE_ID);
		return fromMdc != null ? fromMdc : UUID.randomUUID().toString().substring(0, 8);
	}
}
