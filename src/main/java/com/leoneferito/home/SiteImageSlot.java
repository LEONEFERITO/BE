package com.leoneferito.home;

/**
 * 사이트 사진 칸 — 손님 화면에서 상품 사진이 아닌 사진이 놓이는 자리.
 *
 * <p>값을 늘리면 {@code site_image.slot} 의 CHECK 제약(지금은 V23)과 행, FE 의 {@code SiteImageSlot} 도 같이 고쳐야 한다.
 */
public enum SiteImageSlot {
    /** 메인 OFFLINE SHOP 구간의 사진. */
    OFFLINE_SHOP,

    /** 라인 페이지(/line/leone/) 위쪽 다섯 칸 — "페인포인트와 니즈 포인트". 2026-10-06 (V22). 왼쪽부터 1~5. */
    LINE_LEONE_1, LINE_LEONE_2, LINE_LEONE_3, LINE_LEONE_4, LINE_LEONE_5,
    /** 라인 페이지(/line/ferito/) 위쪽 다섯 칸. */
    LINE_FERITO_1, LINE_FERITO_2, LINE_FERITO_3, LINE_FERITO_4, LINE_FERITO_5,

    /** 메인 "두 가지 라인" 카드 두 장 (V23). */
    MAIN_LINE_LEONE, MAIN_LINE_FERITO,
    /** 룩북 컬렉션 컷 — 레오네 둘 · 페리토 둘 (V23). */
    LOOKBOOK_LEONE_1, LOOKBOOK_LEONE_2, LOOKBOOK_FERITO_1, LOOKBOOK_FERITO_2,
    /** 브랜드 페이지 — 히어로 사진 · 사진 구간 셋 · 첫인상 배경 (V23). */
    BRAND_HERO, BRAND_PHOTO_1, BRAND_PHOTO_2, BRAND_PHOTO_3, BRAND_IMPRESSION
}
