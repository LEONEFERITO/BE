package com.leoneferito.order.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.leoneferito.order.OrderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 토스페이먼츠 웹훅 — 결제 상태 변경 (PAYMENT_STATUS_CHANGED).
 *
 * <p>토스 개발자센터 › 웹훅에 {@code {API 주소}/api/payments/toss/webhook} 을 등록한다.
 * 로그인 · CSRF 없이 열려 있다(토스 서버가 보낸다). 그래서 <b>본문을 믿지 않는다</b> —
 * 결제 키만 꺼내 토스에 다시 조회하고, 그 답으로 맞춘다 ({@link OrderService#reconcile}).
 *
 * <p>200 이 아니면 토스가 다시 보낸다. 조회가 실패하면 오류를 그대로 내서 다시 받는다.
 */
@RestController
public class TossWebhookController {

    private static final Logger log = LoggerFactory.getLogger(TossWebhookController.class);

    private final OrderService orders;

    public TossWebhookController(OrderService orders) {
        this.orders = orders;
    }

    @PostMapping("/api/payments/toss/webhook")
    public ResponseEntity<Void> receive(@RequestBody Event event) {
        String key = event.data() == null ? null : event.data().paymentKey();
        if (!"PAYMENT_STATUS_CHANGED".equals(event.eventType()) || key == null
                || !key.matches("^[A-Za-z0-9_-]{1,200}$")) {
            log.info("웹훅: 처리하지 않는 이벤트 type={}", event.eventType());
            return ResponseEntity.ok().build();
        }
        orders.reconcile(key);
        return ResponseEntity.ok().build();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Event(String eventType, Data data) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Data(String paymentKey) {
    }
}
