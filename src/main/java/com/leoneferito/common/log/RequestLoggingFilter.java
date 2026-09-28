package com.leoneferito.common.log;

import java.io.IOException;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 요청 한 건당 한 줄씩 로그를 남긴다.
 *
 * <p><b>개인정보를 로그에 남기지 않는다.</b> 그래서 아래를 의도적으로 기록하지 않는다:
 * <ul>
 *   <li>쿼리스트링 — {@code ?email=...&phone=...} 형태로 개인정보가 들어온다</li>
 *   <li>요청/응답 본문 — 주문자 이름·주소·연락처가 그대로 들어있다</li>
 *   <li>헤더 — 쿠키·인증 토큰이 들어있다</li>
 * </ul>
 * 남기는 것은 메서드 · 경로 · 상태코드 · 소요시간 · traceId 뿐이다.
 * 이것만으로 "어떤 API 가 느린가 / 어디서 5xx 가 나는가" 는 충분히 추적된다.
 *
 * <p>traceId 는 여기서 만들어 {@link MDC} 에 넣는다. 같은 요청에서 발생한 모든 로그와
 * {@code GlobalExceptionHandler} 의 에러 응답이 같은 id 를 공유하게 되어,
 * 고객이 알려준 id 하나로 서버 로그를 찾을 수 있다.
 */
@Component
public class RequestLoggingFilter extends OncePerRequestFilter {

	public static final String TRACE_ID = "traceId";
	public static final String TRACE_ID_HEADER = "X-Trace-Id";

	private static final Logger log = LoggerFactory.getLogger(RequestLoggingFilter.class);

	/** 헬스체크는 수십 초마다 들어와서 로그를 덮어버린다. 남길 가치가 없다. */
	private static final String HEALTH_PATH = "/actuator/health";

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		return request.getRequestURI().startsWith(HEALTH_PATH);
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {

		String traceId = UUID.randomUUID().toString().substring(0, 8);
		MDC.put(TRACE_ID, traceId);
		response.setHeader(TRACE_ID_HEADER, traceId);

		long startedAt = System.nanoTime();
		try {
			chain.doFilter(request, response);
		}
		finally {
			long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;

			// getRequestURI() 는 쿼리스트링을 포함하지 않는다. getRequestURL()+getQueryString() 을
			// 쓰면 개인정보가 섞여 들어오므로 쓰지 않는다.
			log.info("{} {} -> {} ({}ms) traceId={}",
					request.getMethod(),
					request.getRequestURI(),
					response.getStatus(),
					elapsedMs,
					traceId);

			// 스레드가 재사용되므로 반드시 지운다. 안 지우면 다음 요청 로그에 남의 traceId 가 붙는다.
			MDC.remove(TRACE_ID);
		}
	}
}
