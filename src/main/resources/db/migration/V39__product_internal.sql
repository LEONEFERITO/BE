-- 관리자 전용 상품 정보 (2026-10-27 고객 엑셀 양식 "브랜드 제품 일괄 등록" — 관리용상품명 · 원단공급사 · 원단컬렉션 · 원단품번 · 공임처 · 원가).
-- 손님 API 로는 나가지 않는다(상품 응답 ProductResponse 에 없다). 관리자 상품 화면 · 일괄 등록에서만 쓴다.
ALTER TABLE product
    ADD COLUMN admin_name        VARCHAR(80),
    ADD COLUMN fabric_supplier   VARCHAR(100),
    ADD COLUMN fabric_collection VARCHAR(100),
    ADD COLUMN fabric_code       VARCHAR(100),
    ADD COLUMN workshop          VARCHAR(100),
    ADD COLUMN cost_krw          BIGINT CHECK (cost_krw >= 0);

COMMENT ON COLUMN product.admin_name IS '관리용 상품명(내부). 손님에게 안 보인다';
COMMENT ON COLUMN product.workshop IS '공임처(봉제 공장)';
COMMENT ON COLUMN product.cost_krw IS '원가(원). 내부용';
