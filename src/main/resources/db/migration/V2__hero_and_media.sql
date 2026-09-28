-- ============================================================
-- V2 — 히어로 배너 + 업로드 이미지
--
-- 목적: 관리자가 메인 히어로의 누끼 사진을 코드 수정/재배포 없이 교체한다.
--
-- 왜 이미지 바이트를 DB 에 넣지 않는가:
--   히어로 이미지는 100KB 내외지만 DB 는 백업·복제 단위가 통째다.
--   이미지가 늘어날수록 백업 시간과 복제 지연이 같이 늘고, 되돌리기 어려워진다.
--   DB 에는 **어떤 파일이 어디 있는지** 만 두고 바이트는 스토리지에 둔다.
-- ============================================================

-- 업로드된 이미지 한 장.
CREATE TABLE media_asset (
    id                UUID        PRIMARY KEY,

    -- 원본 파일명은 **표시용** 이다. 경로로 쓰지 않는다.
    -- 사용자가 준 이름을 파일 경로에 그대로 쓰면 ../ 로 디렉터리를 빠져나갈 수 있다.
    original_filename TEXT        NOT NULL,

    -- 클라이언트가 보낸 Content-Type 이 아니라 **서버가 매직바이트로 판별한** 형식이다.
    -- 선언된 타입을 믿고 그대로 내보내면 이미지로 위장한 HTML 이 실행된다(저장형 XSS).
    content_type      TEXT        NOT NULL,
    byte_size         BIGINT      NOT NULL CHECK (byte_size > 0),
    width             INTEGER,
    height            INTEGER,

    -- 스토리지 안에서의 키. 서버가 UUID 로 만든다 (사용자 입력이 섞이지 않는다).
    storage_key       TEXT        NOT NULL UNIQUE,

    created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

COMMENT ON COLUMN media_asset.content_type IS '서버가 매직바이트로 판별한 형식. 클라이언트 선언값을 신뢰하지 않는다.';
COMMENT ON COLUMN media_asset.storage_key  IS '스토리지 키. 서버 생성값이며 원본 파일명이 섞이지 않는다.';

-- 히어로 설정. **한 행만** 존재한다.
--
-- 왜 단일 행 테이블인가: 설정은 하나뿐인데 여러 행이 생길 수 있는 구조로 두면
-- "어느 게 진짜인가" 를 애플리케이션이 매번 판단해야 한다. DB 가 못 하게 막는다.
CREATE TABLE hero_config (
    id              SMALLINT    PRIMARY KEY DEFAULT 1 CHECK (id = 1),

    -- NULL = 코드에 내장된 기본 이미지를 쓴다.
    -- 관리자가 아직 아무것도 올리지 않아도 사이트는 정상 동작해야 한다.
    left_media_id   UUID        REFERENCES media_asset (id) ON DELETE SET NULL,
    left_alt        TEXT        NOT NULL,

    right_media_id  UUID        REFERENCES media_asset (id) ON DELETE SET NULL,
    right_alt       TEXT        NOT NULL,

    -- 마지막으로 저장한 사람. Phase 5 에서 관리자 계정이 붙으면 FK 로 바꾼다.
    updated_by      TEXT,

    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

COMMENT ON TABLE  hero_config IS '메인 히어로 설정. id=1 단일 행만 허용한다.';
COMMENT ON COLUMN hero_config.left_media_id IS 'NULL 이면 FE 내장 기본 이미지를 쓴다.';

CREATE TRIGGER trg_hero_config_updated_at
    BEFORE UPDATE ON hero_config
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- 기본 행을 심어둔다. 없으면 관리자 화면이 첫 진입에서 404 를 만난다.
-- 대체 텍스트는 코드에 내장된 기본 이미지의 설명과 같아야 한다.
INSERT INTO hero_config (id, left_alt, right_alt) VALUES
    (1, '브라운 셔츠와 블랙 와이드 슬랙스를 착용한 측면 컷',
        '화이트 셔츠와 블랙 타이, 슬랙스를 착용한 정면 컷');
