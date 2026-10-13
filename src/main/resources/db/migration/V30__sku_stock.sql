-- ============================================================
-- V30 — 사이즈별 재고 (2026-10-13 "사이즈별 재고 설정 가능하게")
--
-- product_sku.stock — 남은 수량.
--   NULL  세지 않는다(주문 후 제작 — 지금까지의 방식, 기존 사이즈는 전부 이 값으로 시작한다)
--   0     품절. 손님 화면에 「품절」, 장바구니 · 주문서 · 교환 대상에서 빠진다
--   N     N벌까지 판다. 결제가 승인될 때 줄고(행을 잠그고), 발송 전 취소면 돌아온다(OrderService)
--
-- is_orderable(제작 가능 여부)은 그대로 둔다 — 「만들 수 없는 사이즈」와 「다 팔린 사이즈」는 다른 뜻이다.
-- ============================================================

ALTER TABLE product_sku
    ADD COLUMN stock INTEGER CHECK (stock IS NULL OR stock >= 0);

COMMENT ON COLUMN product_sku.stock IS '남은 수량. NULL=세지 않음(주문 후 제작), 0=품절. 결제 승인 때 줄고 발송 전 취소 때 돌아온다';
COMMENT ON COLUMN product_sku.is_orderable IS '제작 가능 여부. 수량은 stock(V30)';
