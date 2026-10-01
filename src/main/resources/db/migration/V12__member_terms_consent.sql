-- ============================================================
-- V12 — 약관 동의 · 만 14세 확인 기록
--
-- 가입할 때 약관에 동의했다는 사실을 남긴다. "언제 · 어느 판 약관에" 동의했는지가
-- 분쟁 때 증빙이 된다. 약관을 개정하면 terms_version 이 바뀌고, 그 뒤 가입자는 새 판에 동의한 것이다.
--
-- 만 14세 확인은 따로 컬럼을 두지 않는다 — 가입 요청이 확인 없이는 통과하지 못하므로(서버 검증)
-- terms_agreed_at 이 있으면 그 시점에 만 14세 이상임을 확인한 것이다.
-- 만 14세 미만은 법정대리인 동의가 필요해서(개인정보 보호법 제22조의2) 아예 받지 않는다.
--
-- 이전 가입자(테스트 계정뿐)는 NULL 이다. 운영 오픈 전이라 채우지 않는다.
-- ============================================================

ALTER TABLE member
    ADD COLUMN terms_agreed_at TIMESTAMPTZ,
    -- 어떤 경로로 동의했는가: FORM(가입 화면의 체크) · SOCIAL_NOTICE(간편가입 버튼 위 고지를 보고 진행)
    ADD COLUMN terms_agreed_via TEXT CHECK (terms_agreed_via IS NULL OR terms_agreed_via IN ('FORM', 'SOCIAL_NOTICE')),
    ADD COLUMN terms_version TEXT CHECK (terms_version IS NULL OR length(terms_version) <= 40);

COMMENT ON COLUMN member.terms_agreed_at IS '이용약관 동의 · 만 14세 확인 시각';
COMMENT ON COLUMN member.terms_version IS '동의한 약관 판. 약관 개정 시 TermsVersion.CURRENT 를 바꾼다';
