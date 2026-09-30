-- 상품별 인스타그램 게시물 주소 (고객 요청, 2026-09-30).
--
-- 없는 상품이 있다. NULL 이면 상세 화면이 버튼을 숨긴다.
--
-- instagram.com 주소만 받는다. 손님이 브랜드 페이지에서 누르는 링크라,
-- 다른 곳(피싱 · javascript:)을 가리키면 그 책임이 브랜드로 돌아온다.
-- 요청 검증(AdminProductRequests)이 먼저 막고, 이 제약은 관리자 SQL 로
-- 직접 넣는 경로까지 막는 마지막 방어선이다.
ALTER TABLE product
    ADD COLUMN instagram_url TEXT;

ALTER TABLE product ADD CONSTRAINT product_instagram_url_host
    CHECK (instagram_url IS NULL
           OR instagram_url ~ '^https://(www\.)?instagram\.com/');
