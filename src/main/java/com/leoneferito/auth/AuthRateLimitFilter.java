package com.leoneferito.auth;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.leoneferito.common.error.ErrorResponse;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import tools.jackson.databind.ObjectMapper;

/**
 * 인증 관련 주소에 <b>출처(IP) 단위</b> 요청 제한을 건다.
 *
 * <h2>무엇을 막는가</h2>
 * 계정별 방어는 이미 있다 — 로그인 실패 누적 잠금({@link LoginAttemptRecorder})과
 * 재설정 메일 시간당 5회({@link PasswordResetService}). 둘 다 <b>계정 하나</b>를 기준으로 센다.
 * 그래서 계정을 바꿔가며 한 번씩 찍는 공격은 어느 쪽에도 걸리지 않았다:
 * <ul>
 *   <li>크리덴셜 스터핑 — 유출 목록으로 수천 계정에 한 번씩. 계정별 임계치에 닿지 않는다.</li>
 *   <li>가입 스팸 — 계정을 무한히 만든다. 계정별로 셀 수가 없다(아직 없는 계정이다).</li>
 *   <li>메일 폭탄 — 계정마다 5통씩, 계정 수만큼. 계정별 한도는 지켜지는데 총량은 안 지켜진다.</li>
 * </ul>
 *
 * <h2>두 가지로 나눠 센다</h2>
 * <table>
 *   <tr><th>구간</th><th>주소</th><th>세는 것</th></tr>
 *   <tr><td>자격증명</td><td>로그인 · 관리자 로그인</td><td><b>실패만</b> (401)</td></tr>
 *   <tr><td>비용</td><td>가입 · 재설정 메일 요청</td><td><b>전체 요청</b></td></tr>
 * </table>
 *
 * <p>자격증명 쪽에서 실패만 세는 이유는 <b>공유 IP</b> 때문이다. 국내 이동통신은 여러
 * 가입자가 한 IP 를 쓰고(CGNAT), 회사도 그렇다. 전체 요청을 세면 정상 사용자들이 서로의
 * 한도를 먹어 치워 로그인이 막힌다 — 우리가 스스로 만든 장애다. 성공을 세지 않으면
 * 정상 사용자는 한도에 기여하지 않고, 쌓이는 쪽은 실제로 틀리고 있는 쪽뿐이다.
 *
 * <p>비용 쪽은 실패를 셀 수 없다. 비밀번호 찾기는 가입 여부를 숨기려고 <b>언제나 202</b> 로
 * 답하므로(설계다) 응답만 봐서는 메일이 나갔는지 알 수 없다. 그래서 전부 센다. 대신 한도를
 * 넉넉히 둔다 — 사람이 가입하거나 메일을 다시 받는 횟수는 원래 적다.
 *
 * <h2>IP 를 어디서 얻는가 — 틀리면 전체 장애다</h2>
 * {@code request.getRemoteAddr()} 를 쓴다. 운영에서는 Caddy 뒤에 서므로 이 값은 프록시의
 * 주소가 되어야 할 텐데, {@code application.yml} 의 {@code forward-headers-strategy: framework}
 * 가 {@code ForwardedHeaderFilter} 를 끼워 {@code X-Forwarded-For} 의 실제 클라이언트로
 * 바꿔 준다. 그 필터는 Spring Security 보다 먼저 돌고, 이 필터는 Security 보다 나중에 도므로
 * 여기 도달할 때는 이미 바뀌어 있다.
 *
 * <p>이 전제가 깨지면(설정이 빠지면) 모든 요청이 <b>한 IP</b> 로 보여서 사이트 전체가 429 가
 * 된다. 그래서 {@code X-Forwarded-For} 를 직접 읽지 않는다 — 직접 읽으면 프록시가 없을 때
 * 공격자가 헤더를 지어내 한도를 무한히 우회할 수 있다. 신뢰 경계를 프레임워크 한 곳에 둔다.
 *
 * <h2>막지 못할 때는 통과시킨다 (fail open)</h2>
 * 이 저장소는 대체로 fail closed 다 — 설정이 없으면 기동을 멈추고, 결제 키가 없으면 주문을
 * 받지 않는다. 여기는 반대로 한다. 요청 제한은 <b>완화 장치</b>이고 출입문이 아니다.
 * 출처를 알 수 없을 때 막아 버리면 공격을 막는 대신 손님을 막는다. 그런 경우는 통과시키고
 * 로그를 남긴다.
 */
/*
 * app.auth.rate-limit.enabled 가 false 면 빈 자체를 만들지 않는다 (기본은 켜짐 — 키가 없어도 켜진다).
 *
 * 끄는 곳은 통합 테스트뿐이다 (src/test/resources/application.properties). @SpringBootTest 들이 컨텍스트를
 * 공유해서 이 필터의 카운터가 **테스트 전체에 걸쳐 누적**된다 — 가입 테스트 몇 개가 지나면 그 뒤
 * 비밀번호 재설정 테스트가 401·202 대신 429 를 받고, 원인과 무관한 테스트가 깨진다.
 * 필터 자체는 AuthRateLimitApiTest 가 자기 컨텍스트에서 켜고 검증한다.
 */
@Component
@ConditionalOnProperty(prefix = "app.auth.rate-limit", name = "enabled", havingValue = "true", matchIfMissing = true)
public class AuthRateLimitFilter extends OncePerRequestFilter {

	private static final Logger log = LoggerFactory.getLogger(AuthRateLimitFilter.class);

	/** 실패만 센다. 둘 다 비밀번호를 받는 주소다. */
	private static final Set<String> CREDENTIAL_PATHS = Set.of(
			"/api/auth/login",
			"/api/auth/admin-login");

	/** 요청 자체가 비용이다 — 계정을 만들거나 메일을 보낸다. */
	private static final Set<String> COSTLY_PATHS = Set.of(
			"/api/auth/signup",
			"/api/auth/password-reset/request");

	private final RateLimiter credentialLimiter;
	private final RateLimiter costlyLimiter;
	private final ObjectMapper objectMapper;

	public AuthRateLimitFilter(
			ObjectMapper objectMapper,
			@Value("${app.auth.rate-limit.credential-failures:30}") int credentialFailures,
			@Value("${app.auth.rate-limit.costly-requests:10}") int costlyRequests,
			@Value("${app.auth.rate-limit.window:10m}") Duration window,
			@Value("${app.auth.rate-limit.max-tracked-clients:50000}") int maxTrackedClients) {

		this.objectMapper = objectMapper;
		this.credentialLimiter = new RateLimiter("credential", credentialFailures, window, maxTrackedClients,
				Instant::now);
		this.costlyLimiter = new RateLimiter("costly", costlyRequests, window, maxTrackedClients, Instant::now);

		log.info("인증 요청 제한 — 자격증명 실패 {}회 · 비용 요청 {}회 / {}",
				credentialFailures, costlyRequests, window);
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		if (!HttpMethod.POST.matches(request.getMethod())) {
			return true;
		}
		String path = request.getRequestURI();
		return !CREDENTIAL_PATHS.contains(path) && !COSTLY_PATHS.contains(path);
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {

		String client = request.getRemoteAddr();
		if (client == null || client.isBlank()) {
			// 출처를 모르면 세지 못한다. 손님을 막는 쪽이 더 나쁘다 (머리말 "fail open").
			log.warn("요청 제한: 출처를 알 수 없어 건너뛴다 path={}", request.getRequestURI());
			chain.doFilter(request, response);
			return;
		}

		boolean costly = COSTLY_PATHS.contains(request.getRequestURI());
		RateLimiter limiter = costly ? costlyLimiter : credentialLimiter;

		// 비용 구간은 요청 자체를 센다. 자격증명 구간은 묻기만 하고, 실패했을 때만 아래에서 센다.
		RateLimiter.Decision decision = costly ? limiter.consume(client) : limiter.peek(client);

		if (!decision.allowed()) {
			/*
			 * 이 저장소에서 IP 를 로그에 남기는 유일한 곳이다. RequestLoggingFilter 는 개인정보를
			 * 남기지 않으려고 일부러 피하지만, 여기는 보안 사건이고 "어디서 오는가" 가 없으면
			 * 차단도 신고도 할 수 없다. 평상시 트래픽에는 찍히지 않는다 — 한도를 넘긴 요청만이다.
			 */
			log.warn("요청 제한 초과 path={} client={} retryAfter={}s",
					request.getRequestURI(), client, decision.retryAfterSeconds());
			writeTooManyRequests(response, decision.retryAfterSeconds());
			return;
		}

		chain.doFilter(request, response);

		/*
		 * 실패한 로그인만 센다. 401 은 이메일·비밀번호 불일치, 계정 잠김, 정지를 모두 포함한다
		 * (GlobalExceptionHandler.handleAuthFailed). 임시 비밀번호 관리자의 403 은 자격증명이
		 * 맞은 경우라 세지 않는다 — 비밀번호를 바꾸러 가는 길을 막으면 안 된다.
		 */
		if (!costly && response.getStatus() == HttpStatus.UNAUTHORIZED.value()) {
			limiter.record(client);
		}
	}

	private void writeTooManyRequests(HttpServletResponse response, long retryAfterSeconds) throws IOException {
		response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		response.setCharacterEncoding("UTF-8");
		// 표준 헤더. 프론트가 "얼마나 기다려야 하나" 를 추측하지 않아도 된다.
		response.setHeader("Retry-After", String.valueOf(retryAfterSeconds));
		/*
		 * traceId 를 넣지 않는다. 이 필터와 RequestLoggingFilter 는 둘 다 기본 순서라
		 * 어느 쪽이 먼저인지 보장되지 않고, MDC 에 없을 수도 있다. SecurityConfig 의 401·403 도
		 * 같은 이유로 비워 둔다 — 없는 값을 지어내는 것보다 빈 칸이 낫다.
		 */
		objectMapper.writeValue(response.getWriter(), ErrorResponse.of(
				"TOO_MANY_REQUESTS", "시도가 너무 잦습니다. 잠시 후 다시 시도해 주세요.", null));
	}
}
