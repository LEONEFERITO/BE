package com.leoneferito.auth;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 고정 창(fixed window) 횟수 제한.
 *
 * <p>열쇠(key) 하나에 대해 {@code window} 동안 {@code limit} 번까지만 허용한다.
 * 쓰는 쪽은 {@link AuthRateLimitFilter} 다 — 거기서 열쇠는 클라이언트 IP 다.
 *
 * <h2>왜 필요한가 — 계정별 잠금으로는 못 막는 것</h2>
 * 로그인 실패가 쌓이면 그 계정이 잠긴다({@link LoginAttemptRecorder}). 그건 <b>한 계정</b>을
 * 집중 공격하는 경우를 막는다. 그런데 유출된 아이디·비밀번호 목록을 들고 <b>계정을 바꿔가며</b>
 * 한 번씩 찍으면(크리덴셜 스터핑) 어느 계정도 임계치에 닿지 않아 잠금이 한 번도 걸리지 않는다.
 * 그 공격은 "같은 출처에서 오는 실패가 많다" 로만 보인다. 그게 이 클래스가 보는 것이다.
 *
 * <h2>고정 창을 쓰는 이유</h2>
 * 창이 바뀌는 순간 한도가 초기화되므로, 창 경계에 몰아 치면 짧은 순간 최대 2배가 통과한다.
 * 슬라이딩 창이 그 점은 낫지만 요청 시각을 모두 들고 있어야 해서 메모리가 요청 수에 비례한다.
 * 여기서 막으려는 것은 "초당 몇 번" 이 아니라 "시간당 수천 번" 이라 2배 오차는 의미가 없다.
 * 열쇠당 숫자 두 개만 들고 있는 쪽을 고른다.
 *
 * <h2>이 클래스는 프레임워크를 모른다</h2>
 * 시각을 {@code clock} 으로 받는다. 그래서 테스트가 기다리지 않고 시간을 옮겨 볼 수 있고,
 * Docker(Testcontainers) 없이 돈다 — 이 저장소에서 창 경계·초기화를 검증할 수 있는 유일한 방법이다.
 *
 * <p>스레드 안전하다. 다만 {@link #consume}는 확인과 증가가 원자적이지 않아, 동시에 들어온
 * 요청 몇 개가 한도를 살짝 넘길 수 있다. 수천 번을 막는 장치에서 몇 번의 오차는 상관없고,
 * 그걸 없애려고 전역 잠금을 걸면 정상 트래픽이 거기서 줄을 선다.
 */
public final class RateLimiter {

	private static final Logger log = LoggerFactory.getLogger(RateLimiter.class);

	private static final Decision ALLOWED = new Decision(true, 0);

	private final String name;
	private final int limit;
	private final long windowMillis;
	private final int maxTrackedClients;
	private final Supplier<Instant> clock;

	private final ConcurrentHashMap<String, Counter> counters = new ConcurrentHashMap<>();

	/** 창이 시작된 시각과 그 창에서의 횟수. 불변이라 {@code compute} 로 통째로 갈아 끼운다. */
	private record Counter(long windowStart, int count) {
	}

	/**
	 * @param name              로그에 찍히는 이름 (어느 제한에 걸렸는지 구분한다)
	 * @param limit             창 하나에서 허용하는 횟수
	 * @param window            창 길이
	 * @param maxTrackedClients 동시에 추적하는 열쇠의 최대 수 (메모리 상한)
	 * @param clock             현재 시각
	 */
	public RateLimiter(String name, int limit, Duration window, int maxTrackedClients, Supplier<Instant> clock) {
		if (limit < 1) {
			throw new IllegalArgumentException("limit 은 1 이상이어야 한다: " + limit);
		}
		if (window.isZero() || window.isNegative()) {
			throw new IllegalArgumentException("window 는 양수여야 한다: " + window);
		}
		if (maxTrackedClients < 1) {
			throw new IllegalArgumentException("maxTrackedClients 는 1 이상이어야 한다: " + maxTrackedClients);
		}
		this.name = name;
		this.limit = limit;
		this.windowMillis = window.toMillis();
		this.maxTrackedClients = maxTrackedClients;
		this.clock = clock;
	}

	/**
	 * 허용되는가. <b>횟수를 올리지 않는다.</b>
	 *
	 * <p>로그인처럼 "실패만 센다" 는 경우에 쓴다 — 요청 전에 이걸로 묻고,
	 * 응답이 실패로 끝났을 때만 {@link #record} 한다. 성공한 로그인은 한 번도 세지 않으므로
	 * 공유 IP(회사·통신사 NAT) 뒤의 정상 사용자는 한도에 영향을 받지 않는다.
	 */
	public Decision peek(String key) {
		Counter counter = counters.get(key);
		if (counter == null) {
			return ALLOWED;
		}
		long now = clock.get().toEpochMilli();
		if (isExpired(counter, now) || counter.count() < limit) {
			return ALLOWED;
		}
		return new Decision(false, retryAfterSeconds(counter, now));
	}

	/** 횟수를 하나 올린다. 한도를 넘었는지는 보지 않는다 — 판단은 {@link #peek} 가 한다. */
	public void record(String key) {
		long now = clock.get().toEpochMilli();
		sweepIfCrowded(now);

		// 표가 꽉 찼고 처음 보는 열쇠면 추적하지 않는다. 메모리가 공격 수단이 되면 안 된다.
		if (counters.size() >= maxTrackedClients && !counters.containsKey(key)) {
			log.warn("요청 제한 추적 한도 도달 limiter={} tracked={} — 새 출처는 당분간 세지 않는다",
					name, counters.size());
			return;
		}

		counters.compute(key, (k, existing) -> {
			if (existing == null || isExpired(existing, now)) {
				return new Counter(now, 1);
			}
			return new Counter(existing.windowStart(), existing.count() + 1);
		});
	}

	/**
	 * 확인하고, 허용되면 횟수를 올린다.
	 *
	 * <p>가입·비밀번호 찾기 메일처럼 <b>요청 자체가 비용</b>인 경우에 쓴다. 그쪽은 실패가
	 * 신호가 아니다 — 비밀번호 찾기는 가입 여부를 숨기려고 언제나 202 로 답하므로
	 * 응답만 봐서는 메일이 나갔는지 알 수 없다. 그래서 전부 센다.
	 */
	public Decision consume(String key) {
		Decision decision = peek(key);
		if (decision.allowed()) {
			record(key);
		}
		return decision;
	}

	/** 지금 추적 중인 열쇠 수. 테스트와 운영 점검용. */
	public int trackedClients() {
		return counters.size();
	}

	private boolean isExpired(Counter counter, long now) {
		return now - counter.windowStart() >= windowMillis;
	}

	private long retryAfterSeconds(Counter counter, long now) {
		long remaining = counter.windowStart() + windowMillis - now;
		// 올림한다. 남은 시간이 0.2초여도 "0초 뒤" 라고 하면 즉시 다시 와서 또 막힌다.
		return Math.max(1, (remaining + 999) / 1000);
	}

	/**
	 * 표가 커졌을 때만 지나간 창을 걷어낸다.
	 *
	 * <p>매 요청마다 훑으면 정상 트래픽이 그 비용을 낸다. 평소에는 아무것도 하지 않고,
	 * 표가 상한에 닿을 때만(= 실제로 많은 출처가 들어올 때만) 정리한다.
	 */
	private void sweepIfCrowded(long now) {
		if (counters.size() < maxTrackedClients) {
			return;
		}
		counters.entrySet().removeIf(entry -> isExpired(entry.getValue(), now));
	}

	/**
	 * 판정 결과.
	 *
	 * @param allowed           통과시킬지
	 * @param retryAfterSeconds 막혔을 때 몇 초 뒤에 다시 오면 되는지 (통과면 0)
	 */
	public record Decision(boolean allowed, long retryAfterSeconds) {
	}
}
