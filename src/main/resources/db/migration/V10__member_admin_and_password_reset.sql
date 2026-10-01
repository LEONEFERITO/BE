-- ============================================================
-- V10 — 회원 관리 · 최고 관리자 · 비밀번호 재설정 · 탈퇴 익명화
--
-- 네 가지가 한 번에 들어오는 이유: 전부 "회원 행의 상태가 바뀌는 길" 이다.
-- 관리자 지정 · 이용 정지 · 비밀번호 재설정 · 탈퇴. 하나라도 빠지면 운영자가
-- 결국 psql 로 행을 고치게 되고, 그 변경은 아무 기록도 남지 않는다.
-- ============================================================

-- ─────────────────────────────────────────────────────────────
-- 1. 역할에 SUPER_ADMIN 추가
--
-- 관리자 지정을 화면으로 열면서(V5 의 "화면으로 승격 불가" 원칙을 바꿨다),
-- 그 권한을 누구에게 줄지가 문제가 된다. 모든 ADMIN 이 ADMIN 을 만들 수 있으면
-- 관리자 계정 하나가 뚫렸을 때 공격자가 자기 편을 늘린다.
--
-- 그래서 한 단계 위를 둔다:
--   SUPER_ADMIN — 관리자 지정·해제. 서버 명령(create-admin)으로만 만든다. 화면으로는 못 만든다.
--   ADMIN       — 상품·회원·주문 운영. SUPER_ADMIN 이 화면에서 지정한다.
--
-- SUPER_ADMIN 을 화면으로 바꿀 수 없으므로 "마지막 최고 관리자를 내려서 아무도 못 들어오는"
-- 사고가 구조적으로 생기지 않는다.
-- ─────────────────────────────────────────────────────────────
ALTER TABLE member DROP CONSTRAINT member_role_check;
ALTER TABLE member ADD CONSTRAINT member_role_check
    CHECK (role IN ('MEMBER', 'ADMIN', 'SUPER_ADMIN'));

DROP INDEX idx_member_admin;
CREATE INDEX idx_member_admin ON member (email) WHERE role <> 'MEMBER';

COMMENT ON COLUMN member.role IS
    'MEMBER · ADMIN · SUPER_ADMIN. ADMIN 은 SUPER_ADMIN 이 화면에서 지정, SUPER_ADMIN 은 서버 명령으로만.';


-- ─────────────────────────────────────────────────────────────
-- 2. 이용 정지 (SUSPENDED)
--
-- 약관 제7조의 "회원자격 제한·정지" 를 실제로 할 수단. 탈퇴와 달리 되돌릴 수 있다.
-- 로그인 실패 잠금(locked_until)과는 다르다 — 그건 시간이 지나면 풀리고, 이건 사람이 푼다.
-- ─────────────────────────────────────────────────────────────
ALTER TABLE member DROP CONSTRAINT member_status_check;
ALTER TABLE member ADD CONSTRAINT member_status_check
    CHECK (status IN ('ACTIVE', 'SUSPENDED', 'WITHDRAWN'));


-- ─────────────────────────────────────────────────────────────
-- 3. 탈퇴 = 익명화
--
-- 행은 남긴다(주문이 참조한다). 대신 사람을 알아볼 수 있는 값을 전부 지운다:
-- 이메일 · 이름 · 전화 · 비밀번호 해시 · 제공자 id.
-- 개인정보 보호법상 목적이 끝난 개인정보는 지체 없이 파기해야 한다. 주문 기록의 보존 의무
-- (전자상거래법 5년)는 주문 테이블이 진다 — 회원 프로필이 그 이유로 남을 필요는 없다.
--
-- 이메일이 비워지므로 같은 주소로 다시 가입할 수 있다. 간편가입도 마찬가지다.
--
-- 익명화된 행은 비밀번호도 제공자 id 도 없다. 그래서 자격 증명 모양 검사(V9)에서 뺀다.
-- ─────────────────────────────────────────────────────────────
ALTER TABLE member DROP CONSTRAINT member_credential_shape;
ALTER TABLE member ADD CONSTRAINT member_credential_shape CHECK (
    status = 'WITHDRAWN'
    OR (provider = 'LOCAL' AND password_hash IS NOT NULL AND provider_user_id IS NULL)
    OR (provider <> 'LOCAL' AND provider_user_id IS NOT NULL)
);

ALTER TABLE member ADD COLUMN withdrawn_at TIMESTAMPTZ;

ALTER TABLE member ADD CONSTRAINT member_withdrawn_shape CHECK (
    (status = 'WITHDRAWN') = (withdrawn_at IS NOT NULL)
);

COMMENT ON COLUMN member.withdrawn_at IS '탈퇴 시각. 탈퇴하면 이메일·이름·전화·자격 증명이 지워진다.';


-- ─────────────────────────────────────────────────────────────
-- 4. 관리자 조치 기록
--
-- 누가 · 언제 · 누구에게 · 무엇을. 권한 변경과 이용 정지는 되돌릴 수 있지만,
-- "누가 했는지" 는 기록이 없으면 되돌릴 수 없다.
--
-- 회원 상세를 열어본 것도 남긴다(VIEWED). 관리자 화면은 개인정보처리시스템이고,
-- 개인정보 안전성 확보조치 기준이 개인정보취급자의 접속 기록 보관을 요구한다.
--
-- actor_id 가 NULL 이면 서버 명령(create-admin)으로 한 것이다.
-- 행위자가 나중에 탈퇴해도 기록은 남아야 해서 FK 에 ON DELETE 를 걸지 않는다(행을 안 지우므로).
-- ─────────────────────────────────────────────────────────────
CREATE TABLE member_admin_log (
    id          BIGSERIAL   PRIMARY KEY,
    member_id   UUID        NOT NULL REFERENCES member (id),
    actor_id    UUID        REFERENCES member (id),
    action      TEXT        NOT NULL
                CHECK (action IN ('VIEWED', 'ROLE_CHANGED', 'SUSPENDED', 'REACTIVATED',
                                  'UNLOCKED', 'CREATED_BY_COMMAND')),
    -- 사람이 읽는 한 줄. 예) "MEMBER → ADMIN", 정지 사유.
    detail      TEXT        CHECK (detail IS NULL OR length(detail) <= 500),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_member_admin_log_member ON member_admin_log (member_id, created_at DESC);

COMMENT ON TABLE member_admin_log IS '관리자가 회원에게 한 일. 지우지 않는다.';


-- ─────────────────────────────────────────────────────────────
-- 5. 비밀번호 재설정 토큰
--
-- 토큰 원문은 저장하지 않는다. SHA-256 해시만 둔다.
-- DB 가 통째로 새도 거기 있는 값으로는 비밀번호를 바꿀 수 없다 — 원문은 메일에만 있다.
-- (BCrypt 가 아니라 SHA-256 인 이유: 토큰은 32바이트 무작위라 대입할 수 없다.
--  느린 해시는 사람이 고른 약한 비밀번호를 위한 것이다.)
--
-- 한 번 쓰면 used_at 이 찍히고 다시 못 쓴다. 30분이 지나도 못 쓴다.
-- 회원을 지우지 않으므로 FK 는 CASCADE 가 필요 없다.
-- ─────────────────────────────────────────────────────────────
CREATE TABLE password_reset_token (
    id          UUID        PRIMARY KEY,
    member_id   UUID        NOT NULL REFERENCES member (id),
    token_hash  TEXT        NOT NULL UNIQUE,
    expires_at  TIMESTAMPTZ NOT NULL,
    used_at     TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 요청 횟수 제한(시간당 몇 번)을 셀 때 쓴다.
CREATE INDEX idx_password_reset_member ON password_reset_token (member_id, created_at DESC);

COMMENT ON TABLE password_reset_token IS '비밀번호 재설정 링크. 원문이 아니라 SHA-256 해시를 저장한다.';
