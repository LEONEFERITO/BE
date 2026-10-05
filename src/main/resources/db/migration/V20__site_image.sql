-- ============================================================
-- V20 — 사이트 사진 칸 (관리자가 바꾼다)
--
-- 손님 화면에서 "상품 사진이 아닌 사진" 이 놓이는 자리. 지금은 한 칸이다:
--   OFFLINE_SHOP   메인 OFFLINE SHOP 구간의 왼쪽 사진 (2026-10-05 고객 디자인 가이드)
-- 사진은 고객이 나중에 준다. 그때 개발자를 거치지 않고 관리자 화면에서 올리게 한다.
--
-- 칸은 행으로 미리 심어 둔다 — 관리자는 "칸을 만드는" 일이 없고 있는 칸의 사진만 바꾼다.
-- media_id 가 NULL 이면 손님 화면은 코드의 기본 사진을 쓴다(사진이 지워져도 기본으로 돌아간다).
-- 손님 화면은 빌드 때 받는다 — 저장하면 손님 화면을 다시 만든다(FrontRebuildTrigger, 1~2분).
--
-- 칸을 늘리려면: 이 CHECK 를 고치는 마이그레이션 + 행 INSERT + SiteImageSlot(서버) · SiteImageSlot(프론트).
-- ============================================================

CREATE TABLE site_image (
    slot        TEXT        PRIMARY KEY CONSTRAINT site_image_slot_check CHECK (slot IN ('OFFLINE_SHOP')),
    media_id    UUID        REFERENCES media_asset (id) ON DELETE SET NULL,
    -- 사진 설명(대체 텍스트). 비어 있으면 장식으로 취급한다 — 옆의 글자가 내용을 전하는 자리다.
    alt         TEXT        NOT NULL DEFAULT '' CHECK (length(alt) <= 200),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TRIGGER trg_site_image_updated_at
    BEFORE UPDATE ON site_image
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

INSERT INTO site_image (slot) VALUES ('OFFLINE_SHOP');
