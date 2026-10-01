-- ============================================================
-- V11 — 상품정보제공고시(의류) 항목
--
-- 「전자상거래 등에서의 상품 등의 정보제공에 관한 고시」 의류 항목:
--   제품 소재 · 색상 · 치수 · 제조자(수입자) · 제조국 · 세탁방법 및 취급시 주의사항 ·
--   제조연월 · 품질보증기준 · A/S 책임자와 전화번호
--
-- 이미 있는 것으로 채우는 항목 (새 컬럼을 만들지 않는다 — 같은 값을 두 번 적게 된다):
--   소재 = fabric (원단 정보 및 혼용률) · 세탁방법 = care · 치수 = SKU 사이즈 목록
-- 브랜드 공통이라 상품마다 적지 않는 항목 (화면이 사업자 정보로 채운다):
--   품질보증기준 · A/S 책임자와 전화번호
--
-- 여기서는 상품마다 다른 넷만 더한다. 공개하려면 넷 다 있어야 한다 (Product.missingForPublish).
-- 법적 고지라 텍스트로 둔다 — 이미지로만 넣으면 고시 위반이고 스크린리더도 못 읽는다.
-- ============================================================

ALTER TABLE product
    ADD COLUMN color             TEXT CHECK (color IS NULL OR length(color) <= 100),
    ADD COLUMN manufacturer      TEXT CHECK (manufacturer IS NULL OR length(manufacturer) <= 100),
    ADD COLUMN country_of_origin TEXT CHECK (country_of_origin IS NULL OR length(country_of_origin) <= 100),
    -- 제조연월. "2026년 9월" 처럼 사람이 쓰는 표기를 그대로 받는다. 시즌 생산이면 "2026년 9월~10월".
    ADD COLUMN manufactured_on   TEXT CHECK (manufactured_on IS NULL OR length(manufactured_on) <= 100);

COMMENT ON COLUMN product.color IS '상품정보제공고시 — 색상';
COMMENT ON COLUMN product.manufacturer IS '상품정보제공고시 — 제조자(수입자)';
COMMENT ON COLUMN product.country_of_origin IS '상품정보제공고시 — 제조국';
COMMENT ON COLUMN product.manufactured_on IS '상품정보제공고시 — 제조연월 (사람이 쓰는 표기)';
