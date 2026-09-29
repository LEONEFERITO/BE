-- ============================================================
-- V7 — 상세 사이즈 차트 이미지
--
-- 고객이 만든 차트를 그대로 올린다(2026-09-29 결정).
-- 카테고리마다 재는 곳이 달라, 브랜드 쪽에서 쓰는 표를 그대로 쓰고 싶다는 요구다.
--
-- product_measurement(숫자 표)는 **지우지 않는다.** 지금 화면은 이미지를 쓰지만
-- 숫자가 있어야만 되는 것들이 있다 — 목록 카드의 실측 요약, 사이즈 추천, 상품 간 비교.
-- 값이 들어오는 날 표를 다시 켤 수 있어야 한다.
-- ============================================================

ALTER TABLE product
    -- 상품 사진(product_image)과 따로 둔다. 갤러리에 섞이면 손님이 사진을 넘기다
    -- 표를 만나 상품 컷으로 오해한다. 놓이는 자리가 다르므로 보관도 나눈다.
    ADD COLUMN size_chart_media_id UUID REFERENCES media_asset (id) ON DELETE SET NULL,

    -- 대체 텍스트. 표를 이미지로 만든 이상 이 문장이 스크린리더에게는 유일한 정보원이다.
    -- 비어 있으면 그 사용자에게 사이즈 구간이 통째로 존재하지 않는다.
    ADD COLUMN size_chart_alt TEXT;

/*
 * 이미지가 있는데 설명이 없는 상태를 DB 가 막는다.
 *
 * 애플리케이션 검증만 두면 관리자 SQL·배치로 들어오는 경로에서 뚫린다.
 * 반대 방향(설명만 있고 이미지가 없음)은 막지 않는다 — 이미지를 교체하는 중간 상태이고
 * 화면에는 아무것도 그려지지 않아 해가 없다.
 */
ALTER TABLE product
    ADD CONSTRAINT product_size_chart_needs_alt
        CHECK (size_chart_media_id IS NULL OR size_chart_alt IS NOT NULL);

COMMENT ON COLUMN product.size_chart_media_id IS
    '상세 사이즈 차트 이미지. 상품 사진과 별도로 보관한다.';
COMMENT ON COLUMN product.size_chart_alt IS
    '차트 대체 텍스트. 이미지가 있으면 필수(CHECK 로 강제).';
