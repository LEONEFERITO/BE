package com.leoneferito.product;

/**
 * 공개 상태.
 *
 * <p>촬영본·가격·제작 기간이 없는 상품이 손님에게 그대로 보이면 안 된다.
 * 목록에서 빼는 일을 애플리케이션 조건문에만 맡기면 어느 한 쿼리에서 빠뜨리는 날이 온다.
 * 그래서 상태를 데이터로 두고, 공개 조회는 {@link #PUBLISHED} 만 본다.
 */
public enum ProductStatus {
    /** 작성 중. 공개 API 에 나가지 않는다. */
    DRAFT,
    PUBLISHED,
    /** 판매 종료. 이미 걸린 링크가 죽지 않도록 삭제하지 않고 남긴다. */
    ARCHIVED
}
