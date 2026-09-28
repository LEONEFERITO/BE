-- ============================================================
-- V4 — 회원 + 세션 저장소
--
-- 로그인/회원가입에 필요한 최소 범위다.
-- 포인트·등급·누적구매금액은 여기 없다 — 적립률·유효기간·승급 기준이 정해지지 않았고,
-- 규칙 없이 컬럼부터 만들면 반드시 다시 만든다. (BRAND_BRIEF.md 4장)
-- ============================================================

-- ─────────────────────────────────────────────────────────────
-- 회원
-- ─────────────────────────────────────────────────────────────
CREATE TABLE member (
    id                    UUID        PRIMARY KEY,

    /*
     * 이메일이 곧 로그인 아이디다.
     *
     * 소문자로 정규화해서 저장한다. 사람은 Kim@x.com 과 kim@x.com 을 같은 주소로 여기는데
     * 그대로 두면 두 계정이 생긴다. 애플리케이션이 소문자로 바꿔 넣고, DB 가 그걸 강제한다.
     * CHECK 를 거는 이유: 관리자 SQL 이나 배치가 대문자로 밀어넣는 경로를 막기 위해서다.
     */
    email                 TEXT        NOT NULL UNIQUE
                          CHECK (email = lower(email) AND email LIKE '%@%'),

    /*
     * BCrypt 해시. 평문 비밀번호는 어디에도 저장하지 않는다.
     *
     * 길이를 제한하지 않는다. 알고리즘을 바꾸면(Argon2 등) 접두사와 길이가 달라지는데,
     * 컬럼 길이를 박아두면 그날 마이그레이션이 하나 더 필요해진다.
     */
    password_hash         TEXT        NOT NULL,

    name                  TEXT        NOT NULL,

    -- 배송·주문 확인용. 가입 시점에는 안 받을 수 있다.
    phone                 TEXT,

    /*
     * WITHDRAWN 은 행을 지우는 대신 남긴다. 주문 이력이 회원을 참조하게 될 텐데
     * 탈퇴할 때마다 행을 지우면 과거 주문의 주인이 사라진다.
     * 로그인 처리에서는 WITHDRAWN 을 "없는 계정" 과 똑같이 취급한다.
     */
    status                TEXT        NOT NULL DEFAULT 'ACTIVE'
                          CHECK (status IN ('ACTIVE', 'WITHDRAWN')),

    /*
     * 로그인 실패 누적과 잠금 해제 시각. 무차별 대입을 늦추기 위한 것이다.
     *
     * 왜 DB 인가: 메모리에 두면 재배포·인스턴스 증설에 초기화된다. 공격자는 그걸 노린다.
     * 시도 횟수는 성공하면 0 으로 되돌린다.
     */
    failed_login_attempts SMALLINT    NOT NULL DEFAULT 0 CHECK (failed_login_attempts >= 0),
    locked_until          TIMESTAMPTZ,

    last_login_at         TIMESTAMPTZ,

    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);

COMMENT ON TABLE  member IS '회원. 이메일이 로그인 아이디다.';
COMMENT ON COLUMN member.email IS '소문자로 정규화되어 저장된다. CHECK 가 강제한다.';
COMMENT ON COLUMN member.password_hash IS 'BCrypt 해시. 평문은 저장하지 않는다.';
COMMENT ON COLUMN member.status IS 'WITHDRAWN 은 로그인에서 없는 계정과 동일하게 취급한다.';
COMMENT ON COLUMN member.locked_until IS '이 시각까지 로그인 시도를 거부한다. 무차별 대입 지연용.';

CREATE TRIGGER trg_member_updated_at
    BEFORE UPDATE ON member
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();


-- ─────────────────────────────────────────────────────────────
-- 세션 저장소 (Spring Session JDBC)
--
-- 아래 두 테이블은 Spring Session 이 정한 스키마를 **그대로** 옮긴 것이다
-- (spring-session-jdbc-4.1.1.jar 의 schema-postgresql.sql).
-- 이름·타입을 고치면 Spring Session 이 못 읽는다. 손대지 않는다.
--
-- 왜 애플리케이션이 만들게 두지 않는가(initialize-schema: always):
--   스키마의 주인은 Flyway 하나여야 한다. 두 주인이 있으면 운영 DB 에서
--   "이 테이블은 누가 만들었지" 를 알 수 없고, 버전 이력에도 남지 않는다.
--
-- 왜 세션을 DB 에 두는가:
--   메모리 세션은 재배포마다 전부 풀린다. 손님이 장바구니를 담다가 로그아웃된다.
--   DB 에 두면 재시작에도 살아남고, 인스턴스를 늘려도 같은 세션을 본다.
--   나중에 "다른 기기에서 로그아웃" 을 붙일 때도 이 행을 지우면 끝난다.
-- ─────────────────────────────────────────────────────────────
CREATE TABLE SPRING_SESSION (
	PRIMARY_ID CHAR(36) NOT NULL,
	SESSION_ID CHAR(36) NOT NULL,
	CREATION_TIME BIGINT NOT NULL,
	LAST_ACCESS_TIME BIGINT NOT NULL,
	MAX_INACTIVE_INTERVAL INT NOT NULL,
	EXPIRY_TIME BIGINT NOT NULL,
	PRINCIPAL_NAME VARCHAR(100),
	CONSTRAINT SPRING_SESSION_PK PRIMARY KEY (PRIMARY_ID)
);

CREATE UNIQUE INDEX SPRING_SESSION_IX1 ON SPRING_SESSION (SESSION_ID);
CREATE INDEX SPRING_SESSION_IX2 ON SPRING_SESSION (EXPIRY_TIME);
CREATE INDEX SPRING_SESSION_IX3 ON SPRING_SESSION (PRINCIPAL_NAME);

CREATE TABLE SPRING_SESSION_ATTRIBUTES (
	SESSION_PRIMARY_ID CHAR(36) NOT NULL,
	ATTRIBUTE_NAME VARCHAR(200) NOT NULL,
	ATTRIBUTE_BYTES BYTEA NOT NULL,
	CONSTRAINT SPRING_SESSION_ATTRIBUTES_PK PRIMARY KEY (SESSION_PRIMARY_ID, ATTRIBUTE_NAME),
	CONSTRAINT SPRING_SESSION_ATTRIBUTES_FK FOREIGN KEY (SESSION_PRIMARY_ID) REFERENCES SPRING_SESSION(PRIMARY_ID) ON DELETE CASCADE
);
