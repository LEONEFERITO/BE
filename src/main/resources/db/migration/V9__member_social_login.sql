/*
 * 간편가입 (카카오 · 네이버).
 *
 * 간편가입 회원은 비밀번호가 없다 — 그래서 password_hash 를 NULL 허용으로 바꾼다.
 * 대신 "자체 가입이면 비밀번호가 있고, 간편가입이면 제공자 id 가 있다" 를 CHECK 로 강제한다.
 * 둘 다 없는 행, 둘 다 있는 행은 만들 수 없다.
 *
 * 이메일은 여전히 NOT NULL 이다. 제공자가 이메일을 안 주면(동의 안 함) 가입을 거절한다 —
 * 이메일이 곧 로그인 아이디이고, 없는 채로 받으면 같은 사람이 이메일로 가입할 때 계정이 둘 된다.
 */
ALTER TABLE member
    ALTER COLUMN password_hash DROP NOT NULL,
    ADD COLUMN provider TEXT NOT NULL DEFAULT 'LOCAL'
        CHECK (provider IN ('LOCAL', 'KAKAO', 'NAVER')),
    ADD COLUMN provider_user_id TEXT;

ALTER TABLE member ADD CONSTRAINT member_credential_shape CHECK (
    (provider = 'LOCAL' AND password_hash IS NOT NULL AND provider_user_id IS NULL)
    OR (provider <> 'LOCAL' AND provider_user_id IS NOT NULL)
);

-- 같은 제공자 계정으로 회원이 둘 생기지 않게. LOCAL 은 provider_user_id 가 NULL 이라 빠진다.
CREATE UNIQUE INDEX uq_member_provider_user
    ON member (provider, provider_user_id) WHERE provider <> 'LOCAL';

COMMENT ON COLUMN member.provider IS 'LOCAL = 이메일·비밀번호 가입. 그 외는 간편가입 제공자.';
COMMENT ON COLUMN member.provider_user_id IS '제공자가 준 사용자 id. LOCAL 이면 NULL.';
