-- ============================================================
-- V27 — 분류 메뉴 순서 (2026-10-13 대표님 "카테고리 순서도 변경할 수 있냐")
--
-- 헤더의 분류 메뉴(SHIRTS · TROUSERS · JACKET · SUIT · FOOTWEAR · ACCESSORIES)와 모바일 메뉴의 순서.
-- 관리자 › 분류 순서에서 바꾸고, 저장하면 손님 화면을 다시 만든다. 분류마다 한 행.
-- 처음 값은 지금까지의 순서(2026-10-06 고객 사이트 구조표)다.
-- 분류가 늘면: 이 CHECK 를 고치는 마이그레이션 + 행 INSERT. 행이 없는 분류는 코드가 맨 뒤에 붙인다.
-- ============================================================

CREATE TABLE category_order (
    category   TEXT        PRIMARY KEY
                           CHECK (category IN ('SUIT', 'JACKET', 'TROUSERS', 'SHIRT', 'SHOES', 'ACCESSORIES')),
    sort_order INTEGER     NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TRIGGER trg_category_order_updated_at
    BEFORE UPDATE ON category_order
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

COMMENT ON TABLE category_order IS '헤더 분류 메뉴 순서. sort_order 작은 것이 앞';

INSERT INTO category_order (category, sort_order) VALUES
    ('SHIRT', 10),
    ('TROUSERS', 20),
    ('JACKET', 30),
    ('SUIT', 40),
    ('SHOES', 50),
    ('ACCESSORIES', 60);
