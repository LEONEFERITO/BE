package com.leoneferito.order;

import java.util.OptionalLong;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 배송비 정책. TODO(고객확인) 금액 · 무료 배송 기준.
 *
 * <p><b>값이 없으면 주문을 받지 않는다(fail closed).</b> 배송비를 지어내면 그 금액이 실제로 결제된다.
 * 운영에서는 {@code ORDER_SHIPPING_FEE_KRW} 를 넣어야 결제가 열린다. 로컬 프로필만 개발용 값을 준다.
 */
@Component
public class ShippingPolicy {

    private final Long feeKrw;
    private final Long freeThresholdKrw;

    public ShippingPolicy(@Value("${app.order.shipping-fee-krw:#{null}}") Long feeKrw,
                          @Value("${app.order.free-shipping-threshold-krw:#{null}}") Long freeThresholdKrw) {
        if (feeKrw != null && feeKrw < 0) {
            throw new IllegalArgumentException("배송비는 0원 이상이어야 한다");
        }
        this.feeKrw = feeKrw;
        this.freeThresholdKrw = freeThresholdKrw;
    }

    public boolean isReady() {
        return feeKrw != null;
    }

    /** 이 상품 금액에 붙는 배송비. 정책이 없으면 비어 있다 — 그러면 주문을 열지 않는다. */
    public OptionalLong feeFor(long itemsAmountKrw) {
        if (feeKrw == null) {
            return OptionalLong.empty();
        }
        if (freeThresholdKrw != null && itemsAmountKrw >= freeThresholdKrw) {
            return OptionalLong.of(0);
        }
        return OptionalLong.of(feeKrw);
    }

    public Long getFreeThresholdKrw() {
        return freeThresholdKrw;
    }
}
