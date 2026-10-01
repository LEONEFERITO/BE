-- ============================================================
-- V13 — 장바구니 · 주문 · 주문 이력
--
-- 원칙 (DECISIONS.md "D1 확정"):
--   · 회원만 주문한다. 장바구니도 서버에 둔다 — 기기를 바꿔도 이어진다.
--   · 금액은 서버만 계산한다. 주문을 만들 때 계산해 저장하고, 토스 승인 직전에 대조한다.
--   · 주문 행은 지우지 않는다. 계약·결제 기록은 5년 보관 의무가 있다(전자상거래법).
--   · 주문 당시의 상품명·가격·사이즈·제작 기간을 주문 항목에 **복사**한다. 상품을 나중에
--     고치거나 내려도 "그때 무엇을 얼마에 샀는지" 는 바뀌면 안 된다.
-- ============================================================

-- ─────────────────────────────────────────────────────────────
-- 장바구니
-- 가격을 담지 않는다. 담아 둔 사이에 가격이 바뀔 수 있다 — 볼 때마다 상품에서 읽는다.
-- ─────────────────────────────────────────────────────────────
CREATE TABLE cart_item (
    id          UUID        PRIMARY KEY,
    member_id   UUID        NOT NULL REFERENCES member (id),
    product_id  UUID        NOT NULL REFERENCES product (id),
    size        TEXT        NOT NULL CHECK (length(size) BETWEEN 1 AND 20),
    -- 한 줄 10벌까지. 주문 제작이라 대량 주문은 상담으로 받는다.
    quantity    SMALLINT    NOT NULL CHECK (quantity BETWEEN 1 AND 10),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- 같은 상품·사이즈는 한 줄. 다시 담으면 수량이 는다.
    UNIQUE (member_id, product_id, size)
);

CREATE INDEX idx_cart_item_member ON cart_item (member_id, created_at);

CREATE TRIGGER trg_cart_item_updated_at
    BEFORE UPDATE ON cart_item
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();


-- ─────────────────────────────────────────────────────────────
-- 주문
--
-- 상태:
--   PENDING_PAYMENT → PAID → IN_PRODUCTION → SHIPPED → DELIVERED
--                      └──────┴→ CANCELLED (발송 전에만, 전액 환불)
-- 결제 전에 떠난 주문은 PENDING_PAYMENT 로 남는다. 손님 화면과 관리자 기본 목록에서는 숨긴다.
-- ─────────────────────────────────────────────────────────────
CREATE TABLE orders (
    id                   UUID        PRIMARY KEY,
    -- 손님과 토스가 보는 번호. 토스 orderId 규칙(영문·숫자·-·_ 6~64자)을 DB 도 지킨다.
    order_number         TEXT        NOT NULL UNIQUE CHECK (order_number ~ '^[A-Za-z0-9_-]{6,64}$'),
    member_id            UUID        NOT NULL REFERENCES member (id),
    status               TEXT        NOT NULL
                         CHECK (status IN ('PENDING_PAYMENT', 'PAID', 'IN_PRODUCTION',
                                           'SHIPPED', 'DELIVERED', 'CANCELLED')),
    -- 결제창에 뜨는 이름. 예) "브라운 셔츠 외 1건"
    order_name           TEXT        NOT NULL CHECK (length(order_name) <= 100),

    items_amount_krw     BIGINT      NOT NULL CHECK (items_amount_krw > 0),
    shipping_fee_krw     BIGINT      NOT NULL CHECK (shipping_fee_krw >= 0),
    total_amount_krw     BIGINT      NOT NULL,
    CONSTRAINT orders_total_is_sum CHECK (total_amount_krw = items_amount_krw + shipping_fee_krw),

    recipient_name       TEXT        NOT NULL CHECK (length(recipient_name) <= 50),
    recipient_phone      TEXT        NOT NULL CHECK (recipient_phone ~ '^[0-9-]{9,20}$'),
    zip_code             TEXT        NOT NULL CHECK (zip_code ~ '^[0-9]{5}$'),
    address1             TEXT        NOT NULL CHECK (length(address1) <= 200),
    address2             TEXT        CHECK (address2 IS NULL OR length(address2) <= 200),
    delivery_memo        TEXT        CHECK (delivery_memo IS NULL OR length(delivery_memo) <= 100),

    -- 결제 전에 주문 내용 · 제작 기간 · 교환/반품 조건을 확인했다는 기록 (전자상거래법 제8조 · 약관 제9조)
    agreed_at            TIMESTAMPTZ NOT NULL,

    -- 결제 (토스페이먼츠). 카드번호 등은 토스가 갖는다 — 여기 없다.
    payment_key          TEXT        UNIQUE,
    payment_method       TEXT,
    paid_at              TIMESTAMPTZ,

    courier              TEXT        CHECK (courier IS NULL OR length(courier) <= 50),
    tracking_number      TEXT        CHECK (tracking_number IS NULL OR length(tracking_number) <= 50),
    shipped_at           TIMESTAMPTZ,
    delivered_at         TIMESTAMPTZ,

    cancelled_at         TIMESTAMPTZ,
    cancel_reason        TEXT        CHECK (cancel_reason IS NULL OR length(cancel_reason) <= 200),
    refunded_amount_krw  BIGINT      NOT NULL DEFAULT 0 CHECK (refunded_amount_krw >= 0),

    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- 결제가 끝난 주문에는 결제 키가 반드시 있다. 없으면 환불할 길이 없다.
    CONSTRAINT orders_paid_has_key CHECK (
        status IN ('PENDING_PAYMENT') OR (status = 'CANCELLED' AND paid_at IS NULL) OR payment_key IS NOT NULL),
    CONSTRAINT orders_refund_le_total CHECK (refunded_amount_krw <= total_amount_krw)
);

CREATE INDEX idx_orders_member ON orders (member_id, created_at DESC);
CREATE INDEX idx_orders_status ON orders (status, created_at DESC);

CREATE TRIGGER trg_orders_updated_at
    BEFORE UPDATE ON orders
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

COMMENT ON TABLE orders IS '주문. 지우지 않는다 — 계약·결제 기록 5년 보관(전자상거래법).';


-- ─────────────────────────────────────────────────────────────
-- 주문 항목 — 주문 당시 값의 복사본
-- ─────────────────────────────────────────────────────────────
CREATE TABLE order_item (
    id                UUID     PRIMARY KEY,
    order_id          UUID     NOT NULL REFERENCES orders (id),
    product_id        UUID     NOT NULL REFERENCES product (id),
    product_slug      TEXT     NOT NULL,
    product_name      TEXT     NOT NULL,
    image_url         TEXT,
    size              TEXT     NOT NULL,
    unit_price_krw    BIGINT   NOT NULL CHECK (unit_price_krw > 0),
    quantity          SMALLINT NOT NULL CHECK (quantity BETWEEN 1 AND 10),
    line_amount_krw   BIGINT   NOT NULL,
    lead_time_days    SMALLINT NOT NULL CHECK (lead_time_days > 0),
    sort_order        INT      NOT NULL DEFAULT 0,
    CONSTRAINT order_item_line_is_product CHECK (line_amount_krw = unit_price_krw * quantity)
);

CREATE INDEX idx_order_item_order ON order_item (order_id, sort_order);


-- ─────────────────────────────────────────────────────────────
-- 주문 이력 — 상태가 바뀔 때마다 한 줄. 손님 화면의 진행 단계와 관리자 기록이 같은 데이터다.
-- actor_id 가 NULL 이면 손님 본인 또는 시스템(결제 승인)이다.
-- ─────────────────────────────────────────────────────────────
CREATE TABLE order_event (
    id           BIGSERIAL   PRIMARY KEY,
    order_id     UUID        NOT NULL REFERENCES orders (id),
    from_status  TEXT,
    to_status    TEXT        NOT NULL,
    actor_id     UUID        REFERENCES member (id),
    note         TEXT        CHECK (note IS NULL OR length(note) <= 300),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_order_event_order ON order_event (order_id, id);
