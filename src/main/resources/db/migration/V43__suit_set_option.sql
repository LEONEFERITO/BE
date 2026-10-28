-- ============================================================
-- V43 — 수트 구성 옵션 (2026-10-28 디자이너 "3피스 / 2피스 구성 선택 + 자켓 · 베스트 · 팬츠 사이즈 따로")
--
-- 사이즈 줄(product_sku)에 조각(piece)을 붙인다. 조각이 붙은 사이즈가 있는 상품은 "수트 구성" 상품이다:
--   3피스 = 자켓 + 베스트 + 팬츠 — 값은 판매가(price_krw)
--   2피스 = 자켓 + 팬츠           — 값은 2피스 판매가(two_piece_price_krw). 비우면 2피스는 팔지 않는다
-- 조각이 없는 사이즈(지금까지의 모든 상품)는 그대로다.
--
-- 장바구니 · 주문의 size 칸에는 고른 구성을 한 줄로 적는다 — "3피스 · 자켓 L / 베스트 M / 팬츠 L".
-- 주문서 · 관리자 주문 · 엑셀 · 메일이 그 줄을 그대로 보여 준다. 그래서 길이 제한을 늘린다(20 → 80).
-- ============================================================

ALTER TABLE product
    ADD COLUMN two_piece_price_krw BIGINT CHECK (two_piece_price_krw IS NULL OR two_piece_price_krw > 0);

COMMENT ON COLUMN product.two_piece_price_krw IS '수트 2피스(자켓 + 팬츠) 판매가. NULL 이면 2피스는 팔지 않는다(V43)';

ALTER TABLE product_sku
    ADD COLUMN piece TEXT CHECK (piece IS NULL OR piece IN ('JACKET', 'VEST', 'PANTS'));

COMMENT ON COLUMN product_sku.piece IS '수트 조각(JACKET · VEST · PANTS). NULL 이면 보통 사이즈(V43)';

-- 같은 상품 안에서 (조각, 사이즈) 가 하나 — 자켓 L 과 팬츠 L 이 함께 있을 수 있다
ALTER TABLE product_sku DROP CONSTRAINT uq_product_sku_size;
CREATE UNIQUE INDEX uq_product_sku_piece_size ON product_sku (product_id, COALESCE(piece, ''), size);

-- 구성 한 줄이 들어가도록 — "3피스 · 자켓 2XL / 베스트 2XL / 팬츠 2XL" 도 40자가 안 된다
ALTER TABLE cart_item DROP CONSTRAINT cart_item_size_check;
ALTER TABLE cart_item ADD CONSTRAINT cart_item_size_check CHECK (length(size) BETWEEN 1 AND 80);

ALTER TABLE return_request_item DROP CONSTRAINT return_request_item_exchange_size_check;
ALTER TABLE return_request_item ADD CONSTRAINT return_request_item_exchange_size_check
    CHECK (exchange_size IS NULL OR length(exchange_size) BETWEEN 1 AND 80);
