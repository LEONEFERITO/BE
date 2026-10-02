-- ============================================================
-- V17 — 상세 이미지 (STORY)
--
-- 한국 쇼핑몰식 긴 상세페이지 이미지. 여러 장을 올리고, 손님 화면은 간격 없이 세로로 이어 붙인다.
-- 메인 사진(대표 · 착용 · 디테일 · 누끼)과 종류로 가른다 — 같은 표에 두고 kind 만 다르다.
-- 장수 제한(상세 30 · 나머지 종류마다 1)은 서버 요청 검증이 한다(AdminProductRequests).
-- ============================================================

ALTER TABLE product_image DROP CONSTRAINT product_image_kind_check;
ALTER TABLE product_image
    ADD CONSTRAINT product_image_kind_check CHECK (kind IN ('MAIN', 'WORN', 'DETAIL', 'CUTOUT', 'STORY'));

COMMENT ON COLUMN product_image.kind IS 'MAIN 대표 / WORN 착용샷 / DETAIL 디테일컷 / CUTOUT 누끼샷 / STORY 상세 이미지(긴 이미지, 여러 장)';
