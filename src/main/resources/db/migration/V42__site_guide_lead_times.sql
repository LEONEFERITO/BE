-- ============================================================
-- V42 — 가이드 페이지 "제작 소요 기간" 표 (관리자에서 고친다, 2026-10-28)
--
-- 표가 자주 바뀐다(디자이너: "제작 소요기간이 바뀌어서"). 한 줄짜리 사이트 설정 표(site_business)에 칸 하나를 붙인다.
-- 한 줄에 한 품목: "품목<탭>기간". 줄은 줄바꿈으로 나눈다. 손님 화면은 빌드할 때 받는다.
-- 처음 값은 지금 가이드 페이지에 박혀 있던 표 그대로다.
-- ============================================================

ALTER TABLE site_business
    ADD COLUMN guide_lead_times TEXT NOT NULL
        DEFAULT E'자켓 / 팬츠 / 베스트\t14~18일\n셔츠 / 구두 / 로퍼\t7~10일\n넥타이\t바로 출고'
        CHECK (char_length(guide_lead_times) BETWEEN 1 AND 2000);

COMMENT ON COLUMN site_business.guide_lead_times IS '가이드 페이지 제작 소요 기간 표 — 한 줄에 "품목<탭>기간"';
