-- ============================================================
-- V25 — 메인 배너를 목록으로 (2026-10-12 "왜 배너가 하나밖에 안 돼, 리스트로")
--
-- V24 는 배너 설정 한 행(hero_config)이었다. 이제 배너를 여러 개 등록하고, 켜 둔 것들이
-- 순서대로 메인 첫 화면에서 넘어간다. 배너 한 개 = 한 행:
--   PRODUCT  공개 상품 하나 — 상품의 누끼 · 착용 · 디테일 사진. 문구를 적으면 상품 이름 · 가격 대신 그 문구
--   CUSTOM   직접 올린 사진(인물 · 액자 둘)과 문구 · 버튼
-- 손님 화면에 나가는 배너 = 켜 둔(visible) 것 중 상품이 공개 중이거나 인물 사진이 있는 것.
-- 하나도 없으면 진열 순서 맨 앞 상품이 자동으로 나간다(배너가 비지 않는다).
--
-- 사진은 배너 행이 직접 가진다 — 배너마다 사진이 다르다. V24 의 사이트 사진 칸 HERO_* 는 걷는다.
-- V24 에 저장된 설정이 있으면 첫 배너로 옮긴다.
-- ============================================================

CREATE TABLE hero_banner (
    id                   UUID        PRIMARY KEY,
    kind                 TEXT        NOT NULL CHECK (kind IN ('PRODUCT', 'CUSTOM')),
    -- 상품이 지워지면 그 상품 배너도 지운다 — 상품 없는 상품 배너는 뜻이 없다
    product_id           UUID        REFERENCES product (id) ON DELETE CASCADE,
    -- 문구. 비어 있으면 그 줄을 그리지 않는다(상품 배너는 상품 이름 · 가격으로). 길이는 API 가 먼저 막는다
    eyebrow              TEXT        NOT NULL DEFAULT '' CHECK (char_length(eyebrow) <= 40),
    title                TEXT        NOT NULL DEFAULT '' CHECK (char_length(title) <= 60),
    subtitle             TEXT        NOT NULL DEFAULT '' CHECK (char_length(subtitle) <= 80),
    cta_label            TEXT        NOT NULL DEFAULT '' CHECK (char_length(cta_label) <= 20),
    -- 사이트 안 주소(/로 시작) 또는 https 주소. 비면 상품 배너는 상품 페이지, 직접 올리기는 전체 제품
    cta_url              TEXT        NOT NULL DEFAULT '' CHECK (char_length(cta_url) <= 300),
    -- 직접 올리기 사진. 사진이 지워지면 비운다 — 인물 사진이 없는 배너는 손님 화면에 나가지 않는다
    figure_media_id      UUID        REFERENCES media_asset (id) ON DELETE SET NULL,
    figure_alt           TEXT        NOT NULL DEFAULT '' CHECK (char_length(figure_alt) <= 200),
    frame_left_media_id  UUID        REFERENCES media_asset (id) ON DELETE SET NULL,
    frame_left_alt       TEXT        NOT NULL DEFAULT '' CHECK (char_length(frame_left_alt) <= 200),
    frame_right_media_id UUID        REFERENCES media_asset (id) ON DELETE SET NULL,
    frame_right_alt      TEXT        NOT NULL DEFAULT '' CHECK (char_length(frame_right_alt) <= 200),
    visible              BOOLEAN     NOT NULL DEFAULT TRUE,
    sort_order           INTEGER     NOT NULL,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT hero_banner_product_needed CHECK (kind <> 'PRODUCT' OR product_id IS NOT NULL)
);

CREATE INDEX idx_hero_banner_order ON hero_banner (sort_order, created_at);

CREATE TRIGGER trg_hero_banner_updated_at
    BEFORE UPDATE ON hero_banner
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

COMMENT ON TABLE hero_banner IS '메인 배너 목록. 켜 둔 것이 sort_order 순서로 넘어간다. 없으면 진열 순서 맨 앞 상품';

-- ── V24 설정 옮기기 ──
INSERT INTO hero_banner (id, kind, product_id, eyebrow, title, subtitle, cta_label, cta_url, sort_order)
SELECT gen_random_uuid(), 'PRODUCT', h.product_id, h.eyebrow, h.title, h.subtitle, h.cta_label, h.cta_url, 10
FROM hero_config h
WHERE h.mode = 'PRODUCT' AND h.product_id IS NOT NULL;

INSERT INTO hero_banner (id, kind, eyebrow, title, subtitle, cta_label, cta_url,
                         figure_media_id, figure_alt, frame_left_media_id, frame_left_alt,
                         frame_right_media_id, frame_right_alt, sort_order)
SELECT gen_random_uuid(), 'CUSTOM', h.eyebrow, h.title, h.subtitle, h.cta_label, h.cta_url,
       f.media_id, f.alt, l.media_id, l.alt, r.media_id, r.alt, 10
FROM hero_config h
JOIN site_image f ON f.slot = 'HERO_FIGURE' AND f.media_id IS NOT NULL
JOIN site_image l ON l.slot = 'HERO_FRAME_LEFT'
JOIN site_image r ON r.slot = 'HERO_FRAME_RIGHT'
WHERE h.mode = 'CUSTOM';

DROP TABLE hero_config;

-- ── 사이트 사진 칸 HERO_* 걷기 (V24 이전으로) ──
DELETE FROM site_image WHERE slot IN ('HERO_FIGURE', 'HERO_FRAME_LEFT', 'HERO_FRAME_RIGHT');

ALTER TABLE site_image DROP CONSTRAINT site_image_slot_check;
ALTER TABLE site_image ADD CONSTRAINT site_image_slot_check CHECK (slot IN (
    'OFFLINE_SHOP',
    'LINE_LEONE_1', 'LINE_LEONE_2', 'LINE_LEONE_3', 'LINE_LEONE_4', 'LINE_LEONE_5',
    'LINE_FERITO_1', 'LINE_FERITO_2', 'LINE_FERITO_3', 'LINE_FERITO_4', 'LINE_FERITO_5',
    'MAIN_LINE_LEONE', 'MAIN_LINE_FERITO',
    'LOOKBOOK_LEONE_1', 'LOOKBOOK_LEONE_2', 'LOOKBOOK_FERITO_1', 'LOOKBOOK_FERITO_2',
    'BRAND_HERO', 'BRAND_PHOTO_1', 'BRAND_PHOTO_2', 'BRAND_PHOTO_3', 'BRAND_IMPRESSION'
));
