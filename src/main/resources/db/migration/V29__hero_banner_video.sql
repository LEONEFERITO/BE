-- ============================================================
-- V29 — 메인 배너 "영상" (2026-10-13 "영상 배너 직접 추가")
--
-- 배너 종류에 VIDEO 를 더한다. 이미지 한 장(IMAGE)처럼 히어로 화면 전체를 덮고, 소리 없이 반복 재생된다.
--   figure_media_id   PC 영상 (MP4 · WebM, 30MB 까지 — MediaService.uploadVideo)
--   mobile_media_id   휴대폰 세로 영상 (선택. 비우면 PC 영상을 가운데로 잘라 쓴다)
--   poster_media_id · mobile_poster_media_id
--                     첫 장면 사진 — 영상을 받는 동안, 움직임을 줄인 사용자에게 보인다.
--                     관리자 화면이 영상을 고를 때 첫 장면을 잘라 자동으로 올린다(선택)
--
-- 영상 · 사진 자리가 뒤바뀌지 않는지는 서버(SiteImageService)가 본다 — media_asset.content_type 이 video/ 인가.
-- ============================================================

ALTER TABLE hero_banner DROP CONSTRAINT hero_banner_kind_check;
ALTER TABLE hero_banner ADD CONSTRAINT hero_banner_kind_check CHECK (kind IN ('PRODUCT', 'CUSTOM', 'IMAGE', 'VIDEO'));

ALTER TABLE hero_banner
    ADD COLUMN poster_media_id        UUID REFERENCES media_asset (id) ON DELETE SET NULL,
    ADD COLUMN mobile_poster_media_id UUID REFERENCES media_asset (id) ON DELETE SET NULL;

COMMENT ON COLUMN hero_banner.figure_media_id IS 'CUSTOM 의 인물 사진 · IMAGE 의 큰 사진 · VIDEO 의 PC 영상';
COMMENT ON COLUMN hero_banner.mobile_media_id IS 'IMAGE 의 휴대폰용 세로 사진 · VIDEO 의 휴대폰 영상. 비우면 figure 를 잘라 쓴다';
COMMENT ON COLUMN hero_banner.poster_media_id IS 'VIDEO 배너의 첫 장면 사진(영상을 받는 동안 · 움직임을 줄인 사용자)';
COMMENT ON COLUMN hero_banner.mobile_poster_media_id IS 'VIDEO 배너 휴대폰 영상의 첫 장면 사진';
