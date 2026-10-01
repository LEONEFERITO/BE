-- ============================================================
-- V16 — 교환·반품 사진 (불량 · 오배송 증거)
--
-- 흐름: 손님이 사진을 먼저 올린다(return_id 없음) → 신청할 때 그 사진 id 를 함께 보내면 붙는다.
-- 먼저 올리는 이유: 신청 버튼을 누르는 순간 사진 다섯 장을 같이 보내면 하나만 실패해도 신청 전체가 실패한다.
--
-- 규칙(서버가 지킨다 — ReturnPhotoService):
--   · 내가 올렸고 아직 어디에도 붙지 않은 사진만 붙일 수 있다.
--   · 한 신청에 5장까지. 붙지 않은 채 쌓인 사진은 한 사람당 10장까지(그 이상은 업로드를 막는다).
--
-- 이미지 바이트는 media_asset 과 같은 저장소에 둔다(형식 판별 · 크기 제한이 같은 길을 탄다).
-- 주소는 상품 사진처럼 추측할 수 없는 UUID 경로다.
-- ============================================================

CREATE TABLE return_photo (
    id          UUID        PRIMARY KEY,
    member_id   UUID        NOT NULL REFERENCES member (id),
    media_id    UUID        NOT NULL UNIQUE REFERENCES media_asset (id),
    -- 업로드 시점에 만든 공개 주소. 화면이 그대로 쓴다(주문 항목의 image_url 과 같은 이유).
    url         TEXT        NOT NULL,
    -- NULL = 올렸지만 아직 신청에 붙지 않았다
    return_id   UUID        REFERENCES return_request (id),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_return_photo_return ON return_photo (return_id);
CREATE INDEX idx_return_photo_pending ON return_photo (member_id) WHERE return_id IS NULL;
