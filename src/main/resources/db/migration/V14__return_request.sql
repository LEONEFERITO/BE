-- ============================================================
-- V14 — 교환 · 반품
--
-- 흐름 (손님이 신청 → 관리자가 처리):
--   REQUESTED → APPROVED → COLLECTED → COMPLETED
--       │           │          │
--       ├→ WITHDRAWN (손님이 승인 전에 철회)
--       └───────────┴──────────┴→ REJECTED (관리자 거절 · 검수 불합격)
--
-- 원칙:
--   · 배송 완료된 주문만 신청한다. 신청 기간은 사유에 따라 다르다(ReturnPolicy).
--   · 단순 변심·사이즈 교환을 받을지는 주문 제작품이라 고객 정책에 달렸다 — TODO(고객확인).
--     그래서 신청은 받고, 받을지 말지는 관리자가 승인·거절로 정한다. 시스템이 미리 막지 않는다.
--   · 반품 환불은 토스 부분 취소로 한다. 환불액은 관리자가 완료할 때 넣는다
--     (왕복 배송비를 누가 내는지 아직 없다 — TODO(고객확인)). 주문 금액을 넘을 수 없다.
--   · 지우지 않는다. 주문처럼 계약 기록이다.
-- ============================================================

CREATE TABLE return_request (
    id                      UUID        PRIMARY KEY,
    order_id                UUID        NOT NULL REFERENCES orders (id),
    member_id               UUID        NOT NULL REFERENCES member (id),
    type                    TEXT        NOT NULL CHECK (type IN ('EXCHANGE', 'RETURN')),
    reason                  TEXT        NOT NULL
                            CHECK (reason IN ('SIZE', 'CHANGE_OF_MIND', 'DEFECT', 'WRONG_ITEM', 'OTHER')),
    detail                  TEXT        CHECK (detail IS NULL OR length(detail) <= 1000),
    status                  TEXT        NOT NULL
                            CHECK (status IN ('REQUESTED', 'APPROVED', 'COLLECTED', 'COMPLETED',
                                              'REJECTED', 'WITHDRAWN')),
    -- 관리자가 손님에게 남기는 안내 (회수 방법 등). 손님 화면에 보인다.
    admin_note              TEXT        CHECK (admin_note IS NULL OR length(admin_note) <= 300),
    reject_reason           TEXT        CHECK (reject_reason IS NULL OR length(reject_reason) <= 200),
    refund_amount_krw       BIGINT      NOT NULL DEFAULT 0 CHECK (refund_amount_krw >= 0),
    -- 교환 재발송
    reship_courier          TEXT        CHECK (reship_courier IS NULL OR length(reship_courier) <= 50),
    reship_tracking_number  TEXT        CHECK (reship_tracking_number IS NULL OR length(reship_tracking_number) <= 50),
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- 교환은 돈이 오가지 않는다. 반품만 환불액이 있다.
    CONSTRAINT return_exchange_no_refund CHECK (type = 'RETURN' OR refund_amount_krw = 0)
);

-- 한 주문에 진행 중인 신청은 하나. 두 건이 동시에 돌면 회수·환불이 꼬인다.
CREATE UNIQUE INDEX uq_return_request_active
    ON return_request (order_id) WHERE status IN ('REQUESTED', 'APPROVED', 'COLLECTED');

CREATE INDEX idx_return_request_member ON return_request (member_id, created_at DESC);
CREATE INDEX idx_return_request_status ON return_request (status, created_at DESC);

CREATE TRIGGER trg_return_request_updated_at
    BEFORE UPDATE ON return_request
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

COMMENT ON TABLE return_request IS '교환·반품 신청. 지우지 않는다 — 주문과 같은 계약 기록.';


-- 어느 주문 항목을 몇 벌 · 교환이면 어떤 사이즈로
CREATE TABLE return_request_item (
    id              UUID     PRIMARY KEY,
    return_id       UUID     NOT NULL REFERENCES return_request (id),
    order_item_id   UUID     NOT NULL REFERENCES order_item (id),
    quantity        SMALLINT NOT NULL CHECK (quantity BETWEEN 1 AND 10),
    exchange_size   TEXT     CHECK (exchange_size IS NULL OR length(exchange_size) BETWEEN 1 AND 20),
    UNIQUE (return_id, order_item_id)
);

CREATE INDEX idx_return_request_item_order_item ON return_request_item (order_item_id);


-- 상태 이력. 손님 화면의 진행 단계와 관리자 기록이 같은 데이터다 (order_event 와 같은 구조).
CREATE TABLE return_event (
    id           BIGSERIAL   PRIMARY KEY,
    return_id    UUID        NOT NULL REFERENCES return_request (id),
    from_status  TEXT,
    to_status    TEXT        NOT NULL,
    actor_id     UUID        REFERENCES member (id),
    note         TEXT        CHECK (note IS NULL OR length(note) <= 300),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_return_event_return ON return_event (return_id, id);
