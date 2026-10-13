-- ============================================================
-- V28 — 기존 회원에 간편 로그인 연결 (2026-10-13 "이미 가입된 이메일이라 간편 로그인이 안 된다 → 2번")
--
-- 이메일 · 비밀번호로 가입한 회원이 같은 이메일의 카카오 · 네이버로 들어오면, 제공자가 그 이메일을
-- **본인 확인된 이메일**이라고 알려 줄 때만 이 회원에 연결해서 로그인시킨다(SocialLoginService).
--   카카오: kakao_account.is_email_valid · is_email_verified 가 둘 다 true
--   네이버: 확인 여부를 주지 않는다 — 그 네이버 계정 자체의 주소인 @naver.com 만 연결
-- 관리자 계정은 연결하지 않는다(비밀번호 로그인만). 확인되지 않은 이메일은 지금처럼 거절한다.
--
-- member.provider · provider_user_id(V9)는 "처음 가입한 방법" 이라 그대로 둔다. 연결은 이 표에만 쌓인다 —
-- 한 회원에 카카오 · 네이버를 하나씩 붙일 수 있다. 탈퇴하면 연결을 지운다(같은 계정으로 다시 가입할 수 있게).
-- ============================================================

CREATE TABLE member_social_link (
    id               UUID        PRIMARY KEY,
    member_id        UUID        NOT NULL REFERENCES member (id) ON DELETE CASCADE,
    provider         TEXT        NOT NULL CHECK (provider IN ('KAKAO', 'NAVER')),
    provider_user_id TEXT        NOT NULL,
    linked_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- 제공자 계정 하나는 회원 하나에만
    CONSTRAINT member_social_link_account UNIQUE (provider, provider_user_id),
    -- 회원 하나에 제공자마다 하나만
    CONSTRAINT member_social_link_one_per_provider UNIQUE (member_id, provider)
);

COMMENT ON TABLE member_social_link IS '기존 회원에 붙인 간편 로그인. 확인된 이메일이 같을 때만 연결(관리자 제외)';
