-- ============================================================
-- V3 — 상품 도메인
--
-- 라인(레오네·페리토)과 카테고리(자켓·트라우저·셔츠·구두)가 확정되어(2026-09-28)
-- FE 가 하드코딩으로 들고 있던 모양을 DB 로 옮긴다.
--
-- 범위: 상품 · 이미지 · SKU(사이즈) · 실측.
-- 주문·장바구니·회원은 여기 없다. 포인트/등급 규칙이 아직 없어서 지금 그리면 다시 그린다.
-- ============================================================

-- ─────────────────────────────────────────────────────────────
-- 상품
-- ─────────────────────────────────────────────────────────────
CREATE TABLE product (
    id              UUID        PRIMARY KEY,

    -- URL 에 그대로 나간다(/products/{slug}). 한번 공개되면 바꾸지 않는다 —
    -- 바꾸면 검색 결과와 공유된 링크가 전부 죽는다. 바꿔야 하면 리다이렉트를 함께 넣는다.
    slug            TEXT        NOT NULL UNIQUE,

    -- 미확정이면 NULL. 빈 문자열로 두지 않는다 —
    -- ''(빈 값)과 "아직 안 정함"은 다른 상태인데 둘을 같은 값으로 두면 구분이 사라진다.
    name            TEXT,

    /*
     * 라인과 카테고리는 TEXT + CHECK 다.
     *
     * PostgreSQL enum 타입을 쓰지 않는 이유: 값 추가(ALTER TYPE ... ADD VALUE)가
     * 트랜잭션 안에서 제약이 있어 Flyway 마이그레이션에서 다루기 번거롭다.
     * CHECK 는 그냥 제약을 다시 걸면 된다.
     *
     * 값을 늘릴 때(예: 니트·타이) 반드시 세 곳을 같이 고친다:
     *   이 CHECK · Java enum(ProductCategory/ProductLine) · FE 의 CATEGORY_LABEL
     * 한 곳만 고치면 DB 가 막아준다(= 조용히 틀리지 않는다). 그게 CHECK 를 두는 이유다.
     */
    category        TEXT        NOT NULL
                    CHECK (category IN ('JACKET', 'TROUSERS', 'SHIRT', 'SHOES')),
    line            TEXT        NOT NULL
                    CHECK (line IN ('LEONE', 'FERITO')),

    -- 원화는 소수 단위가 없다. NUMERIC 대신 BIGINT 를 쓰고 '원' 단위 정수로 둔다.
    -- 부동소수점(REAL/DOUBLE)은 금액에 절대 쓰지 않는다 — 더하면 오차가 쌓인다.
    price_krw       BIGINT      CHECK (price_krw > 0),
    -- 정가. 할인 표시(취소선)에 쓴다. 판매가보다 낮으면 표시가 거꾸로 되므로 막는다.
    list_price_krw  BIGINT      CHECK (list_price_krw > 0),
    CONSTRAINT product_list_price_not_below_price
        CHECK (list_price_krw IS NULL OR price_krw IS NULL OR list_price_krw >= price_krw),

    -- 상세페이지 본문 (고객 요구사항 3번: 제작 의도 / 특징 / 모델 스펙)
    description     TEXT,
    intent          TEXT,        -- 제작 의도
    features        TEXT,        -- 제품 특징·장점
    fabric          TEXT,        -- 원단 정보 및 혼용률
    care            TEXT,        -- 세탁·관리 정보

    -- 피팅 모델 신체 스펙. "이 사람이 이 사이즈를 입었다" 가 실측만큼 중요한 판단 재료다.
    model_height_cm SMALLINT    CHECK (model_height_cm BETWEEN 100 AND 250),
    model_weight_kg SMALLINT    CHECK (model_weight_kg BETWEEN 30 AND 200),
    model_size      TEXT,

    /*
     * 주문 후 제작 기간(일).
     *
     * 이 컬럼이 재고(stock)를 대신한다. 이 브랜드는 만들어 둔 물건을 파는 게 아니라
     * 주문을 받고 만든다. "재고 3개"라는 숫자 자체가 존재하지 않는다.
     * 재고 컬럼을 두면 0 이 되는 순간 품절로 표시되는데, 사실은 주문할 수 있다.
     *
     * NULL = 아직 안 받음. 전자상거래법상 **결제 전에 반드시 표시해야 하는 값**이라
     * 값이 없으면 상품을 공개하면 안 된다(아래 status 참고).
     */
    lead_time_days  SMALLINT    CHECK (lead_time_days > 0),

    /*
     * 공개 상태. 촬영본·가격·제작 기간이 없는 상품이 그대로 손님에게 보이면 안 된다.
     * 공개 API 는 PUBLISHED 만 내보낸다. 목록에서 빼는 걸 애플리케이션 조건문에만
     * 맡기면 어느 한 쿼리에서 빠뜨리는 날이 온다 — 상태를 데이터로 둔다.
     */
    status          TEXT        NOT NULL DEFAULT 'DRAFT'
                    CHECK (status IN ('DRAFT', 'PUBLISHED', 'ARCHIVED')),

    -- 목록 정렬. 같은 값이면 created_at 으로 자른다(아래 인덱스).
    display_order   INTEGER     NOT NULL DEFAULT 0,

    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

COMMENT ON TABLE  product IS '판매 상품. 주문 후 제작이므로 재고 개념이 없다.';
COMMENT ON COLUMN product.slug IS 'URL 에 노출된다. 공개 후 변경 금지(리다이렉트 없이 링크가 죽는다).';
COMMENT ON COLUMN product.lead_time_days IS '주문 후 제작 기간(일). 재고를 대신한다. 결제 전 고지 의무 대상.';
COMMENT ON COLUMN product.status IS 'DRAFT 는 공개 API 에서 제외된다.';

CREATE TRIGGER trg_product_updated_at
    BEFORE UPDATE ON product
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- 목록 조회는 "공개된 것만, 정렬 순서대로" 가 기본이다.
-- 부분 인덱스로 두면 DRAFT/ARCHIVED 행이 인덱스에 들어가지 않아 더 작고 빠르다.
CREATE INDEX idx_product_published
    ON product (display_order, created_at DESC)
    WHERE status = 'PUBLISHED';

-- 카테고리·라인 필터는 메인의 카테고리 격자와 목록 칩이 둘 다 쓴다.
CREATE INDEX idx_product_category ON product (category) WHERE status = 'PUBLISHED';
CREATE INDEX idx_product_line     ON product (line)     WHERE status = 'PUBLISHED';


-- ─────────────────────────────────────────────────────────────
-- 상품 이미지
--
-- 종류는 고객이 지정한 4종이다 (상세페이지 요구사항 4번).
-- ─────────────────────────────────────────────────────────────
CREATE TABLE product_image (
    id            UUID        PRIMARY KEY,

    product_id    UUID        NOT NULL REFERENCES product (id) ON DELETE CASCADE,

    -- 바이트는 media_asset 이 가리키는 스토리지에 있다 (V2 참고).
    -- 이미지가 지워지면 상품에서도 사라져야 한다. 깨진 참조를 남기지 않는다.
    media_id      UUID        NOT NULL REFERENCES media_asset (id) ON DELETE CASCADE,

    kind          TEXT        NOT NULL
                  CHECK (kind IN ('MAIN', 'WORN', 'DETAIL', 'CUTOUT')),

    /*
     * 대체 텍스트. NOT NULL 이다.
     *
     * 나중에 채우게 두면 영원히 비어 있다. 장식용 이미지가 아니라 상품 사진이므로
     * 빈 alt 는 스크린리더 사용자에게 상품이 없는 것과 같다.
     * (빈 문자열은 허용한다 — "장식이라 읽지 말 것" 은 유효한 의도다. NULL 은 "안 정함"이다)
     */
    alt           TEXT        NOT NULL,

    sort_order    INTEGER     NOT NULL DEFAULT 0,

    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

COMMENT ON COLUMN product_image.kind IS 'MAIN 대표 / WORN 착용샷 / DETAIL 디테일컷 / CUTOUT 누끼샷';

CREATE INDEX idx_product_image_product ON product_image (product_id, sort_order);

-- 대표 이미지는 상품당 하나다. 둘이면 어느 걸 목록에 쓸지 코드가 매번 고민한다.
CREATE UNIQUE INDEX uq_product_image_main
    ON product_image (product_id)
    WHERE kind = 'MAIN';


-- ─────────────────────────────────────────────────────────────
-- SKU — 사이즈 한 줄
-- ─────────────────────────────────────────────────────────────
CREATE TABLE product_sku (
    id            UUID        PRIMARY KEY,

    product_id    UUID        NOT NULL REFERENCES product (id) ON DELETE CASCADE,

    -- '95' '100' 같은 표기. 브랜드가 쓰는 문자열을 그대로 둔다(숫자로 바꾸지 않는다).
    -- 사이즈 체계가 알파벳(S/M/L)으로 바뀌어도 스키마를 안 고쳐도 된다.
    size          TEXT        NOT NULL,

    /*
     * 정렬 순서를 따로 둔다. size 로 정렬하면 문자열 비교라 '100' 이 '95' 앞에 온다.
     * 숫자로 캐스팅하는 방법은 S/M/L 이 들어오는 순간 깨진다.
     */
    sort_order    INTEGER     NOT NULL DEFAULT 0,

    /*
     * 주문 가능 여부. 재고 수량이 아니다.
     * 제작이 가능한 사이즈인가를 뜻한다 — 패턴이 없거나 원단이 끊긴 경우 false.
     */
    is_orderable  BOOLEAN     NOT NULL DEFAULT TRUE,

    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_product_sku_size UNIQUE (product_id, size)
);

COMMENT ON COLUMN product_sku.is_orderable IS '제작 가능 여부. 재고 수량이 아니다(주문 후 제작).';

CREATE TRIGGER trg_product_sku_updated_at
    BEFORE UPDATE ON product_sku
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE INDEX idx_product_sku_product ON product_sku (product_id, sort_order);


-- ─────────────────────────────────────────────────────────────
-- 실측
--
-- 사이즈마다 다르므로 상품이 아니라 SKU 에 붙는다.
-- 이 사이트의 주장("치수를 다 공개한다")이 실제로 서는 곳이다.
-- ─────────────────────────────────────────────────────────────
CREATE TABLE product_measurement (
    id             UUID          PRIMARY KEY,

    product_sku_id UUID          NOT NULL REFERENCES product_sku (id) ON DELETE CASCADE,

    -- 부위. 상의·하의가 한 테이블을 쓰므로 둘의 부위가 모두 들어 있다.
    part           TEXT          NOT NULL
                   CHECK (part IN ('SHOULDER', 'CHEST', 'WAIST', 'SLEEVE',
                                   'LENGTH', 'THIGH', 'HEM', 'RISE')),

    -- cm. 0.5 단위까지 쓰므로 소수 한 자리. 금액이 아니므로 NUMERIC 이 맞다.
    value_cm       NUMERIC(5, 1) NOT NULL CHECK (value_cm > 0),

    /*
     * 허용 오차(±cm). 실측을 공개하면 "1cm 다르다" 는 문의가 반드시 온다.
     * 봉제 제품은 오차가 있는 게 정상이고, 그 범위를 미리 밝히는 것이 분쟁을 줄인다.
     * NULL = 아직 안 정함.
     */
    tolerance_cm   NUMERIC(3, 1) CHECK (tolerance_cm >= 0),

    created_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),

    -- 같은 사이즈에 같은 부위가 두 번 있으면 어느 게 맞는지 알 수 없다.
    CONSTRAINT uq_product_measurement_part UNIQUE (product_sku_id, part)
);

COMMENT ON TABLE  product_measurement IS '사이즈별 부위 실측(cm). 상품이 아니라 SKU 에 붙는다.';
COMMENT ON COLUMN product_measurement.tolerance_cm IS '허용 오차 ±cm. 봉제 오차를 미리 밝혀 분쟁을 줄인다.';

CREATE TRIGGER trg_product_measurement_updated_at
    BEFORE UPDATE ON product_measurement
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE INDEX idx_product_measurement_sku ON product_measurement (product_sku_id);
