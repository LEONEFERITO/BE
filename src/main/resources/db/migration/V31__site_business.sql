-- ============================================================
-- V31 — 푸터 사업자 정보 (관리자에서 고친다)
--
-- 전자상거래법 제10조 표기 의무 항목이다. 지금까지 FE 코드(data/business.ts)에 있었는데, 관리자 화면에서
-- 고칠 수 있게 한 줄짜리 표로 옮긴다. 손님 화면은 빌드할 때 받는다 — 저장하면 손님 화면을 다시 만든다.
-- 값은 2026-10-01 고객 전달값 그대로 넣는다. PG 심사는 사업자등록증과 한 글자만 달라도 반려한다.
-- ============================================================

CREATE TABLE site_business (
    id                   SMALLINT    PRIMARY KEY CHECK (id = 1),
    company_name         TEXT        NOT NULL CHECK (char_length(company_name) BETWEEN 1 AND 100),
    representative       TEXT        NOT NULL CHECK (char_length(representative) BETWEEN 1 AND 50),
    registration_number  TEXT        NOT NULL CHECK (registration_number ~ '^[0-9]{3}-[0-9]{2}-[0-9]{5}$'),
    mail_order_number    TEXT        NOT NULL CHECK (char_length(mail_order_number) BETWEEN 1 AND 50),
    address              TEXT        NOT NULL CHECK (char_length(address) BETWEEN 1 AND 200),
    phone                TEXT        NOT NULL CHECK (char_length(phone) BETWEEN 1 AND 100),
    email                TEXT        NOT NULL CHECK (char_length(email) BETWEEN 3 AND 254),
    privacy_officer      TEXT        NOT NULL CHECK (char_length(privacy_officer) BETWEEN 1 AND 100),
    hosting              TEXT        NOT NULL CHECK (char_length(hosting) BETWEEN 1 AND 100),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now()
);

COMMENT ON TABLE site_business IS '푸터 사업자 정보 — 한 줄. 손님 화면이 빌드할 때 받는다.';

CREATE TRIGGER trg_site_business_updated_at
    BEFORE UPDATE ON site_business
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

INSERT INTO site_business (id, company_name, representative, registration_number, mail_order_number, address, phone,
                           email, privacy_officer, hosting)
VALUES (1, '레오네페리토 (LEONEFERITO)', '조강현', '881-15-02245', '2024-수원영통-0059',
        '수원시 영통구 매영로 425번길 1 1층 더맨리', '010-2434-7794 · 010-9291-0518', 'sport0308@naver.com',
        '조강현 (대표)', 'Amazon Web Services (서울)');
