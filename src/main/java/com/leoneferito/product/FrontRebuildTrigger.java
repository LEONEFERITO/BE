package com.leoneferito.product;

import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 손님 화면을 다시 빌드하게 한다.
 *
 * <h2>왜 필요한가</h2>
 * 프론트는 정적 내보내기다(D3). 상품 목록·상세 HTML 은 <b>빌드할 때</b> 이 API 에서 받아 만든다 —
 * 그래서 검색엔진과 카톡 미리보기가 상품명을 읽는다. 대신 관리자가 상품을 공개해도
 * 다시 빌드하기 전까지 손님 화면은 그대로다. 공개·비공개·공개 상품 수정이 커밋되면
 * Vercel 배포 훅을 불러 다시 빌드한다. 반영까지 1~2분 걸린다.
 *
 * <h2>몰아서 한 번</h2>
 * 관리자는 상품 여러 개를 연달아 고친다. 저장할 때마다 빌드하면 빌드가 줄을 서고
 * Vercel 빌드 한도를 쓴다. 첫 변경 뒤 {@code delay} 동안 들어온 변경은 한 번의 빌드로 묶는다.
 *
 * <h2>커밋 뒤에만</h2>
 * 롤백된 변경으로 빌드하면 바뀐 게 없는 빌드가 돈다. 그보다 나쁜 건, 커밋 전에 빌드가 API 를
 * 읽어 옛 내용으로 굳는 것이다. 그래서 트랜잭션이 커밋된 다음에만 예약한다.
 *
 * <p>훅 주소가 없으면(로컬 · 아직 Vercel 연결 전) 아무것도 하지 않는다. 훅 주소는 비밀이다 —
 * 아는 사람은 누구나 빌드를 돌릴 수 있다. 로그에 찍지 않는다.
 */
@Component
public class FrontRebuildTrigger {

    private static final Logger log = LoggerFactory.getLogger(FrontRebuildTrigger.class);

    /** 상품 목록이나 내용이 손님 화면에서 바뀌는 변경. 공개 · 비공개 · 공개 상품 수정. */
    public record CatalogChanged(String reason) {
    }

    private final String hookUrl;
    private final Duration delay;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "front-rebuild");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean pending = new AtomicBoolean(false);

    public FrontRebuildTrigger(@Value("${app.front.deploy-hook-url:}") String hookUrl,
                               @Value("${app.front.rebuild-delay:30s}") Duration delay) {
        this.hookUrl = hookUrl == null ? "" : hookUrl.trim();
        this.delay = delay;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void on(CatalogChanged event) {
        if (hookUrl.isEmpty()) {
            log.debug("배포 훅 없음 — 다시 빌드하지 않음 reason={}", event.reason());
            return;
        }
        // 이미 예약돼 있으면 그 빌드에 묶인다.
        if (pending.compareAndSet(false, true)) {
            scheduler.schedule(this::fire, delay.toMillis(), TimeUnit.MILLISECONDS);
            log.info("프론트 다시 빌드 예약 delay={} reason={}", delay, event.reason());
        }
    }

    /** 지금 예약된 빌드가 있는가. 테스트와 운영 확인용. */
    public boolean isPending() {
        return pending.get();
    }

    private void fire() {
        pending.set(false);
        try {
            HttpResponse<Void> res = http.send(
                    HttpRequest.newBuilder(URI.create(hookUrl))
                            .timeout(Duration.ofSeconds(10))
                            .POST(HttpRequest.BodyPublishers.noBody())
                            .build(),
                    HttpResponse.BodyHandlers.discarding());
            if (res.statusCode() / 100 == 2) {
                log.info("프론트 다시 빌드 요청 완료 status={}", res.statusCode());
            } else {
                log.error("프론트 다시 빌드 요청 거부 status={} — 손님 화면이 옛 상품으로 남아 있다", res.statusCode());
            }
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.error("프론트 다시 빌드 요청 실패 — 손님 화면이 옛 상품으로 남아 있다", e);
        }
    }

    @PreDestroy
    void shutdown() {
        scheduler.shutdownNow();
    }
}
