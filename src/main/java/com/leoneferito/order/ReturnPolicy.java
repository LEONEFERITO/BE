package com.leoneferito.order;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 교환·반품 신청 기간. 배송 완료일(서울 날짜) 다음 날부터 센다.
 *
 * <p>기본값은 법이 정한 <b>최소</b> 기간이다 — 판매자가 더 짧게 정할 수 없다.
 * <ul>
 *   <li>단순 변심 · 사이즈 · 기타: 공급받은 날부터 7일 (전자상거래법 제17조 ①)</li>
 *   <li>불량 · 오배송: 공급받은 날부터 3개월 (같은 법 제17조 ③).
 *       "안 날부터 30일" 도 있어서 3개월이 지나도 사정이 있으면 관리자가 따로 받는다.</li>
 * </ul>
 * TODO(고객확인) 법보다 길게 받을지 · 주문 제작품의 단순 변심 교환·반품을 받을지
 * (받지 않으려면 결제 전 고지가 있어야 한다 — 제17조 ② 5호). 받을지 말지는 지금 관리자 승인·거절로 정한다.
 */
@Component
public class ReturnPolicy {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final int changeOfMindDays;
    private final int sellerFaultMonths;

    public ReturnPolicy(@Value("${app.returns.change-of-mind-days:7}") int changeOfMindDays,
                        @Value("${app.returns.seller-fault-months:3}") int sellerFaultMonths) {
        this.changeOfMindDays = Math.max(changeOfMindDays, 7);
        this.sellerFaultMonths = Math.max(sellerFaultMonths, 3);
    }

    /** 이 사유로 신청할 수 있는 마지막 순간(그 시각 전까지). 배송 완료 전이면 없다. */
    public Optional<Instant> deadline(ShopOrder order, ReturnReason reason) {
        if (order.getStatus() != OrderStatus.DELIVERED || order.getDeliveredAt() == null) {
            return Optional.empty();
        }
        var delivered = order.getDeliveredAt().atZone(SEOUL).toLocalDate();
        var lastDay = reason.sellerFault() ? delivered.plusMonths(sellerFaultMonths) : delivered.plusDays(changeOfMindDays);
        return Optional.of(lastDay.plusDays(1).atStartOfDay(SEOUL).toInstant());
    }

    public boolean open(ShopOrder order, ReturnReason reason, Instant now) {
        return deadline(order, reason).map(now::isBefore).orElse(false);
    }

    /** 어떤 사유로든 지금 신청할 수 있는가. 불량 기간이 늘 더 길다. */
    public boolean anyOpen(ShopOrder order, Instant now) {
        return open(order, ReturnReason.DEFECT, now);
    }
}
