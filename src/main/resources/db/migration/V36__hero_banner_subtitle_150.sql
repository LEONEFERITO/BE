-- 메인 배너 "제목 아래 줄" 을 80자 → 150자로 (2026-10-20 디자이너 시안의 세 줄 설명이 122자라 80자에 안 들어갔다).
-- V25 가 표를 만들 때 붙인 자동 이름(hero_banner_subtitle_check)을 풀고 새 길이로 다시 건다.
-- API 의 @Size 도 150 으로 같이 바뀐다(SiteImageController.BannerInput). 관리자 화면의 글자 수(HERO_LIMITS.subtitle)도 같은 값.
ALTER TABLE hero_banner DROP CONSTRAINT IF EXISTS hero_banner_subtitle_check;
ALTER TABLE hero_banner ADD CONSTRAINT hero_banner_subtitle_check CHECK (char_length(subtitle) <= 150);
