-- 로그인 · 회원가입 카드의 와인 패널 배경 사진 (2026-10-26 요청 "저 사진 관리자에서 넣을 수 있나").
-- 비어 있으면 사진 없이 와인 그라데이션만. 사진을 올리면 그 위에 와인을 반투명으로 덮는다(FE AuthCard).
ALTER TABLE site_image DROP CONSTRAINT site_image_slot_check;
ALTER TABLE site_image ADD CONSTRAINT site_image_slot_check CHECK (slot IN (
    'OFFLINE_SHOP',
    'LINE_LEONE_1', 'LINE_LEONE_2', 'LINE_LEONE_3', 'LINE_LEONE_4', 'LINE_LEONE_5',
    'LINE_FERITO_1', 'LINE_FERITO_2', 'LINE_FERITO_3', 'LINE_FERITO_4', 'LINE_FERITO_5',
    'MAIN_LINE_LEONE', 'MAIN_LINE_FERITO',
    'LOOKBOOK_LEONE_1', 'LOOKBOOK_LEONE_2', 'LOOKBOOK_FERITO_1', 'LOOKBOOK_FERITO_2',
    'BRAND_HERO', 'BRAND_PHOTO_1', 'BRAND_PHOTO_2', 'BRAND_PHOTO_3', 'BRAND_IMPRESSION',
    'AUTH_PANEL'
));

INSERT INTO site_image (slot) VALUES ('AUTH_PANEL');
