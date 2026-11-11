-- ============================================================
-- V54 — 관리자 2단계 인증 (2026-11-11 보안 강화 "레오네페리토 보안 레벨 올리기")
--
-- 관리자 로그인은 비밀번호 다음에 OTP 앱(Google OTP · Microsoft Authenticator 등)의 6자리를 한 번 더 받는다(TOTP, RFC 6238).
--   member.otp_secret       등록한 OTP 비밀 키(Base32). 처음 관리자 로그인 때 QR 로 앱에 등록하면 채워진다
--   member.otp_enabled_at   등록한 시각. NULL 이면 다음 관리자 로그인 때 등록부터 한다
--   member.otp_last_step    마지막으로 받은 6자리의 30초 칸 번호 — 같은 6자리를 두 번 쓰지 못하게(엿본 번호 재사용 방지)
--   admin_login_challenge   비밀번호를 통과한 뒤 6자리를 받기까지의 5분짜리 표. 5번 틀리면 처음부터
-- 폰을 잃으면 최고 관리자가 회원 관리에서 OTP 를 초기화한다(OTP_RESET 기록).
-- 손님용 로그인으로 들어온 관리자 계정은 손님 권한만 받는다(AuthController) — 관리자 화면은 이 길로만 열린다.
-- ============================================================

ALTER TABLE member
    ADD COLUMN otp_secret     TEXT CHECK (otp_secret IS NULL OR otp_secret ~ '^[A-Z2-7]{16,64}$'),
    ADD COLUMN otp_enabled_at TIMESTAMPTZ,
    ADD COLUMN otp_last_step  BIGINT;

COMMENT ON COLUMN member.otp_secret IS '관리자 OTP 비밀 키(Base32, V54). NULL 이면 미등록';

CREATE TABLE admin_login_challenge (
    id              UUID         PRIMARY KEY,
    member_id       UUID         NOT NULL REFERENCES member (id) ON DELETE CASCADE,
    -- 처음 등록이면 이번에 만든 비밀 키. 6자리를 맞히면 member.otp_secret 으로 옮긴다
    pending_secret  TEXT         CHECK (pending_secret IS NULL OR pending_secret ~ '^[A-Z2-7]{16,64}$'),
    attempts        SMALLINT     NOT NULL DEFAULT 0,
    expires_at      TIMESTAMPTZ  NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_admin_login_challenge_member ON admin_login_challenge (member_id);

COMMENT ON TABLE admin_login_challenge IS '관리자 로그인 2단계 대기(V54) — 비밀번호 통과 뒤 5분, 5번까지';

-- 회원 관리 기록에 «OTP 초기화» 를 남긴다
ALTER TABLE member_admin_log DROP CONSTRAINT member_admin_log_action_check;
ALTER TABLE member_admin_log ADD CONSTRAINT member_admin_log_action_check
    CHECK (action IN ('VIEWED', 'ROLE_CHANGED', 'SUSPENDED', 'REACTIVATED', 'UNLOCKED', 'CREATED_BY_COMMAND', 'OTP_RESET'));
