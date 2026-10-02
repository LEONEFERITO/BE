package com.leoneferito.product;

/**
 * 상품 사진의 종류. 고객이 상세페이지 요구사항에서 지정한 4종이다 (2026-09-28).
 *
 * <p>순서(정렬)는 {@code sort_order} 가 따로 들고 있다. 이 enum 의 선언 순서에 의미를 두지 않는다
 * — 선언 순서에 기대면 값을 추가할 때 화면 순서가 같이 흔들린다.
 */
public enum ProductImageKind {
    /** 대표 이미지. 상품당 한 장만 존재한다(DB 부분 유니크 인덱스로 강제). */
    MAIN,
    /** 착용샷. */
    WORN,
    /** 디테일컷. */
    DETAIL,
    /** 누끼샷. 배경이 없는 컷 — 단색 면 위에 올릴 때 쓴다. */
    CUTOUT,
    /**
     * 상세 이미지 (V17). 한국 쇼핑몰식 긴 상세페이지 이미지 — 여러 장, 손님 화면은 간격 없이 이어 붙인다.
     * 메인 사진과 달리 여러 장이다 (최대 {@link #STORY_MAX}).
     */
    STORY;

    /** 상세 이미지 최대 장수. 메인 사진 종류(나머지)는 종류마다 한 장이다. */
    public static final int STORY_MAX = 30;
}
