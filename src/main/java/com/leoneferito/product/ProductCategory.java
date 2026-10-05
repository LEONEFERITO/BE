package com.leoneferito.product;

/**
 * 제품 분류. 목록 필터·카테고리 페이지·내비게이션이 같은 값을 본다.
 *
 * <p>값을 늘리면 CHECK 제약(지금은 {@code V19__product_category_suit_accessories.sql})과
 * FE 의 {@code Category} · {@code CATEGORY_LABEL} · {@code CATEGORY_NAV} 도 같이 고쳐야 한다.
 *
 * <p>여섯 분류와 순서는 고객 지정이다 (2026-10-05 디자인 가이드 — 기존 몰의 내비
 * Suit · Jacket · Trousers · Shirts · Shoes · Accessories). 선언 순서를 그 순서로 둔다.
 */
public enum ProductCategory {
    /** 수트. 2026-10-05 추가 (V19). */
    SUIT,
    JACKET,
    TROUSERS,
    SHIRT,
    /** 구두 · 로퍼. */
    SHOES,
    /** 액세서리. 2026-10-05 추가 (V19). */
    ACCESSORIES
}
