-- ============================================================
-- V52 — 함께 구매 상품 (2026-11-10 고객 "수트 구매 시 착장에 함께 입은 셔츠 · 타이 · 신발도 고를 수 있게,
--        추가 구매 옵션을 만들고 금액을 설정하는 기능을 상품 업로드에")
--
-- 관리자가 상품(주로 수트)에 이미 등록한 다른 상품을 «함께 구매 상품» 으로 붙인다. 손님은 그 상품의 사이즈 · 컬러까지 골라 함께 담는다.
--   product_addon   붙인 상품과 «함께 구매 금액». 금액이 비면(NULL) 그 상품의 원래 판매가.
--   cart_item.addon_of    이 줄이 어느 상품과 함께 담겼는가. 보통 줄은 NULL.
--   order_item.addon_of_name  주문 당시 «함께 구매» 가 적용됐으면 그 상품 이름(복사본) — 주문서 · 관리자 · 메일 · 엑셀에 보인다.
--
-- 함께 구매 금액은 «함께 담긴 그 상품(수트)이 같은 장바구니 · 같은 주문에 있을 때만» 적용한다. 없으면 원래 판매가 — 서버가 다시 계산한다.
-- 재고 · 사이즈 · 교환 · 반품은 붙인 상품 자신의 것을 그대로 따른다.
-- ============================================================

CREATE TABLE product_addon (
    product_id        UUID    NOT NULL REFERENCES product (id) ON DELETE CASCADE,
    sort_order        INT     NOT NULL CHECK (sort_order >= 0),
    addon_product_id  UUID    NOT NULL REFERENCES product (id) ON DELETE CASCADE,
    price_krw         BIGINT  CHECK (price_krw IS NULL OR price_krw > 0),
    PRIMARY KEY (product_id, sort_order),
    CHECK (addon_product_id <> product_id)
);

COMMENT ON TABLE product_addon IS '함께 구매 상품(V52) — 붙인 상품과 함께 구매 금액(NULL 이면 원래 판매가)';

ALTER TABLE cart_item ADD COLUMN addon_of UUID REFERENCES product (id) ON DELETE SET NULL;
COMMENT ON COLUMN cart_item.addon_of IS '함께 담은 상품(V52). 보통 줄은 NULL';

-- 같은 셔츠라도 «수트와 함께» 담은 줄과 따로 담은 줄은 다른 줄이다
ALTER TABLE cart_item DROP CONSTRAINT uq_cart_item_line;
CREATE UNIQUE INDEX uq_cart_item_line ON cart_item
    (member_id, product_id, size, color, COALESCE(addon_of, '00000000-0000-0000-0000-000000000000'::uuid));

ALTER TABLE order_item ADD COLUMN addon_of_name TEXT CHECK (addon_of_name IS NULL OR char_length(addon_of_name) <= 120);
COMMENT ON COLUMN order_item.addon_of_name IS '함께 구매 금액이 적용된 줄이면 함께 산 상품 이름(V52, 복사본). 아니면 NULL';
