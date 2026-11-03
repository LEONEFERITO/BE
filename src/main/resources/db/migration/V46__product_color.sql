-- ============================================================
-- V46 — 컬러 옵션 (2026-11-03 고객 "컬러별로 컬러명칭 적을 수 있게, 여러 컬러 선택, 컬러와 사이즈 선택 후 각각 수량")
--
-- 지금까지는 색마다 상품을 따로 만들었다(복제). 이제 한 상품에 컬러 여러 개를 두고 손님이 고른다.
--   product_color  상품의 컬러 목록 — 이름(관리자가 적는다)과 동그라미 색(#RRGGBB). 순서는 관리자가 놓은 대로.
--   cart_item.color · order_item.color  고른 컬러 이름. 컬러가 없는 상품은 빈 값('').
-- 재고는 지금처럼 사이즈별이다(컬러끼리 나눠 세지 않는다).
-- 장바구니의 «같은 줄» 은 상품 · 사이즈 · 컬러가 모두 같을 때다 — 다시 담으면 수량이 는다.
-- ============================================================

CREATE TABLE product_color (
    product_id  UUID    NOT NULL REFERENCES product (id) ON DELETE CASCADE,
    sort_order  INT     NOT NULL CHECK (sort_order >= 0),
    -- 손님 화면 · 장바구니 · 주문서에 그대로 나간다. 구성 줄 구분 글자(/ ·)는 쓰지 않는다
    name        TEXT    NOT NULL CHECK (char_length(name) BETWEEN 1 AND 20 AND name !~ '[/·]'),
    hex         TEXT    NOT NULL CHECK (hex ~ '^#[0-9A-Fa-f]{6}$'),
    -- 이름이 겹치지 않는지는 저장 요청에서 본다(AdminProductRequests) — 순서를 바꿀 때 줄을 하나씩 고치므로
    -- DB 유니크를 걸면 이름을 맞바꾸는 순간 서로 부딪힌다
    PRIMARY KEY (product_id, sort_order)
);

COMMENT ON TABLE product_color IS '상품 컬러 옵션 — 이름 · 색. 재고는 사이즈별(product_sku)이다.';

ALTER TABLE cart_item ADD COLUMN color TEXT NOT NULL DEFAULT '' CHECK (char_length(color) <= 20);
ALTER TABLE cart_item DROP CONSTRAINT cart_item_member_id_product_id_size_key;
ALTER TABLE cart_item ADD CONSTRAINT uq_cart_item_line UNIQUE (member_id, product_id, size, color);

ALTER TABLE order_item ADD COLUMN color TEXT NOT NULL DEFAULT '' CHECK (char_length(color) <= 20);

COMMENT ON COLUMN cart_item.color IS '고른 컬러 이름(V46). 컬러가 없는 상품은 빈 값';
COMMENT ON COLUMN order_item.color IS '주문 당시 컬러 이름(V46) — 복사본. 컬러가 없는 상품은 빈 값';
