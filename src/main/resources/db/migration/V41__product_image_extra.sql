-- 상품 메인 사진 5장 (2026-10-28 디자이너 "똑같은 규격 사진 5개 등록") — 대표 · 착용 · 디테일에 추가 사진 두 칸.
-- 종류마다 한 장은 그대로다(AdminProductRequests.Save.isImageCountValid). 상세 화면은 다섯 장을 같은 크기 네모 줄로 보여 준다.
ALTER TABLE product_image DROP CONSTRAINT product_image_kind_check;
ALTER TABLE product_image
    ADD CONSTRAINT product_image_kind_check CHECK (kind IN ('MAIN', 'WORN', 'DETAIL', 'EXTRA_1', 'EXTRA_2', 'CUTOUT', 'STORY'));
