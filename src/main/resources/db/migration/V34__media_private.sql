-- ============================================================
-- V34 — 비공개 이미지 (2026-10-16 교환·반품 증빙 사진)
--
-- 손님이 올린 증빙 사진이 /media/** 로 누구나 열렸다(UUID 주소라 추측은 어렵지만 주소가 새면 끝).
-- 비공개 표시가 있는 이미지는 /media/** 가 내보내지 않고, 주인(손님)과 관리자만 /api/returns/photos/{id} 로 본다.
-- 지금까지 올린 증빙 사진도 전부 비공개로 돌린다.
-- ============================================================

ALTER TABLE media_asset ADD COLUMN private BOOLEAN NOT NULL DEFAULT FALSE;

UPDATE media_asset SET private = TRUE WHERE id IN (SELECT media_id FROM return_photo);

-- 이미 붙은 사진의 주소도 새 경로로 — 옛 주소(…/media/날짜/uuid.jpg)는 이제 404 다.
-- 앞부분(https://api 도메인)은 그대로 두고 /media/… 만 /api/returns/photos/{id} 로 바꾼다. 로컬(/media/…)도 같은 규칙.
UPDATE return_photo SET url = regexp_replace(url, '/media/.*$', '/api/returns/photos/' || id) WHERE url ~ '/media/';

COMMENT ON COLUMN media_asset.private IS 'TRUE 면 /media/** 로 내보내지 않는다 — 권한 있는 경로로만.';
