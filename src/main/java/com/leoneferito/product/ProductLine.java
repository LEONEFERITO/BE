package com.leoneferito.product;

/**
 * 제품 라인. 브랜드 이름이 곧 라인 이름이다 (2026-09-28 고객 확정).
 *
 * <p>{@code ATHLETIC}/{@code REGULAR} 같은 일반 명사로 두지 않는다. 그렇게 두면
 * 화면에는 브랜드 이름이 나오는데 코드에는 없어서, 나중에 둘이 어긋나도 아무도 모른다.
 *
 * <p>값을 늘리면 {@code V3__product.sql} 의 CHECK 제약과 FE 의 {@code LINE_LABEL} 도
 * 같이 고쳐야 한다. 한 곳만 고치면 DB 가 INSERT 를 거부한다 — 조용히 틀리지 않는다.
 */
public enum ProductLine {
    /** 클래식을 기반으로 정제한 포멀 실루엣. */
    LEONE,
    /** 운동으로 발달한 체형을 고려한, 섹시한 포멀 실루엣. */
    FERITO
}
