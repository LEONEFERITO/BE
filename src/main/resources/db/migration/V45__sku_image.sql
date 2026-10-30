-- ============================================================
-- V45 — 사이즈마다 보여 줄 사진 (2026-10-30 디자이너 "사이즈 눌렀을 때 각각의 이미지를 선정해서 그 이미지가 보이게끔")
--
-- product_sku.image_kind — 손님이 이 사이즈를 누르면 큰 사진이 이 칸의 사진으로 바뀐다.
-- 사진 자체가 아니라 메인 사진 다섯 칸 중 어느 칸인지를 적는다: 사진을 바꿔 올려도 고른 칸은 그대로다.
-- NULL 이면 사이즈를 눌러도 사진이 바뀌지 않는다(지금까지와 같다).
-- ============================================================

ALTER TABLE product_sku
    ADD COLUMN image_kind TEXT CHECK (image_kind IS NULL OR image_kind IN ('MAIN', 'WORN', 'DETAIL', 'EXTRA_1', 'EXTRA_2'));

COMMENT ON COLUMN product_sku.image_kind IS '이 사이즈를 누르면 보여 줄 메인 사진 칸. NULL 이면 사진을 바꾸지 않는다';
