-- 제작 기간을 범위로 (2026-10-27 대표님 예시 "14~18") — lead_time_days 는 그대로 '최대' 로 쓰고, '최소' 칸을 더한다.
-- 발송 예정 · 주문 스냅샷은 지금처럼 최대값(lead_time_days)으로 계산한다 — 손님에게 늦게 약속하지 않게.
-- 화면은 최소가 있으면 "14~18일", 없으면 "18일".
ALTER TABLE product
    ADD COLUMN lead_time_min_days SMALLINT CHECK (lead_time_min_days BETWEEN 1 AND 365),
    ADD CONSTRAINT product_lead_time_range_check CHECK (lead_time_min_days IS NULL OR lead_time_days IS NULL
        OR lead_time_min_days <= lead_time_days);

-- 엑셀 양식의 "소분류" — 자유 태그(관리자 전용, 2026-10-27 결정). 사이트 메뉴는 대분류(category)가 정한다.
ALTER TABLE product ADD COLUMN sub_category VARCHAR(40);

COMMENT ON COLUMN product.lead_time_min_days IS '제작 기간 최소(일). lead_time_days 가 최대. 없으면 한 값';
COMMENT ON COLUMN product.sub_category IS '소분류(자유 태그, 관리자 전용)';
