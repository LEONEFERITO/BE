-- ============================================================
-- V15 — 관리자 아이디 로그인 · 공지사항 · FAQ
-- ============================================================

-- ─────────────────────────────────────────────────────────────
-- 관리자 아이디 로그인
--
-- login_id: 관리자 로그인 화면(/admin/login)에서 이메일 대신 쓰는 아이디. 서버 명령(create-admin)으로만 붙는다.
--           소문자·숫자·밑줄 4~30자. 이메일과 헷갈리지 않게 @ 를 받지 않는다.
-- must_change_password: 임시 비밀번호로 만든 계정. 비밀번호를 바꾸기 전에는 관리자 API 가 막힌다.
-- ─────────────────────────────────────────────────────────────
ALTER TABLE member
    ADD COLUMN login_id TEXT UNIQUE CHECK (login_id IS NULL OR login_id ~ '^[a-z0-9_]{4,30}$'),
    ADD COLUMN must_change_password BOOLEAN NOT NULL DEFAULT false;


-- ─────────────────────────────────────────────────────────────
-- 공지사항. 본문은 글자 그대로 보여 준다(HTML 을 받지 않는다 — 저장형 XSS 를 원천 차단).
-- ─────────────────────────────────────────────────────────────
CREATE TABLE notice (
    id            UUID        PRIMARY KEY,
    title         TEXT        NOT NULL CHECK (length(title) BETWEEN 1 AND 100),
    body          TEXT        NOT NULL CHECK (length(body) BETWEEN 1 AND 5000),
    -- 목록 맨 위에 고정
    pinned        BOOLEAN     NOT NULL DEFAULT false,
    published     BOOLEAN     NOT NULL DEFAULT false,
    -- 처음 공개한 시각. 목록의 날짜다. 내렸다가 다시 올려도 바뀌지 않는다.
    published_at  TIMESTAMPTZ,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT notice_published_has_date CHECK (NOT published OR published_at IS NOT NULL)
);

CREATE INDEX idx_notice_public ON notice (published, pinned DESC, published_at DESC);

CREATE TRIGGER trg_notice_updated_at
    BEFORE UPDATE ON notice
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();


-- ─────────────────────────────────────────────────────────────
-- 자주 묻는 질문. 분류는 QnA 화면의 칩과 같다.
-- 한 줄도 없으면 QnA 화면은 코드에 있는 기본 질문을 보여 준다.
-- ─────────────────────────────────────────────────────────────
CREATE TABLE faq (
    id          UUID        PRIMARY KEY,
    category    TEXT        NOT NULL CHECK (category IN ('ORDER', 'SIZE', 'SHIPPING')),
    question    TEXT        NOT NULL CHECK (length(question) BETWEEN 1 AND 200),
    answer      TEXT        NOT NULL CHECK (length(answer) BETWEEN 1 AND 3000),
    sort_order  INT         NOT NULL DEFAULT 0,
    published   BOOLEAN     NOT NULL DEFAULT true,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_faq_order ON faq (sort_order, created_at);

CREATE TRIGGER trg_faq_updated_at
    BEFORE UPDATE ON faq
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
