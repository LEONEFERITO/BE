package com.leoneferito.order;

/**
 * 주문 상태.
 *
 * <pre>
 *   PENDING_PAYMENT → PAID → IN_PRODUCTION → SHIPPED → DELIVERED
 *                      └──────┴→ CANCELLED (발송 전에만, 전액 환불)
 * </pre>
 *
 * 결제 전에 떠난 주문(PENDING_PAYMENT)은 지우지 않고 남긴다. 손님 화면과 관리자 기본 목록에서 숨긴다.
 */
public enum OrderStatus {
    PENDING_PAYMENT,
    PAID,
    IN_PRODUCTION,
    SHIPPED,
    DELIVERED,
    CANCELLED;

    /** 손님이 직접 취소할 수 있는가. 제작이 시작되면 안 된다 — 주문 제작이라 이미 원단을 잘랐다. */
    public boolean customerCancellable() {
        return this == PAID;
    }

    /** 관리자가 취소·환불할 수 있는가. 발송 뒤에는 반품 절차로 간다. */
    public boolean adminCancellable() {
        return this == PAID || this == IN_PRODUCTION;
    }

    /** 결제가 끝나고 아직 진행 중인 주문. 이런 주문이 있으면 탈퇴를 막는다. */
    public boolean inProgress() {
        return this == PAID || this == IN_PRODUCTION || this == SHIPPED;
    }
}
