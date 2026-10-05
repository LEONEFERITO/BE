package com.leoneferito.home;

/**
 * 사이트 사진 칸 — 손님 화면에서 상품 사진이 아닌 사진이 놓이는 자리.
 *
 * <p>값을 늘리면 {@code site_image.slot} 의 CHECK 제약(V20)과 행, FE 의 {@code SiteImageSlot} 도 같이 고쳐야 한다.
 */
public enum SiteImageSlot {
    /** 메인 OFFLINE SHOP 구간의 사진. */
    OFFLINE_SHOP
}
