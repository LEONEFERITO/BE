package com.leoneferito.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.leoneferito.payment.NicePayClient;
import com.leoneferito.payment.PaymentGateway;
import com.leoneferito.payment.TossPaymentsClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 값이 없으면 결제를 열지 않는다 — 결제사 키도, 배송비 정책도.
 * 지어낸 배송비가 결제되거나, 결제할 수 없는 주문서가 쌓이는 것보다 낫다.
 */
class FailClosedTest {

    @Test
    @DisplayName("토스 시크릿 키가 없으면 승인·취소를 시도하지 않고 '결제 준비 중' 이다")
    void noTossKey() {
        TossPaymentsClient toss = new TossPaymentsClient("", "http://127.0.0.1:9");
        assertThat(toss.isReady()).isFalse();
        assertThatThrownBy(() -> toss.approve("pk", "LF20261001-AAAAAAAA", 1000))
                .isInstanceOf(PaymentGateway.PaymentException.class)
                .extracting(e -> ((PaymentGateway.PaymentException) e).getCode())
                .isEqualTo("NOT_CONFIGURED");
    }

    @Test
    @DisplayName("나이스페이 키가 하나라도 없으면 승인·취소를 시도하지 않고, 어떤 서명도 통과시키지 않는다")
    void noNicePayKey() {
        for (NicePayClient nice : new NicePayClient[] {
                new NicePayClient("", "", "http://127.0.0.1:9"),
                new NicePayClient("client", "", "http://127.0.0.1:9"),
                new NicePayClient("", "secret", "http://127.0.0.1:9")}) {
            assertThat(nice.isReady()).isFalse();
            assertThatThrownBy(() -> nice.approve("tid", "LF20261001-AAAAAAAA", 1000))
                    .isInstanceOf(PaymentGateway.PaymentException.class)
                    .extracting(e -> ((PaymentGateway.PaymentException) e).getCode())
                    .isEqualTo("NOT_CONFIGURED");
            assertThat(nice.verifyAuthSignature("token", "client", "1000", "anything")).isFalse();
        }
    }

    @Test
    @DisplayName("배송비 정책이 없으면 배송비를 정하지 않는다(0원으로 치지 않는다)")
    void noShippingPolicy() {
        ShippingPolicy policy = new ShippingPolicy(null, null, "63000-63644:3000:제주");
        assertThat(policy.isReady()).isFalse();
        assertThat(policy.feeFor(100_000)).isEmpty();
        assertThat(policy.feeFor(100_000, "63100")).isEmpty(); // 추가 배송비만 있어도 열지 않는다
    }

    @Test
    @DisplayName("무료 배송 기준 이상이면 0원, 미만이면 정한 배송비")
    void freeThreshold() {
        ShippingPolicy policy = new ShippingPolicy(3_000L, 100_000L, "");
        assertThat(policy.feeFor(99_999).getAsLong()).isEqualTo(3_000);
        assertThat(policy.feeFor(100_000).getAsLong()).isZero();
    }

    @Test
    @DisplayName("제주 · 도서산간은 우편번호로 추가 배송비 — 무료 배송 주문에도 붙고, 육지 · 우편번호 없음은 추가 없다")
    void remoteAreas() {
        ShippingPolicy policy = new ShippingPolicy(3_000L, 50_000L, "63000-63644:3000:제주, 40200-40240:5000:도서산간");
        ShippingPolicy.Fee jeju = policy.feeFor(290_000, "63100").orElseThrow();
        assertThat(jeju.baseKrw()).isZero();
        assertThat(jeju.extraKrw()).isEqualTo(3_000);
        assertThat(jeju.extraLabel()).isEqualTo("제주");
        assertThat(policy.feeFor(30_000, "40240").orElseThrow().totalKrw()).isEqualTo(8_000);
        assertThat(policy.feeFor(30_000, "06236").orElseThrow().totalKrw()).isEqualTo(3_000);
        assertThat(policy.feeFor(30_000, "63645").orElseThrow().extraKrw()).isZero();
        assertThat(policy.feeFor(30_000, null).orElseThrow().extraKrw()).isZero();
        assertThat(policy.getRemoteAreas()).hasSize(2);
    }

    @Test
    @DisplayName("추가 배송비 지역 설정이 틀리면 기동하지 않는다 — 조용히 빠지면 추가 배송비가 사라진다")
    void remoteAreaSpecMustBeValid() {
        assertThatThrownBy(() -> new ShippingPolicy(3_000L, 50_000L, "63000:3000:제주"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ShippingPolicy(3_000L, 50_000L, "63644-63000:3000:제주"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ShippingPolicy(3_000L, 50_000L, "63000-63644:abc:제주"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("주문번호는 토스 orderId 규칙(영문·숫자·-·_ 6~64자)을 지킨다")
    void orderNumberFormat() {
        for (int i = 0; i < 100; i++) {
            assertThat(OrderService.newOrderNumber()).matches("^[A-Za-z0-9_-]{6,64}$");
        }
    }
}
