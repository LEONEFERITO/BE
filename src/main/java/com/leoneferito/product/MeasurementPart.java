package com.leoneferito.product;

/**
 * 실측 부위.
 *
 * <p>상의와 하의가 한 테이블을 쓰므로 둘의 부위가 모두 들어 있다.
 * 어떤 상품이 어떤 부위를 갖는지는 데이터가 정한다 — 스키마로 가르지 않는다.
 * 자켓에 {@link #THIGH} 가 없고 트라우저에 {@link #SHOULDER} 가 없을 뿐이다.
 */
public enum MeasurementPart {
    SHOULDER,
    CHEST,
    WAIST,
    SLEEVE,
    /** 총장. */
    LENGTH,
    THIGH,
    /** 밑단. */
    HEM,
    /** 밑위. */
    RISE
}
