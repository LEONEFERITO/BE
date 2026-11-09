-- ============================================================
-- V51 — 접속 통계 (2026-11-09 고객 "날짜 · 요일 · 시간대별로 얼마나 접속했는지, 접속 경로는 어떤지 관리자에서 확인")
--
-- 손님 화면이 페이지를 열 때마다 한 줄. 관리자 «통계 › 접속 통계» 가 날짜 · 요일 · 시간대 · 유입 경로 · 기기로 묶어 보여 준다.
--
-- 개인정보를 남기지 않는다:
--   · IP · 브라우저 정보 원본은 저장하지 않는다. visitor 는 (그날의 무작위 소금 + IP + 브라우저)의 해시 앞 16자리.
--   · 소금(site_visit_salt)은 하루만 쓰고 다음 날 지운다 — 지난 날짜의 visitor 는 누구였는지 되돌릴 방법이 없다.
--     그래서 "방문자" 는 날마다 따로 센다(같은 사람이 이틀 오면 2).
--   · 쿠키를 쓰지 않는다. 같은 탭 안에서 처음 연 페이지만 entry(방문 시작)다 — 브라우저의 sessionStorage 로 화면이 판단한다.
-- 보관: 2년(RetentionCleanup).
-- ============================================================

CREATE TABLE site_visit (
    id            BIGSERIAL    PRIMARY KEY,
    visited_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    visitor       TEXT         NOT NULL CHECK (char_length(visitor) = 16),
    -- 방문 시작(탭에서 처음 연 페이지)인가. 방문 수 · 유입 경로 · 첫 페이지는 이 줄로 센다
    entry         BOOLEAN      NOT NULL,
    path          TEXT         NOT NULL CHECK (char_length(path) BETWEEN 1 AND 200),
    -- 유입 경로 분류(VisitService.Source). 방문 시작 줄에만 의미가 있다
    source        TEXT         NOT NULL,
    referrer_host TEXT         CHECK (referrer_host IS NULL OR char_length(referrer_host) <= 100),
    utm_source    TEXT         CHECK (utm_source IS NULL OR char_length(utm_source) <= 60),
    utm_medium    TEXT         CHECK (utm_medium IS NULL OR char_length(utm_medium) <= 60),
    utm_campaign  TEXT         CHECK (utm_campaign IS NULL OR char_length(utm_campaign) <= 60),
    device        TEXT         NOT NULL CHECK (device IN ('MOBILE', 'TABLET', 'DESKTOP'))
);

CREATE INDEX idx_site_visit_at ON site_visit (visited_at);

COMMENT ON TABLE site_visit IS '접속 통계(V51) — 손님 화면 페이지 열람 한 번에 한 줄. IP 원본 없음, visitor 는 하루짜리 해시';

CREATE TABLE site_visit_salt (
    day   DATE  PRIMARY KEY,
    salt  TEXT  NOT NULL
);

COMMENT ON TABLE site_visit_salt IS '방문자 해시의 그날 소금(V51). 지난 날짜 것은 매일 정리에서 지운다';
