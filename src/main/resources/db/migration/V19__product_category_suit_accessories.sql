-- ─────────────────────────────────────────────────────────────
-- V19 — 제품 분류에 SUIT · ACCESSORIES 추가
--
-- 2026-10-05 고객 디자인 가이드: 헤더 내비와 카테고리 페이지가 기존 몰의 여섯 분류
-- (Suit · Jacket · Trousers · Shirts · Shoes · Accessories)를 따른다. V3 의 CHECK 제약은
-- 네 분류만 받아서, 그대로면 관리자가 수트 상품을 등록할 때 DB 가 거부한다.
--
-- ── 제약 이름을 가정하지 않는다 ─────────────────────────────
-- V3 는 CHECK 를 이름 없이 컬럼에 붙였다. PostgreSQL 이 붙인 이름은 보통 product_category_check 지만
-- "보통" 에 마이그레이션을 걸지 않는다 — 이름이 다르면 DROP ... IF EXISTS 는 조용히 아무것도 안 하고,
-- 옛 제약이 남아서 새 분류가 계속 거부된다(그리고 그건 누가 수트를 등록하는 날에야 드러난다).
-- 그래서 정의에 category 가 들어간 CHECK 를 카탈로그에서 찾아 지우고, 못 찾으면 **실패시킨다.**
-- ─────────────────────────────────────────────────────────────

DO $$
DECLARE
    found_name text;
    dropped    integer := 0;
BEGIN
    FOR found_name IN
        SELECT conname
          FROM pg_constraint
         WHERE conrelid = 'product'::regclass
           AND contype = 'c'
           AND pg_get_constraintdef(oid) LIKE '%category%'
    LOOP
        EXECUTE format('ALTER TABLE product DROP CONSTRAINT %I', found_name);
        dropped := dropped + 1;
    END LOOP;

    IF dropped = 0 THEN
        RAISE EXCEPTION 'product.category 의 CHECK 제약을 찾지 못했다 — V3 이후 스키마가 예상과 다르다';
    END IF;
END
$$;

-- 이번에는 이름을 붙인다. 다음에 분류를 늘릴 때 이 이름으로 바로 고칠 수 있다.
ALTER TABLE product
    ADD CONSTRAINT product_category_check
    CHECK (category IN ('SUIT', 'JACKET', 'TROUSERS', 'SHIRT', 'SHOES', 'ACCESSORIES'));
