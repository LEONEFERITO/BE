-- ============================================================
-- V26 — 메인 배너 "이미지 한 장" (2026-10-13 "누끼 말고 이미지 하나로 히어로 전체를 덮게")
--
-- 배너 종류에 IMAGE 를 더한다:
--   PRODUCT  누끼 — 상품 정보 가져오기
--   CUSTOM   누끼 — 직접 등록(인물 · 액자 둘)
--   IMAGE    이미지 한 장이 히어로 화면 전체를 덮는다. 문구는 고르면 사진 위에, 버튼은 원하는 주소로
--
-- IMAGE 의 큰 사진은 figure_media_id 를 그대로 쓴다(배너의 "주 사진" 칸).
-- 휴대폰은 화면이 세로라 가로 사진을 덮으면 많이 잘린다 — 세로 사진을 따로 올릴 수 있게 mobile_* 칸을 더한다.
-- 비우면 큰 사진을 휴대폰에서도 가운데 기준으로 잘라 쓴다.
-- ============================================================

ALTER TABLE hero_banner DROP CONSTRAINT hero_banner_kind_check;
ALTER TABLE hero_banner ADD CONSTRAINT hero_banner_kind_check CHECK (kind IN ('PRODUCT', 'CUSTOM', 'IMAGE'));

ALTER TABLE hero_banner
    ADD COLUMN mobile_media_id UUID REFERENCES media_asset (id) ON DELETE SET NULL,
    ADD COLUMN mobile_alt      TEXT NOT NULL DEFAULT '' CHECK (char_length(mobile_alt) <= 200);

COMMENT ON COLUMN hero_banner.mobile_media_id IS 'IMAGE 배너의 휴대폰용 세로 사진. 비우면 큰 사진(figure)을 잘라 쓴다';
