package com.leoneferito.order;

/**
 * 교환·반품 사유. 사유에 따라 신청 기간이 다르다 ({@link ReturnPolicy}).
 *
 * <p>상품 잘못(불량 · 오배송)은 단순 변심과 기간이 다르고, 주문 제작품이어도 제한할 수 없다
 * (전자상거래법 제17조 ③). 그래서 사유를 자유 입력이 아니라 고르게 한다.
 */
public enum ReturnReason {
    SIZE,
    CHANGE_OF_MIND,
    DEFECT,
    WRONG_ITEM,
    OTHER;

    /** 판매자 책임 사유인가 — 불량 · 오배송. */
    public boolean sellerFault() {
        return this == DEFECT || this == WRONG_ITEM;
    }
}
