-- ============================================================
-- V1 — 베이스라인
--
-- Phase 1(뼈대) 단계의 마이그레이션이다. 도메인 테이블은 아직 없다.
-- 상품/SKU/실측표 등은 Phase 2 에서 V2 부터 추가한다.
--
-- 여기에는 "어느 도메인에도 속하지 않지만 모든 테이블이 쓸 것" 만 둔다.
-- ============================================================

-- 모든 테이블이 갖게 될 updated_at 을 자동으로 갱신하는 공용 트리거 함수.
--
-- 왜 애플리케이션(JPA @PreUpdate)이 아니라 DB 트리거인가:
--   관리자 화면이든 배치 SQL 이든 마이그레이션이든, 어디서 UPDATE 가 들어와도
--   updated_at 이 반드시 맞는다. 애플리케이션에만 두면 DB 를 직접 만진 변경이 누락된다.
--
-- Phase 2 에서 테이블마다 이렇게 붙인다:
--   CREATE TRIGGER trg_product_updated_at
--     BEFORE UPDATE ON product
--     FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE OR REPLACE FUNCTION set_updated_at()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    -- 실제로 바뀐 게 없으면 updated_at 도 건드리지 않는다.
    -- (같은 값으로 UPDATE 하는 요청까지 수정 시각을 올려버리면 이력이 지저분해진다)
    IF NEW IS NOT DISTINCT FROM OLD THEN
        RETURN NEW;
    END IF;

    NEW.updated_at = now();
    RETURN NEW;
END;
$$;

COMMENT ON FUNCTION set_updated_at() IS
    'BEFORE UPDATE 트리거용. 행이 실제로 변경된 경우에만 updated_at 을 현재 시각으로 갱신한다.';
