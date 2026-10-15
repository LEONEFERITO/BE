-- ============================================================
-- V32 — 배송비 면제 스위치 (2026-10-15 "테스트 하려고 하는데 배송비 면제 버튼 하나만")
--
-- 관리자가 켜면 그동안의 모든 주문이 배송비 0원(제주 · 도서산간 추가도 0원)이다. 결제 테스트 · 무료배송 행사용.
-- 한 줄짜리 표다 — 켜 둔 채 잊으면 손해라 관리자 화면이 켜져 있는 동안 계속 경고한다.
-- ============================================================

CREATE TABLE shipping_waiver (
    id          SMALLINT    PRIMARY KEY CHECK (id = 1),
    waived      BOOLEAN     NOT NULL DEFAULT FALSE,
    updated_by  TEXT,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

COMMENT ON TABLE shipping_waiver IS '배송비 면제 스위치 — 한 줄. 켜면 모든 주문 배송비 0원.';

CREATE TRIGGER trg_shipping_waiver_updated_at
    BEFORE UPDATE ON shipping_waiver
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

INSERT INTO shipping_waiver (id, waived) VALUES (1, FALSE);
