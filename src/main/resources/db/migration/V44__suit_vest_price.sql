-- ============================================================
-- V44 — 수트 2피스 값은 "베스트 금액만큼 빠지게" (2026-10-31 디자이너 답변)
--
-- V43 은 2피스 판매가를 따로 적었다. 답변: 2피스 = 3피스(판매가) − 베스트 금액. 판매가를 바꾸면 2피스도 따라가야 하므로
-- 2피스 값 대신 베스트 금액을 둔다. 이미 적은 값은 (판매가 − 2피스 값)으로 옮긴다.
--
-- 3피스는 레오네만 있다 — 페리토 수트는 자켓 + 팬츠뿐이라 베스트 금액 없이 판매가가 곧 2피스 값이다(관리자 요청 검증).
-- ============================================================

ALTER TABLE product
    ADD COLUMN vest_price_krw BIGINT CHECK (vest_price_krw IS NULL OR vest_price_krw > 0);

COMMENT ON COLUMN product.vest_price_krw IS '수트 베스트 금액(V44). 2피스 = 판매가 − 이 값. NULL 이면 3피스만 판다(베스트가 있는 수트)';

UPDATE product
SET vest_price_krw = price_krw - two_piece_price_krw
WHERE two_piece_price_krw IS NOT NULL AND price_krw IS NOT NULL AND price_krw > two_piece_price_krw;

ALTER TABLE product DROP COLUMN two_piece_price_krw;
