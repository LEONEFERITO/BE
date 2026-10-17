-- 메인 배너 버튼 켜기/끄기 (2026-10-17 요청 "버튼은 쓸지 안 쓸지 관리자에서 설정").
-- 지금까지의 배너는 전부 버튼이 있었으므로 기본값은 켬 — 기존 행의 화면이 바뀌지 않는다.
-- 꺼 두면 cta_label · cta_url 은 그대로 저장돼 있지만 손님 화면에 버튼이 나가지 않는다(다시 켜면 그 값이 돌아온다).
ALTER TABLE hero_banner
    ADD COLUMN cta_enabled BOOLEAN NOT NULL DEFAULT TRUE;

COMMENT ON COLUMN hero_banner.cta_enabled IS '손님 화면에 버튼을 내보내는가. 끄면 cta_label · cta_url 은 보관만 된다';
