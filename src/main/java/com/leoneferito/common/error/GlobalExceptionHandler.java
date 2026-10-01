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
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
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

	/**
	 * 쿼리 파라미터·경로변수의 타입이 맞지 않는 경우 (예: {@code ?category=KNITWEAR}).
	 *
	 * <p>이게 없으면 500 이 나간다. 클라이언트가 잘못 보낸 요청에 서버 오류로 답하면
	 * 프론트는 자기 잘못인 줄 모르고 재시도하거나 장애로 신고한다. 400 이어야 한다.
	 *
	 * <p>응답에 <b>받은 값을 되비추지 않는다.</b> 그대로 돌려주면 그 값이 에러 화면이나
	 * 로그 수집기에 그대로 실려 반사형 XSS·로그 오염의 통로가 된다. 값은 서버 로그에만 남긴다.
	 */
	@ExceptionHandler(MethodArgumentTypeMismatchException.class)
	public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException e, HttpServletRequest request) {
		String traceId = currentTraceId();
		log.warn("파라미터 타입 불일치 traceId={} path={} name={} value={}",
				traceId, request.getRequestURI(), e.getName(), e.getValue());
		return ResponseEntity.badRequest()
				.body(ErrorResponse.of("INVALID_PARAMETER", "요청 값이 올바르지 않습니다.", traceId));
	}

	/** 존재하지 않는 정적 리소스·경로. 404 는 흔하므로 스택트레이스를 남기지 않는다. */
	@ExceptionHandler(NoResourceFoundException.class)
	public ResponseEntity<ErrorResponse> handleNotFound(NoResourceFoundException e, HttpServletRequest request) {
		String traceId = currentTraceId();
		return ResponseEntity.status(HttpStatus.NOT_FOUND)
				.body(ErrorResponse.of("NOT_FOUND", "요청한 리소스를 찾을 수 없습니다.", traceId));
	}

	/**
	 * 도메인이 "없다" 고 판단한 경우.
	 *
	 * <p>비공개 상품을 403 으로 돌려주면 그 slug 가 존재한다는 사실이 샌다.
	 * 없는 것과 볼 수 없는 것을 같은 답으로 덮는다 ({@link ResourceNotFoundException} 참고).
	 * 예외 메시지는 로그에만 남기고 응답에는 넣지 않는다.
	 */
	@ExceptionHandler(ResourceNotFoundException.class)
	public ResponseEntity<ErrorResponse> handleResourceNotFound(ResourceNotFoundException e, HttpServletRequest request) {
		String traceId = currentTraceId();
		log.info("리소스 없음 traceId={} path={} reason={}", traceId, request.getRequestURI(), e.getMessage());
		return ResponseEntity.status(HttpStatus.NOT_FOUND)
				.body(ErrorResponse.of("NOT_FOUND", "요청한 리소스를 찾을 수 없습니다.", traceId));
	}

	/**
	 * 로그인 실패. 401 이다.
	 *
	 * <p>이유를 응답 코드로 구분하되, {@code INVALID_CREDENTIALS} 하나가
	 * "없는 이메일 · 틀린 비밀번호 · 탈퇴한 계정" 을 모두 덮는다
	 * ({@link com.leoneferito.auth.AuthenticationFailedException} 참고).
	 */
	@ExceptionHandler(com.leoneferito.auth.AuthenticationFailedException.class)
	public ResponseEntity<ErrorResponse> handleAuthFailed(com.leoneferito.auth.AuthenticationFailedException e) {
		String traceId = currentTraceId();
		String message = switch (e.getReason()) {
			case INVALID_CREDENTIALS -> "이메일 또는 비밀번호가 올바르지 않습니다.";
			case ACCOUNT_LOCKED -> "로그인 시도가 많아 계정이 잠겼습니다. 잠시 후 다시 시도해 주세요.";
			case ACCOUNT_SUSPENDED -> "이용이 정지된 계정입니다. 고객센터로 문의해 주세요.";
		};
		return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
				.body(ErrorResponse.of(e.getReason().name(), message, traceId));
	}

	/**
	 * 이미 가입된 이메일. 409 다.
	 *
	 * <p>예외 메시지에는 이메일이 들어 있지만 응답에는 넣지 않는다 —
	 * 입력값을 그대로 되비추면 에러 화면에 그려지는 경로가 생긴다.
	 */
	@ExceptionHandler(com.leoneferito.auth.EmailAlreadyRegisteredException.class)
	public ResponseEntity<ErrorResponse> handleDuplicateEmail(com.leoneferito.auth.EmailAlreadyRegisteredException e, HttpServletRequest request) {
		String traceId = currentTraceId();
		log.info("가입 중복 traceId={} reason={}", traceId, e.getMessage());
		return ResponseEntity.status(HttpStatus.CONFLICT)
				.body(ErrorResponse.of("EMAIL_ALREADY_REGISTERED", "이미 가입된 이메일입니다.", traceId));
	}

	/**
	 * 비밀번호 규칙 위반. 400 이다.
	 *
	 * <p>이 예외만은 <b>메시지를 그대로 내보낸다.</b> 무엇을 고쳐야 하는지 알려주지 않으면
	 * 손님이 같은 실패를 반복한다. 비밀번호 규칙은 어차피 공개된 정보라 숨겨서 얻는 게 없다.
	 */
	@ExceptionHandler(com.leoneferito.auth.WeakPasswordException.class)
	public ResponseEntity<ErrorResponse> handleWeakPassword(com.leoneferito.auth.WeakPasswordException e) {
		return ResponseEntity.badRequest()
				.body(ErrorResponse.of("WEAK_PASSWORD", e.getMessage(), currentTraceId()));
	}

	/**
	 * 비밀번호 재설정 링크가 없거나 · 만료됐거나 · 이미 쓰였다. 400 이다.
	 *
	 * <p>셋을 구분하지 않는다. 어느 쪽이든 손님이 할 일은 같다 — 링크를 다시 받는다.
	 */
	@ExceptionHandler(com.leoneferito.auth.PasswordResetService.InvalidTokenException.class)
	public ResponseEntity<ErrorResponse> handleInvalidResetToken(com.leoneferito.auth.PasswordResetService.InvalidTokenException e) {
		return ResponseEntity.badRequest()
				.body(ErrorResponse.of("INVALID_RESET_TOKEN",
						"링크가 만료되었거나 이미 사용되었습니다. 비밀번호 찾기를 다시 해 주세요.", currentTraceId()));
	}

	/**
	 * 마이페이지에서 현재 비밀번호가 틀렸다. 400 이다 (401 이 아니다).
	 *
	 * <p>401 을 주면 프론트가 "로그인이 풀렸다" 로 읽고 로그인 화면으로 보낸다.
	 * 로그인은 멀쩡하고 입력만 틀린 것이다.
	 */
	@ExceptionHandler(com.leoneferito.member.MemberAccountService.WrongPasswordException.class)
	public ResponseEntity<ErrorResponse> handleWrongPassword(com.leoneferito.member.MemberAccountService.WrongPasswordException e) {
		String message = e.isLocked()
				? "시도가 많아 잠시 잠겼습니다. 15분 뒤에 다시 시도해 주세요."
				: "현재 비밀번호가 올바르지 않습니다.";
		return ResponseEntity.badRequest()
				.body(ErrorResponse.of(e.isLocked() ? "ACCOUNT_LOCKED" : "WRONG_PASSWORD", message, currentTraceId()));
	}

	/** 간편가입 계정은 비밀번호가 없어 바꿀 수 없다. 409 다. */
	@ExceptionHandler(com.leoneferito.member.MemberAccountService.NoPasswordException.class)
	public ResponseEntity<ErrorResponse> handleNoPassword(com.leoneferito.member.MemberAccountService.NoPasswordException e) {
		return ResponseEntity.status(HttpStatus.CONFLICT)
				.body(ErrorResponse.of("NO_PASSWORD",
						"간편가입 계정은 비밀번호가 없습니다. 카카오·네이버로 로그인해 주세요.", currentTraceId()));
	}

	/** 관리자는 스스로 탈퇴할 수 없다. 409 다. */
	@ExceptionHandler(com.leoneferito.member.MemberAccountService.AdminWithdrawalException.class)
	public ResponseEntity<ErrorResponse> handleAdminWithdrawal(com.leoneferito.member.MemberAccountService.AdminWithdrawalException e) {
		return ResponseEntity.status(HttpStatus.CONFLICT)
				.body(ErrorResponse.of("ADMIN_CANNOT_WITHDRAW",
						"관리자 계정은 탈퇴할 수 없습니다. 먼저 관리자 권한을 해제해 주세요.", currentTraceId()));
	}

	/**
	 * 회원 관리 규칙 위반 (자기 자신 정지, 최고 관리자 강등 등). 409 다.
	 *
	 * <p>메시지를 그대로 내보낸다 — 관리자 화면에서만 나오고, 무엇이 막혔는지 알아야 한다.
	 */
	@ExceptionHandler(com.leoneferito.member.AdminMemberService.MemberRuleException.class)
	public ResponseEntity<ErrorResponse> handleMemberRule(com.leoneferito.member.AdminMemberService.MemberRuleException e) {
		return ResponseEntity.status(HttpStatus.CONFLICT)
				.body(ErrorResponse.of("MEMBER_RULE", e.getMessage(), currentTraceId()));
	}

	// ── 장바구니 · 주문 · 결제 ─────────────────────────────────────

	/** 장바구니 규칙 위반 (주문 불가 사이즈, 수량 초과 등). 400. 메시지는 손님에게 그대로 보인다. */
	@ExceptionHandler(com.leoneferito.order.CartItem.CartException.class)
	public ResponseEntity<ErrorResponse> handleCart(com.leoneferito.order.CartItem.CartException e) {
		return ResponseEntity.badRequest().body(ErrorResponse.of("CART_INVALID", e.getMessage(), currentTraceId()));
	}

	/** 정렬하려는 목록이 그 사이 바뀌었다 (다른 관리자가 추가·삭제). 409. */
	@ExceptionHandler(StaleListException.class)
	public ResponseEntity<ErrorResponse> handleStaleList(StaleListException e) {
		return ResponseEntity.status(HttpStatus.CONFLICT)
				.body(ErrorResponse.of("STALE_LIST", e.getMessage(), currentTraceId()));
	}

	/** 임시 비밀번호 관리자 — 비밀번호를 바꾸기 전에는 관리자 API 를 못 쓴다. 403. */
	@ExceptionHandler(com.leoneferito.auth.AdminPasswordGate.PasswordChangeRequiredException.class)
	public ResponseEntity<ErrorResponse> handlePasswordChangeRequired(
			com.leoneferito.auth.AdminPasswordGate.PasswordChangeRequiredException e) {
		return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ErrorResponse.of("PASSWORD_CHANGE_REQUIRED",
				"임시 비밀번호입니다. 비밀번호를 바꾼 뒤 이용해 주세요.", currentTraceId()));
	}

	/** 교환·반품 신청이 규칙에 맞지 않는다 (기간 · 수량 · 사이즈). 손님이 고칠 수 있게 문구를 그대로 보낸다. */
	@ExceptionHandler(com.leoneferito.order.ReturnService.ReturnException.class)
	public ResponseEntity<ErrorResponse> handleReturn(com.leoneferito.order.ReturnService.ReturnException e) {
		return ResponseEntity.badRequest().body(ErrorResponse.of("RETURN_INVALID", e.getMessage(), currentTraceId()));
	}

	/** 지금 주문 상태에서 할 수 없는 일 (제작 중인 주문 취소 등). 409. */
	@ExceptionHandler(com.leoneferito.order.ShopOrder.OrderStateException.class)
	public ResponseEntity<ErrorResponse> handleOrderState(com.leoneferito.order.ShopOrder.OrderStateException e) {
		return ResponseEntity.status(HttpStatus.CONFLICT)
				.body(ErrorResponse.of("ORDER_STATE", e.getMessage(), currentTraceId()));
	}

	/**
	 * 결제사 거절 · 연결 실패. 키가 없으면 503(결제 준비 중), 나머지는 409.
	 * 토스 메시지는 손님용 문장이라 그대로 보여준다("한도 초과입니다" 등) — 무엇을 해야 하는지 알 수 있다.
	 */
	@ExceptionHandler(com.leoneferito.payment.TossPaymentsClient.PaymentException.class)
	public ResponseEntity<ErrorResponse> handlePayment(com.leoneferito.payment.TossPaymentsClient.PaymentException e) {
		String traceId = currentTraceId();
		if ("NOT_CONFIGURED".equals(e.getCode())) {
			return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
					.body(ErrorResponse.of("PAYMENT_NOT_READY", "결제 준비 중입니다.", traceId));
		}
		log.info("결제 실패 traceId={} code={}", traceId, e.getCode());
		return ResponseEntity.status(HttpStatus.CONFLICT)
				.body(ErrorResponse.of("PAYMENT_FAILED", e.getMessage(), traceId));
	}

	/** 화면이 보낸 결제 금액이 주문 금액과 다르다. 토스에 보내기 전에 거절했다. 400. */
	@ExceptionHandler(com.leoneferito.order.OrderService.AmountMismatchException.class)
	public ResponseEntity<ErrorResponse> handleAmountMismatch(com.leoneferito.order.OrderService.AmountMismatchException e) {
		return ResponseEntity.badRequest().body(ErrorResponse.of("AMOUNT_MISMATCH",
				"결제 금액이 주문 금액과 다릅니다. 주문서를 다시 만들어 주세요.", currentTraceId()));
	}

	/** 배송비 정책이 없다 — 주문을 받지 않는다(fail closed). 503. */
	@ExceptionHandler(com.leoneferito.order.OrderService.ShippingPolicyPendingException.class)
	public ResponseEntity<ErrorResponse> handleShippingPending(com.leoneferito.order.OrderService.ShippingPolicyPendingException e) {
		return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(ErrorResponse.of("SHIPPING_POLICY_PENDING",
				"배송 정책을 준비하고 있어 지금은 주문할 수 없습니다.", currentTraceId()));
	}

	/** 진행 중인 주문이 있어 탈퇴할 수 없다. 409. */
	@ExceptionHandler(com.leoneferito.member.MemberAccountService.OrdersInProgressException.class)
	public ResponseEntity<ErrorResponse> handleOrdersInProgress(com.leoneferito.member.MemberAccountService.OrdersInProgressException e) {
		return ResponseEntity.status(HttpStatus.CONFLICT).body(ErrorResponse.of("ORDERS_IN_PROGRESS",
				"진행 중인 주문이나 교환·반품이 있어 탈퇴할 수 없습니다. 끝난 뒤 다시 시도해 주세요.", currentTraceId()));
	}

	/**
	 * 업로드된 파일이 이미지로 받아들일 수 없는 경우. 400 이다.
	 *
	 * <p>메시지를 그대로 내보낸다 — 올리는 사람은 관리자(신뢰 경계 안)이고,
	 * 무엇이 잘못됐는지 알아야 다시 올릴 수 있다.
	 */
	@ExceptionHandler(com.leoneferito.media.InvalidImageException.class)
	public ResponseEntity<ErrorResponse> handleInvalidImage(com.leoneferito.media.InvalidImageException e) {
		return ResponseEntity.badRequest()
				.body(ErrorResponse.of("INVALID_IMAGE", e.getMessage(), currentTraceId()));
	}

	/**
	 * 업로드 용량 초과. 413 이다.
	 *
	 * <p>이게 없으면 500 이 나간다. 서블릿이 요청을 다 받기 전에 끊으므로
	 * 컨트롤러에 도달하지도 못하고, 관리자는 "저장이 안 된다" 만 보게 된다.
	 * 413 과 함께 한도를 알려줘야 파일을 줄여서 다시 올릴 수 있다.
	 */
	@ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
	public ResponseEntity<ErrorResponse> handleUploadTooLarge(org.springframework.web.multipart.MaxUploadSizeExceededException e) {
		long mb = com.leoneferito.media.MediaService.MAX_BYTES / 1024 / 1024;
		return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
				.body(ErrorResponse.of("FILE_TOO_LARGE",
						"이미지가 너무 큽니다. " + mb + "MB 이하로 올려 주세요.", currentTraceId()));
	}

	/** 이미 쓰고 있는 slug. 409 다. slug 는 URL 이라 겹칠 수 없다. */
	@ExceptionHandler(com.leoneferito.product.AdminProductService.DuplicateSlugException.class)
	public ResponseEntity<ErrorResponse> handleDuplicateSlug(com.leoneferito.product.AdminProductService.DuplicateSlugException e) {
		String traceId = currentTraceId();
		log.info("slug 중복 traceId={} reason={}", traceId, e.getMessage());
		return ResponseEntity.status(HttpStatus.CONFLICT)
				.body(ErrorResponse.of("DUPLICATE_SLUG", "이미 사용 중인 주소(slug)입니다.", traceId));
	}

	/**
	 * 공개된 상품의 주소를 바꾸려 한 경우. 409 다.
	 *
	 * <p>조용히 무시하면 관리자는 바뀐 줄 안다. 거부하고 이유를 말한다.
	 */
	@ExceptionHandler(com.leoneferito.product.AdminProductService.SlugChangeNotAllowedException.class)
	public ResponseEntity<ErrorResponse> handleSlugChange(com.leoneferito.product.AdminProductService.SlugChangeNotAllowedException e) {
		return ResponseEntity.status(HttpStatus.CONFLICT)
				.body(ErrorResponse.of("SLUG_IMMUTABLE",
						"주소(slug)는 변경할 수 없습니다. 이미 걸린 링크가 모두 끊어집니다.",
						currentTraceId()));
	}

	/**
	 * 도메인 규칙 위반 (예: 값이 덜 찬 상품을 공개하려 함). 409 다.
	 *
	 * <p>메시지를 그대로 내보내지 않는다 — 내부 식별자가 섞여 있다.
	 * 무엇이 비었는지는 관리자 화면이 스스로 판단해 표시한다.
	 */
	@ExceptionHandler(IllegalStateException.class)
	public ResponseEntity<ErrorResponse> handleIllegalState(IllegalStateException e, HttpServletRequest request) {
		String traceId = currentTraceId();
		log.warn("도메인 규칙 위반 traceId={} path={} reason={}", traceId, request.getRequestURI(), e.getMessage());
		return ResponseEntity.status(HttpStatus.CONFLICT)
				.body(ErrorResponse.of("NOT_READY",
						"공개에 필요한 값이 비어 있습니다. 이름 · 가격 · 제작 기간 · 대표 이미지 · 상품정보제공고시를 확인해 주세요.",
						traceId));
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
