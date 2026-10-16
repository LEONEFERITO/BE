-- ============================================================
-- V33 — 관리자 접속 · 열람 기록 (2026-10-16)
--
-- 개인정보 보호법 안전성 확보조치: 개인정보취급자(관리자)의 접속기록을 1년 이상 보관하고 위 · 변조되지 않게 둔다.
-- 관리자 API(/api/admin/**) 요청 한 건이 한 줄 — 누가(actor) · 언제 · 무엇을(method · path) · 어디서(ip) · 결과(status).
-- 쿼리스트링 · 본문은 남기지 않는다(개인정보가 섞인다). 주문번호 · 회원 id 는 경로에 있어 "누구 것을 봤는지" 는 남는다.
-- 지우지 않는다. 앱은 INSERT 만 한다(UPDATE · DELETE 코드 없음).
-- ============================================================

CREATE TABLE admin_access_log (
    id          BIGSERIAL   PRIMARY KEY,
    actor_id    UUID        REFERENCES member (id),
    -- 관리자가 탈퇴 · 이메일 변경해도 기록은 남아야 해서 이메일을 같이 적는다
    actor_email TEXT        NOT NULL CHECK (char_length(actor_email) BETWEEN 3 AND 254),
    method      TEXT        NOT NULL CHECK (method IN ('GET', 'POST', 'PUT', 'PATCH', 'DELETE')),
    path        TEXT        NOT NULL CHECK (char_length(path) BETWEEN 1 AND 500),
    status      SMALLINT    NOT NULL,
    ip          TEXT        CHECK (ip IS NULL OR char_length(ip) <= 45),
    user_agent  TEXT        CHECK (user_agent IS NULL OR char_length(user_agent) <= 200),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_admin_access_log_time  ON admin_access_log (created_at DESC);
CREATE INDEX idx_admin_access_log_actor ON admin_access_log (actor_id, created_at DESC);

COMMENT ON TABLE admin_access_log IS '관리자 접속 · 열람 기록. 1년 이상 보관, 지우지 않는다.';
