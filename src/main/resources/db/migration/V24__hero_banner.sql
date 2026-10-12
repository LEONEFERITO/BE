-- ============================================================
-- V24 — 메인 배너 관리 (2026-10-12 "배너 따로 관리, 옵션 선택처럼")
--
-- 배너를 무엇으로 채울지 관리자가 고른다:
--   AUTO     진열 순서 맨 앞 공개 상품(대표 사진이 있는 것) — 지금까지의 방식
--   PRODUCT  관리자가 고른 공개 상품 하나
--   CUSTOM   관리자가 직접 올린 사진(인물 · 액자 둘)과 문구 · 버튼
-- 고른 상품이 비공개가 되거나 CUSTOM 인데 인물 사진이 없으면 손님 화면은 AUTO 로 돌아간다(배너가 비지 않는다).
--
-- 설정은 V2 의 hero_config(단일 행, 지금까지 쓰지 않던 표)를 고쳐 쓴다. 옛 좌우 사진 칸은 쓰는 코드가 없어 지운다.
-- CUSTOM 사진은 사이트 사진 칸(V20)에 셋을 더한다 — 올리고 비우는 방법이 다른 사진들과 같다.
-- ============================================================

ALTER TABLE hero_config
    DROP COLUMN left_media_id,
    DROP COLUMN left_alt,
    DROP COLUMN right_media_id,
    DROP COLUMN right_alt;

ALTER TABLE hero_config
    ADD COLUMN mode        TEXT NOT NULL DEFAULT 'AUTO' CHECK (mode IN ('AUTO', 'PRODUCT', 'CUSTOM')),
    -- 상품이 지워지면 비운다 — 손님 화면은 AUTO 로 돌아간다
    ADD COLUMN product_id  UUID REFERENCES product (id) ON DELETE SET NULL,
    -- CUSTOM 문구. 비어 있으면 그 줄을 그리지 않는다. 길이는 API 가 먼저 막는다
    ADD COLUMN eyebrow     TEXT NOT NULL DEFAULT '' CHECK (char_length(eyebrow) <= 40),
    ADD COLUMN title       TEXT NOT NULL DEFAULT '' CHECK (char_length(title) <= 60),
    ADD COLUMN subtitle    TEXT NOT NULL DEFAULT '' CHECK (char_length(subtitle) <= 80),
    ADD COLUMN cta_label   TEXT NOT NULL DEFAULT '' CHECK (char_length(cta_label) <= 20),
    -- 사이트 안 주소(/로 시작) 또는 https 주소. 비면 전체 제품으로
    ADD COLUMN cta_url     TEXT NOT NULL DEFAULT '' CHECK (char_length(cta_url) <= 300);

COMMENT ON COLUMN hero_config.mode IS 'AUTO 진열 순서 맨 앞 상품 · PRODUCT 지정 상품 · CUSTOM 직접 올린 사진과 문구';
COMMENT ON COLUMN hero_config.product_id IS 'mode=PRODUCT 일 때 배너 상품. 비공개면 손님 화면은 AUTO 로';

-- CUSTOM 배너 사진 세 칸
ALTER TABLE site_image DROP CONSTRAINT site_image_slot_check;
ALTER TABLE site_image ADD CONSTRAINT site_image_slot_check CHECK (slot IN (
    'OFFLINE_SHOP',
    'LINE_LEONE_1', 'LINE_LEONE_2', 'LINE_LEONE_3', 'LINE_LEONE_4', 'LINE_LEONE_5',
    'LINE_FERITO_1', 'LINE_FERITO_2', 'LINE_FERITO_3', 'LINE_FERITO_4', 'LINE_FERITO_5',
    'MAIN_LINE_LEONE', 'MAIN_LINE_FERITO',
    'LOOKBOOK_LEONE_1', 'LOOKBOOK_LEONE_2', 'LOOKBOOK_FERITO_1', 'LOOKBOOK_FERITO_2',
    'BRAND_HERO', 'BRAND_PHOTO_1', 'BRAND_PHOTO_2', 'BRAND_PHOTO_3', 'BRAND_IMPRESSION',
    'HERO_FIGURE', 'HERO_FRAME_LEFT', 'HERO_FRAME_RIGHT'
));

INSERT INTO site_image (slot) VALUES ('HERO_FIGURE'), ('HERO_FRAME_LEFT'), ('HERO_FRAME_RIGHT');
