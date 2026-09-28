package com.leoneferito.product;

/**
 * 제품 분류. 목록 필터·메인의 카테고리 격자·내비게이션이 같은 값을 본다.
 *
 * <p>값을 늘리면 {@code V3__product.sql} 의 CHECK 제약과 FE 의 {@code CATEGORY_LABEL} 도
 * 같이 고쳐야 한다.
 */
public enum ProductCategory {
    JACKET,
    TROUSERS,
    SHIRT,
    /** 구두 · 로퍼. */
    SHOES
}
