package com.leoneferito.product;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 프론트 다시 빌드 — 몰아서 한 번 부르는가, 훅이 없으면 아무것도 안 하는가.
 *
 * <p>진짜 Vercel 대신 이 JVM 안에 작은 HTTP 서버를 띄워 몇 번 불렸는지 센다.
 * 커밋 뒤에만 불리는지(AFTER_COMMIT)는 AdminApiTest 가 이벤트로 본다.
 */
class FrontRebuildTriggerTest {

    private HttpServer server;
    private final AtomicInteger hits = new AtomicInteger();

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hook", exchange -> {
            hits.incrementAndGet();
            exchange.sendResponseHeaders(201, -1);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private String hookUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/hook";
    }

    private void waitUntil(java.util.function.BooleanSupplier done) throws InterruptedException {
        long until = System.currentTimeMillis() + 5_000;
        while (!done.getAsBoolean() && System.currentTimeMillis() < until) {
            Thread.sleep(20);
        }
    }

    @Test
    @DisplayName("연달아 바뀌어도 한 번만 다시 빌드한다 — 관리자가 다섯 개를 고치면 빌드는 하나")
    void debounces() throws Exception {
        FrontRebuildTrigger trigger = new FrontRebuildTrigger(hookUrl(), Duration.ofMillis(200));
        for (int i = 0; i < 5; i++) {
            trigger.on(new FrontRebuildTrigger.CatalogChanged("수정 " + i));
        }
        waitUntil(() -> hits.get() >= 1 && !trigger.isPending());
        Thread.sleep(300);
        assertThat(hits.get()).isEqualTo(1);

        // 빌드가 나간 뒤의 변경은 새 빌드를 예약한다.
        trigger.on(new FrontRebuildTrigger.CatalogChanged("다음 수정"));
        waitUntil(() -> hits.get() >= 2);
        assertThat(hits.get()).isEqualTo(2);
        trigger.shutdown();
    }

    @Test
    @DisplayName("훅 주소가 없으면 아무것도 하지 않는다 — 로컬은 그대로 돈다")
    void noHookNoCall() throws Exception {
        FrontRebuildTrigger trigger = new FrontRebuildTrigger("", Duration.ofMillis(10));
        trigger.on(new FrontRebuildTrigger.CatalogChanged("공개"));
        Thread.sleep(100);
        assertThat(trigger.isPending()).isFalse();
        assertThat(hits.get()).isZero();
        trigger.shutdown();
    }
}
