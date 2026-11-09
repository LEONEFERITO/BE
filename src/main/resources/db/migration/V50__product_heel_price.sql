-- ============================================================
-- V50 — 굽높이 추가 금액 (2026-11-09 고객 "굽높이 추가 옵션에 추가 금액이 붙게, 금액은 직접 작성")
--
-- 굽높이 옵션(V49)을 켠 상품에서 +1cm ~ +6cm 단계마다 관리자가 추가 금액을 적는다. 기본 굽은 언제나 0원.
--   sort_order 0 = +1cm … 5 = +6cm. 줄이 없는 단계는 0원.
-- 한 벌 값 = 판매가 + 고른 굽의 추가 금액. 장바구니 · 주문서는 서버가 다시 계산하므로 화면 값은 믿지 않는다.
-- ============================================================

CREATE TABLE product_heel_price (
    product_id  UUID    NOT NULL REFERENCES product (id) ON DELETE CASCADE,
    sort_order  INT     NOT NULL CHECK (sort_order BETWEEN 0 AND 5),
    price_krw   BIGINT  NOT NULL CHECK (price_krw BETWEEN 0 AND 10000000),
    PRIMARY KEY (product_id, sort_order)
);

COMMENT ON TABLE product_heel_price IS '굽높이 단계별 추가 금액(V50). sort_order 0 = +1cm … 5 = +6cm. 없으면 0원';
