-- 세부 분류 (2026-10-06 고객 사이트 구조표)
--
-- 트라우저는 핏(REGULAR · STRAIGHT · FLARE), 신발은 종류(OXFORD · LOAFER)로 한 번 더 나눈다.
-- 셔츠 · 자켓 · 수트의 Classic / Athletic 은 line(LEONE · FERITO)이 이미 맡고 있어 여기 넣지 않는다.
-- 없어도 된다(NULL) — 기존 상품과 세부 분류가 없는 분류는 비워 둔다.
--
-- 분류와 짝이 맞지 않는 값(셔츠에 FLARE 등)은 DB 도 막는다. 요청 검증 · 엔티티가 먼저 막지만 마지막 방어선이다.

ALTER TABLE product ADD COLUMN style VARCHAR(20);

ALTER TABLE product ADD CONSTRAINT product_style_check CHECK (
    style IS NULL
    OR (category = 'TROUSERS' AND style IN ('REGULAR', 'STRAIGHT', 'FLARE'))
    OR (category = 'SHOES' AND style IN ('OXFORD', 'LOAFER'))
);

COMMENT ON COLUMN product.style IS '세부 분류. TROUSERS: REGULAR/STRAIGHT/FLARE, SHOES: OXFORD/LOAFER, 그 외 NULL';
