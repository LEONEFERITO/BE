-- 메인 배너 글자 크기 — 작은 줄 · 큰 제목 · 제목 아래 줄을 관리자가 px 로 (2026-10-21 요청 "세 가지 각각 픽셀 조절").
-- PC 화면에만 쓴다(휴대폰은 화면 폭에 맞춘 자동 크기 그대로). 비우면(NULL) 디자이너 기준 크기 — 기존 배너는 그대로다.
-- 범위는 화면이 깨지지 않을 만큼만 연다. API 의 @Min/@Max(SiteImageController.BannerInput) · 관리자 화면(HERO_SIZE)과 같은 값.
ALTER TABLE hero_banner
    ADD COLUMN eyebrow_px  INTEGER CHECK (eyebrow_px BETWEEN 10 AND 40),
    ADD COLUMN title_px    INTEGER CHECK (title_px BETWEEN 24 AND 120),
    ADD COLUMN subtitle_px INTEGER CHECK (subtitle_px BETWEEN 12 AND 40);

COMMENT ON COLUMN hero_banner.eyebrow_px IS 'PC 작은 줄(제목 위) 글자 크기 px. NULL 이면 기본';
COMMENT ON COLUMN hero_banner.title_px IS 'PC 큰 제목 글자 크기 px. NULL 이면 기본';
COMMENT ON COLUMN hero_banner.subtitle_px IS 'PC 제목 아래 줄 글자 크기 px. NULL 이면 기본';
