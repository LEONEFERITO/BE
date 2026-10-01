package com.leoneferito.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.leoneferito.payment.TossPaymentsClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 값이 없으면 결제를 열지 않는다 — 토스 키도, 배송비 정책도.
 * 지어낸 배송비가 결제되거나, 결제할 수 없는 주문서가 쌓이는 것보다 낫다.
 */
class FailClosedTest {

    @Test
    @DisplayName("토스 시크릿 키가 없으면 승인·취소를 시도하지 않고 '결제 준비 중' 이다")
    void noTossKey() {
        TossPaymentsClient toss = new TossPaymentsClient("", "http://127.0.0.1:9");
        assertThat(toss.isReady()).isFalse();
        assertThatThrownBy(() -> toss.confirm("pk", "LF20261001-AAAAAAAA", 1000))
                .isInstanceOf(TossPaymentsClient.PaymentException.class)
                .extracting(e -> ((TossPaymentsClient.PaymentException) e).getCode())
                .isEqualTo("NOT_CONFIGURED");
    }

    @Test
    @DisplayName("배송비 정책이 없으면 배송비를 정하지 않는다(0원으로 치지 않는다)")
    void noShippingPolicy() {
        ShippingPolicy policy = new ShippingPolicy(null, null);
        assertThat(policy.isReady()).isFalse();
        assertThat(policy.feeFor(100_000)).isEmpty();
    }

    @Test
    @DisplayName("무료 배송 기준 이상이면 0원, 미만이면 정한 배송비")
    void freeThreshold() {
        ShippingPolicy policy = new ShippingPolicy(3_000L, 100_000L);
        assertThat(policy.feeFor(99_999).getAsLong()).isEqualTo(3_000);
        assertThat(policy.feeFor(100_000).getAsLong()).isZero();
    }

    @Test
    @DisplayName("주문번호는 토스 orderId 규칙(영문·숫자·-·_ 6~64자)을 지킨다")
    void orderNumberFormat() {
        for (int i = 0; i < 100; i++) {
            assertThat(OrderService.newOrderNumber()).matches("^[A-Za-z0-9_-]{6,64}$");
        }
    }
}
