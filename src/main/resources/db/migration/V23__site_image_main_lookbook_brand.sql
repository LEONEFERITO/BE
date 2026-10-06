-- ============================================================
-- V23 — 사이트 사진 칸: 메인 라인 카드 · 룩북 · 브랜드 페이지 (2026-10-06 — "관리자 관리 필요" 로 정리한 칸 전부)
--
--   MAIN_LINE_LEONE · MAIN_LINE_FERITO           메인 "두 가지 라인" 카드. 비면 그 라인 첫 상품 사진
--   LOOKBOOK_LEONE_1 · 2 · LOOKBOOK_FERITO_1 · 2   룩북 컬렉션 컷. 비면 코드의 임시 컷
--   BRAND_HERO · BRAND_PHOTO_1 · 2 · 3 · BRAND_IMPRESSION   브랜드 페이지 사진. 비면 코드의 임시 컷
-- 규칙은 V20 · V22 와 같다 — 칸은 미리 심고, 관리자는 사진만 바꾼다.
-- ============================================================

ALTER TABLE site_image DROP CONSTRAINT site_image_slot_check;
ALTER TABLE site_image ADD CONSTRAINT site_image_slot_check CHECK (slot IN (
    'OFFLINE_SHOP',
    'LINE_LEONE_1', 'LINE_LEONE_2', 'LINE_LEONE_3', 'LINE_LEONE_4', 'LINE_LEONE_5',
    'LINE_FERITO_1', 'LINE_FERITO_2', 'LINE_FERITO_3', 'LINE_FERITO_4', 'LINE_FERITO_5',
    'MAIN_LINE_LEONE', 'MAIN_LINE_FERITO',
    'LOOKBOOK_LEONE_1', 'LOOKBOOK_LEONE_2', 'LOOKBOOK_FERITO_1', 'LOOKBOOK_FERITO_2',
    'BRAND_HERO', 'BRAND_PHOTO_1', 'BRAND_PHOTO_2', 'BRAND_PHOTO_3', 'BRAND_IMPRESSION'
));

INSERT INTO site_image (slot) VALUES
    ('MAIN_LINE_LEONE'), ('MAIN_LINE_FERITO'),
    ('LOOKBOOK_LEONE_1'), ('LOOKBOOK_LEONE_2'), ('LOOKBOOK_FERITO_1'), ('LOOKBOOK_FERITO_2'),
    ('BRAND_HERO'), ('BRAND_PHOTO_1'), ('BRAND_PHOTO_2'), ('BRAND_PHOTO_3'), ('BRAND_IMPRESSION');
