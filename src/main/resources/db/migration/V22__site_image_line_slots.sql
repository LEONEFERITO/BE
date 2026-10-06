-- ============================================================
-- V22 — 사이트 사진 칸: 라인 페이지 다섯 칸 (2026-10-06 고객 요청 — 관리자에서 올리게)
--
-- /line/leone/ · /line/ferito/ 위쪽의 사진 다섯 칸("페인포인트와 니즈 포인트"). 왼쪽부터 1~5.
-- 비어 있으면 손님 화면은 그 라인 상품 사진으로 메우고, 그것도 없으면 가이드의 회색 칸이다.
-- 칸은 V20 과 같은 규칙으로 미리 심어 둔다 — 관리자는 있는 칸의 사진만 바꾼다.
-- ============================================================

ALTER TABLE site_image DROP CONSTRAINT site_image_slot_check;
ALTER TABLE site_image ADD CONSTRAINT site_image_slot_check CHECK (slot IN (
    'OFFLINE_SHOP',
    'LINE_LEONE_1', 'LINE_LEONE_2', 'LINE_LEONE_3', 'LINE_LEONE_4', 'LINE_LEONE_5',
    'LINE_FERITO_1', 'LINE_FERITO_2', 'LINE_FERITO_3', 'LINE_FERITO_4', 'LINE_FERITO_5'
));

INSERT INTO site_image (slot) VALUES
    ('LINE_LEONE_1'), ('LINE_LEONE_2'), ('LINE_LEONE_3'), ('LINE_LEONE_4'), ('LINE_LEONE_5'),
    ('LINE_FERITO_1'), ('LINE_FERITO_2'), ('LINE_FERITO_3'), ('LINE_FERITO_4'), ('LINE_FERITO_5');
