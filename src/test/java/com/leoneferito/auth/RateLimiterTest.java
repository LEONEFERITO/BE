package com.leoneferito.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 출처 단위 요청 제한.
 *
 * <p>시각을 주입받는 덕에 <b>기다리지 않고</b> 창 경계를 넘겨 볼 수 있고, Docker 없이 돈다.
 * 창 초기화는 "10분 뒤" 를 실제로 기다려야 확인되는 종류라, 시계를 손에 쥐지 않으면
 * 사실상 검증할 수 없는 부분이다.
 */
class RateLimiterTest {

	/** 테스트가 마음대로 옮기는 시계. */
	private static final class FakeClock {
		private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-04T00:00:00Z"));

		Instant get() {
			return now.get();
		}

		void advance(Duration d) {
			now.updateAndGet(t -> t.plus(d));
		}
	}

	private static RateLimiter limiter(int limit, Duration window, FakeClock clock) {
		return new RateLimiter("test", limit, window, 1000, clock::get);
	}

	@Nested
	@DisplayName("한도")
	class Limit {

		@Test
		@DisplayName("한도까지는 통과하고, 넘기면 막는다")
		void blocksOverLimit() {
			FakeClock clock = new FakeClock();
			RateLimiter rl = limiter(3, Duration.ofMinutes(10), clock);

			for (int i = 0; i < 3; i++) {
				assertThat(rl.consume("1.1.1.1").allowed()).as("%d번째", i + 1).isTrue();
			}
			assertThat(rl.consume("1.1.1.1").allowed()).isFalse();
		}

		@Test
		@DisplayName("출처가 다르면 한도를 따로 센다 — 한 쪽이 막혀도 다른 쪽은 멀쩡하다")
		void perClient() {
			FakeClock clock = new FakeClock();
			RateLimiter rl = limiter(2, Duration.ofMinutes(10), clock);

			rl.consume("1.1.1.1");
			rl.consume("1.1.1.1");
			assertThat(rl.consume("1.1.1.1").allowed()).isFalse();

			// 같은 한도를 쓰지만 다른 출처다. 이게 깨지면 한 명이 전체를 막을 수 있다.
			assertThat(rl.consume("2.2.2.2").allowed()).isTrue();
		}

		@Test
		@DisplayName("막혔을 때 '몇 초 뒤' 는 남은 시간을 올림해서 알려준다 (0초라고 하지 않는다)")
		void retryAfterIsNeverZero() {
			FakeClock clock = new FakeClock();
			RateLimiter rl = limiter(1, Duration.ofMinutes(10), clock);
			rl.consume("1.1.1.1");

			assertThat(rl.peek("1.1.1.1").retryAfterSeconds()).isEqualTo(600);

			// 창이 끝나기 0.2초 전 — "0초 뒤에 오라" 고 하면 즉시 다시 와서 또 막힌다.
			clock.advance(Duration.ofMillis(599_800));
			assertThat(rl.peek("1.1.1.1").retryAfterSeconds()).isEqualTo(1);
		}
	}

	@Nested
	@DisplayName("창")
	class Window {

		@Test
		@DisplayName("창이 지나면 횟수가 0 으로 돌아간다")
		void resetsAfterWindow() {
			FakeClock clock = new FakeClock();
			RateLimiter rl = limiter(2, Duration.ofMinutes(10), clock);

			rl.consume("1.1.1.1");
			rl.consume("1.1.1.1");
			assertThat(rl.consume("1.1.1.1").allowed()).isFalse();

			clock.advance(Duration.ofMinutes(10));
			assertThat(rl.consume("1.1.1.1").allowed()).as("창이 지났으면 다시 받는다").isTrue();
		}

		@Test
		@DisplayName("창 안에서는 시간이 흘러도 초기화되지 않는다")
		void notResetWithinWindow() {
			FakeClock clock = new FakeClock();
			RateLimiter rl = limiter(2, Duration.ofMinutes(10), clock);

			rl.consume("1.1.1.1");
			clock.advance(Duration.ofMinutes(9));
			rl.consume("1.1.1.1");

			assertThat(rl.consume("1.1.1.1").allowed()).isFalse();
		}
	}

	@Nested
	@DisplayName("실패만 세기 (로그인)")
	class PeekAndRecord {

		@Test
		@DisplayName("peek 은 횟수를 올리지 않는다 — 성공한 로그인은 한도에 쌓이지 않는다")
		void peekDoesNotCount() {
			FakeClock clock = new FakeClock();
			RateLimiter rl = limiter(3, Duration.ofMinutes(10), clock);

			// 성공한 로그인 100번: 묻기만 하고 세지 않는다.
			for (int i = 0; i < 100; i++) {
				assertThat(rl.peek("1.1.1.1").allowed()).isTrue();
			}
			assertThat(rl.trackedClients()).as("추적 대상에 아예 올라가지 않는다").isZero();

			// 실패 3번을 세면 그때부터 막힌다.
			rl.record("1.1.1.1");
			rl.record("1.1.1.1");
			assertThat(rl.peek("1.1.1.1").allowed()).as("2번 실패까지는 통과").isTrue();
			rl.record("1.1.1.1");
			assertThat(rl.peek("1.1.1.1").allowed()).as("3번 실패하면 막힘").isFalse();
		}

		@Test
		@DisplayName("공유 IP 뒤에서 한 명이 계속 틀려도, 그 IP 의 성공 로그인은 영향받지 않는다")
		void sharedIpSuccessUnaffected() {
			FakeClock clock = new FakeClock();
			RateLimiter rl = limiter(3, Duration.ofMinutes(10), clock);

			// 통신사 NAT: 같은 IP. 한 명이 3번 틀려 한도를 채운다.
			for (int i = 0; i < 3; i++) {
				rl.record("203.0.113.9");
			}
			// 이제 그 IP 는 막힌다 — 이게 의도다(실패가 쌓였으므로).
			assertThat(rl.peek("203.0.113.9").allowed()).isFalse();

			/*
             * 여기서 확인하는 것은 "성공이 한도를 먹지 않는다" 다. 위 3번이 전부 실패였다는 점이
             * 핵심이다 — 만약 전체 요청을 셌다면 같은 IP 의 정상 로그인 3번만으로도 막혔을 것이고,
             * 그건 우리가 만든 장애다. 창이 지나면 풀린다.
             */
			clock.advance(Duration.ofMinutes(10));
			assertThat(rl.peek("203.0.113.9").allowed()).isTrue();
		}
	}

	@Nested
	@DisplayName("메모리 상한")
	class MemoryBound {

		@Test
		@DisplayName("추적 상한을 넘으면 지나간 창을 걷어내고 자리를 만든다")
		void sweepsExpired() {
			FakeClock clock = new FakeClock();
			RateLimiter rl = new RateLimiter("test", 5, Duration.ofMinutes(10), 10, clock::get);

			for (int i = 0; i < 10; i++) {
				rl.record("10.0.0." + i);
			}
			assertThat(rl.trackedClients()).isEqualTo(10);

			// 창이 지난 뒤 새 출처가 오면, 죽은 항목이 걷히고 새 것이 들어간다.
			clock.advance(Duration.ofMinutes(10));
			rl.record("10.0.1.1");
			assertThat(rl.trackedClients()).as("지나간 10개가 걷히고 새 1개만 남는다").isEqualTo(1);
		}

		@Test
		@DisplayName("상한이 살아 있는 창으로 꽉 차면 새 출처는 세지 않는다 (메모리를 늘리지 않는다)")
		void stopsTrackingWhenSaturated() {
			FakeClock clock = new FakeClock();
			RateLimiter rl = new RateLimiter("test", 5, Duration.ofMinutes(10), 10, clock::get);

			for (int i = 0; i < 10; i++) {
				rl.record("10.0.0." + i);
			}
			rl.record("10.0.9.9"); // 처음 보는 출처, 표는 꽉 찼다

			assertThat(rl.trackedClients()).as("표가 커지지 않는다").isEqualTo(10);
			// 세지 않았으므로 통과한다 — 막는 것보다 통과가 낫다(완화 장치다).
			assertThat(rl.peek("10.0.9.9").allowed()).isTrue();
		}
	}

	@Nested
	@DisplayName("설정값")
	class Configuration {

		@Test
		@DisplayName("말이 안 되는 설정은 기동 때 거부한다 — 조용히 '제한 없음' 이 되면 안 된다")
		void rejectsNonsense() {
			FakeClock clock = new FakeClock();
			assertThatThrownBy(() -> limiter(0, Duration.ofMinutes(10), clock))
					.isInstanceOf(IllegalArgumentException.class);
			assertThatThrownBy(() -> limiter(5, Duration.ZERO, clock))
					.isInstanceOf(IllegalArgumentException.class);
			assertThatThrownBy(() -> new RateLimiter("test", 5, Duration.ofMinutes(10), 0, clock::get))
					.isInstanceOf(IllegalArgumentException.class);
		}
	}

	@Test
	@DisplayName("동시에 들어와도 한도를 크게 넘기지 않는다")
	void concurrent() throws Exception {
		FakeClock clock = new FakeClock();
		int limit = 50;
		RateLimiter rl = limiter(limit, Duration.ofMinutes(10), clock);

		int threads = 16;
		int perThread = 40;
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		CountDownLatch start = new CountDownLatch(1);
		AtomicInteger allowed = new AtomicInteger();

		for (int t = 0; t < threads; t++) {
			pool.submit(() -> {
				start.await();
				for (int i = 0; i < perThread; i++) {
					if (rl.consume("1.1.1.1").allowed()) {
						allowed.incrementAndGet();
					}
				}
				return null;
			});
		}
		start.countDown();
		pool.shutdown();
		assertThat(pool.awaitTermination(20, TimeUnit.SECONDS)).isTrue();

		/*
		 * consume 은 확인과 증가가 원자적이지 않아 동시 요청 몇 개가 한도를 넘길 수 있다(의도된 절충 —
		 * RateLimiter 머리말 참고). 수천 번을 막는 장치이므로 "정확히 limit" 이 아니라
		 * "limit 근처에서 멈춘다" 를 확인한다. 640번 시도가 수십 번으로 줄어드는 것이 요점이다.
		 */
		assertThat(allowed.get()).isGreaterThanOrEqualTo(limit);
		assertThat(allowed.get()).isLessThan(limit + threads);
	}
}
