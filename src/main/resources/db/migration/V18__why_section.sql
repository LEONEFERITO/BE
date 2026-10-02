-- ============================================================
-- V18 — 메인 WHY 구간 (관리자가 고친다)
--
-- 메인의 "왜 LEONE FERITO 인가" 구간. 화면을 고정해 두고 스크롤하면 항목이 하나씩 밝아지며
-- 배경 사진이 바뀐다(스크롤 연출). 제목 · 소개 · 항목(제목 · 설명 · 배경 사진)을 관리자가 고친다.
--
-- why_section 은 한 행뿐이다(hero_config 와 같은 이유 — "어느 게 진짜인가" 를 DB 가 막는다).
-- 항목은 2~5개(요청 검증). 사진이 없는 항목은 코드에 있는 기본 배경을 쓴다.
-- 손님 화면은 빌드 때 받는다 — 저장하면 손님 화면을 다시 만든다(FrontRebuildTrigger, 1~2분).
-- ============================================================

CREATE TABLE why_section (
    id          SMALLINT    PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    eyebrow     TEXT        NOT NULL CHECK (length(eyebrow) BETWEEN 1 AND 40),
    title       TEXT        NOT NULL CHECK (length(title) BETWEEN 1 AND 60),
    intro       TEXT        NOT NULL CHECK (length(intro) BETWEEN 1 AND 400),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TRIGGER trg_why_section_updated_at
    BEFORE UPDATE ON why_section
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TABLE why_item (
    id          UUID        PRIMARY KEY,
    section_id  SMALLINT    NOT NULL REFERENCES why_section (id),
    title       TEXT        NOT NULL CHECK (length(title) BETWEEN 1 AND 40),
    body        TEXT        NOT NULL CHECK (length(body) BETWEEN 1 AND 300),
    -- 배경 사진. NULL 이면 코드의 기본 배경. 사진이 지워지면 기본으로 돌아간다.
    media_id    UUID        REFERENCES media_asset (id) ON DELETE SET NULL,
    sort_order  INT         NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_why_item_order ON why_item (section_id, sort_order);

-- 지금 코드에 박혀 있던 문구를 그대로 심는다 — 관리자가 처음 열어도 빈 화면이 아니다.
INSERT INTO why_section (id, eyebrow, title, intro) VALUES
    (1, 'WHY LEONE FERITO', '사진이 아니라 치수로 고르세요',
     '어깨·가슴·허벅지는 끼는데 허리는 남는 옷을 입어 오셨다면, 문제는 체형이 아니라 패턴입니다. 모든 상품에 사이즈별 상세 실측과 모델 착용 정보를 공개합니다.');

INSERT INTO why_item (id, section_id, title, body, sort_order) VALUES
    (gen_random_uuid(), 1, '두 개의 라인', '레오네(클래식)와 페리토(애슬레틱)로 패턴을 나눠 제작합니다. 상품마다 어느 라인인지 표시합니다.', 10),
    (gen_random_uuid(), 1, '상세 실측', '사이즈별 어깨·가슴·허리·소매·총장을 전부 공개합니다. 측정 기준과 허용 오차까지 밝힙니다.', 20),
    (gen_random_uuid(), 1, '모델 체형', '모델의 키·몸무게·착용 사이즈를 함께 표기해 내 체형과 비교할 수 있게 합니다.', 30);
