package com.leoneferito.product.api;

import com.leoneferito.product.MeasurementPart;
import com.leoneferito.product.Product;
import com.leoneferito.product.ProductCategory;
import com.leoneferito.product.ProductImageKind;
import com.leoneferito.product.ProductLine;
import com.leoneferito.product.ProductStyle;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * 관리자 상품 등록·수정 요청.
 *
 * <p><b>글자 수 상한이 화면과 같은 값이어야 한다.</b> 화면에서만 막으면 API 를 직접
 * 호출하는 순간 뚫리고, 서버에서만 막으면 관리자가 다 쓰고 나서야 거부당한다.
 * 양쪽에 같은 숫자를 두되 <b>진짜 기준은 여기</b>다 — 화면 값이 이걸 넘으면 안 된다.
 *
 * <p>상한을 정한 기준은 "그 글자가 실제로 놓이는 자리" 다:
 * 이름은 카드 두 줄, 한 줄 설명은 목록 한 줄, 본문은 상세 한 덩어리.
 * 상한이 없으면 관리자가 붙여넣은 긴 문장이 카드 레이아웃을 무너뜨린다.
 */
public final class AdminProductRequests {

    private AdminProductRequests() {
    }

    /** 이름: 카드에서 두 줄로 잘린다(line-clamp-2). 그 두 줄에 들어갈 만큼. */
    public static final int NAME_MAX = 40;
    /** 한 줄 설명: 목록 카드 아래 한 줄. */
    public static final int SUMMARY_MAX = 60;
    /** 상세 본문류: 상세 페이지 한 덩어리. */
    public static final int BODY_MAX = 2000;
    /** 원단·관리 정보: 표 아래 문단. */
    public static final int SHORT_BODY_MAX = 500;
    /** 사이즈 차트 대체 텍스트: 표 전체를 한 문단으로 옮겨 적을 만큼. */
    public static final int SIZE_CHART_ALT_MAX = 300;
    /** 인스타그램 게시물 주소. 실제 주소는 60자 안팎이다. 여유를 두되 문단은 못 들어오게. */
    public static final int INSTAGRAM_URL_MAX = 300;
    /** 상품정보제공고시 한 줄 (색상 · 제조자 · 제조국 · 제조연월). DB CHECK(V11)와 같은 값. */
    public static final int NOTICE_MAX = 100;

    public record Save(
            /*
             * slug 는 URL 에 그대로 나간다. 소문자·숫자·하이픈만 받는다 —
             * 한글이나 공백이 들어가면 주소가 퍼센트 인코딩으로 도배되고,
             * 대문자는 서버 환경에 따라 다른 주소로 취급될 수 있다.
             */
            @NotBlank
            @Size(max = 80)
            @Pattern(regexp = "^[a-z0-9]+(-[a-z0-9]+)*$",
                    message = "영문 소문자 · 숫자 · 하이픈만 사용할 수 있습니다.")
            String slug,

            @Size(max = NAME_MAX) String name,
            @NotNull ProductCategory category,
            /** 라인. 라인 구분 없이 올리는 상품은 null (V48) */
            ProductLine line,
            /** 세부 분류. 트라우저 · 신발만, 없으면 null (V21) */
            ProductStyle style,

            // 원화. 소수 단위가 없으므로 정수다.
            @Positive Long priceKrw,
            @Positive Long listPriceKrw,

            @Size(max = SUMMARY_MAX) String summary,
            @Size(max = BODY_MAX) String description,
            @Size(max = BODY_MAX) String intent,
            @Size(max = BODY_MAX) String features,
            @Size(max = SHORT_BODY_MAX) String fabric,
            @Size(max = SHORT_BODY_MAX) String care,

            // 상품정보제공고시 (V11). 한 줄짜리 값이라 100자.
            @Size(max = NOTICE_MAX) String color,
            @Size(max = NOTICE_MAX) String manufacturer,
            @Size(max = NOTICE_MAX) String countryOfOrigin,
            @Size(max = NOTICE_MAX) String manufacturedOn,

            @Min(100) @Max(250) Short modelHeightCm,
            @Min(30) @Max(200) Short modelWeightKg,
            @Size(max = 20) String modelSize,

            /*
             * 주문 후 제작 기간. 상한을 365일로 둔다 — 그보다 길면 입력 실수일 가능성이
             * 훨씬 높고, 실수를 그대로 저장하면 결제 화면에 그대로 표시된다.
             */
            @Min(1) @Max(365) Short leadTimeDays,

            Integer displayOrder,

            /** 상세 사이즈 차트 이미지. 없으면 null. */
            UUID sizeChartMediaId,

            /*
             * 차트 대체 텍스트. 일반 사진(120자)보다 길게 허용한다 —
             * "사이즈 차트" 한 마디로는 스크린리더 사용자에게 아무것도 전달되지 않는다.
             * 어떤 항목을 어느 사이즈 범위로 싣고 있는지가 들어가야 한다.
             */
            @Size(max = SIZE_CHART_ALT_MAX) String sizeChartAlt,

            /*
             * 인스타그램 게시물 주소. 없으면 null.
             * https + instagram.com 만 받는다 — 손님이 누르는 링크라 다른 곳을 가리키면 안 되고,
             * 특히 javascript: 같은 주소가 href 에 들어가면 누르는 순간 스크립트가 실행된다.
             */
            @Size(max = INSTAGRAM_URL_MAX)
            @Pattern(regexp = "^https://(www\\.)?instagram\\.com/[A-Za-z0-9._~/?=&%+-]*$",
                    message = "인스타그램 주소(https://www.instagram.com/...)만 넣을 수 있습니다.")
            String instagramUrl,

            @Valid @Size(max = 40) List<Image> images,
            @Valid List<Sku> skus,
            /** 관리자 전용 정보(V39). 안 보내면(null) 저장된 값을 그대로 둔다 — 예전 화면 · 다른 경로의 요청이 지우지 않게. */
            @Valid Internal internal,
            /** 제작 기간 최소(V40). 있으면 "최소~leadTimeDays일" — leadTimeDays 가 최대다. 없으면 한 값 */
            @Min(1) @Max(365) Short leadTimeMinDays,
            /** 수트 베스트 금액(V44). 2피스 = 판매가 − 이 값. 비우면 3피스만 판다. 베스트가 있는 수트(레오네)에만 */
            @Positive Long vestPriceKrw,
            /** 컬러 옵션(V46). 관리자가 놓은 순서대로. 안 보내면(null) 저장된 값을 그대로 둔다 — 엑셀 등록 · 예전 화면이 지우지 않게 */
            @Valid @Size(max = 12) List<Color> colors,
            /** 굽높이 옵션(V49). 안 보내면(null) 저장된 값을 그대로 둔다 — 엑셀 등록 · 예전 화면이 끄지 않게 */
            Boolean heelOption,
            /** 굽 추가 금액(V50) — +1cm ~ +6cm 순서, 비운 단계는 0. 안 보내면(null) 저장된 값을 그대로 둔다 */
            @Size(max = 6) List<@jakarta.validation.constraints.NotNull @jakarta.validation.constraints.PositiveOrZero
                    @jakarta.validation.constraints.Max(10_000_000) Long> heelPrices,
            /** 함께 구매 상품(V52) — 관리자가 놓은 순서. 안 보내면(null) 저장된 값을 그대로 둔다 */
            @Valid @Size(max = 12) List<Addon> addons) {

        /** 같은 상품을 두 번 붙이면 손님 화면에 같은 줄이 둘 생긴다 */
        @AssertTrue(message = "같은 상품을 함께 구매 상품으로 두 번 넣었습니다.")
        public boolean isAddonUnique() {
            if (addons == null) {
                return true;
            }
            List<UUID> ids = addons.stream().filter(a -> a != null && a.productId() != null).map(Addon::productId).toList();
            return ids.size() == new java.util.HashSet<>(ids).size();
        }

        /** 굽높이는 보통 사이즈에만 붙는다 — 수트 구성(조각별 사이즈)과 함께 켜면 손님 화면이 줄을 만들 수 없다 */
        @AssertTrue(message = "굽높이 옵션은 수트 구성(조각별 사이즈) 상품에는 켤 수 없습니다.")
        public boolean isHeelOptionValid() {
            return !Boolean.TRUE.equals(heelOption) || skus == null || skus.stream().noneMatch(k -> k != null && k.piece() != null);
        }

        /** 같은 이름의 컬러를 두 번 넣으면 손님이 어느 것을 고른 건지 모른다. 앞뒤 공백 · 대소문자는 같은 이름으로 본다 */
        @AssertTrue(message = "같은 이름의 컬러를 두 번 넣었습니다.")
        public boolean isColorUnique() {
            if (colors == null) {
                return true;
            }
            List<String> names = colors.stream().filter(c -> c != null && c.name() != null)
                    .map(c -> c.name().trim().toLowerCase(java.util.Locale.ROOT)).toList();
            return names.size() == new java.util.HashSet<>(names).size();
        }

        private boolean hasPiece(Product.Piece piece) {
            return skus != null && skus.stream().anyMatch(k -> k != null && k.piece() == piece);
        }

        /**
         * 수트 구성(V43 · V44) — 사이즈에 조각을 붙이면 전부 붙이고(보통 사이즈와 섞지 않는다), 자켓 · 팬츠가 한 사이즈 이상 있다.
         * 베스트는 있어도 되고 없어도 된다(없으면 2피스만 — 페리토). 섞이면 손님 화면이 칸을 정할 수 없다.
         */
        @AssertTrue(message = "수트 구성은 자켓 · 팬츠 사이즈를 넣어야 하고, 보통 사이즈와 섞을 수 없습니다.")
        public boolean isSuitSkusValid() {
            if (skus == null) {
                return true;
            }
            long pieced = skus.stream().filter(k -> k != null && k.piece() != null).count();
            if (pieced == 0) {
                return true;
            }
            return pieced == skus.size() && hasPiece(Product.Piece.JACKET) && hasPiece(Product.Piece.PANTS);
        }

        /** 3피스는 레오네에만 있다(2026-10-31 디자이너 답변) — 페리토 수트에는 베스트를 넣지 못한다. */
        @AssertTrue(message = "3피스(베스트)는 레오네 라인에만 있습니다. 페리토 수트는 자켓 · 팬츠만 넣어 주세요.")
        public boolean isVestLineValid() {
            return line != ProductLine.FERITO || !hasPiece(Product.Piece.VEST);
        }

        /** 베스트 금액은 베스트가 있는 수트에만, 판매가보다 작게 — 2피스 값(판매가 − 베스트)이 0 이하가 되면 안 된다. */
        @AssertTrue(message = "베스트 금액은 베스트 사이즈가 있는 수트에만, 판매가보다 작게 넣습니다.")
        public boolean isVestPriceValid() {
            return vestPriceKrw == null || (hasPiece(Product.Piece.VEST) && (priceKrw == null || vestPriceKrw < priceKrw));
        }

        /** 같은 사이즈(수트는 같은 조각의 같은 사이즈)를 두 번 넣으면 저장할 수 없다 — 손님이 어느 칸을 고른 건지 모른다. */
        @AssertTrue(message = "같은 사이즈를 두 번 넣었습니다.")
        public boolean isSkuUnique() {
            if (skus == null) {
                return true;
            }
            List<String> keys = skus.stream().filter(k -> k != null && k.size() != null)
                    .map(k -> (k.piece() == null ? "" : k.piece().name()) + "|" + k.size().trim()).toList();
            return keys.size() == new java.util.HashSet<>(keys).size();
        }

        /** 범위는 최소가 최대보다 작아야 한다("18~14" 는 400). 같으면 한 값으로 저장한다. */
        @AssertTrue(message = "제작 기간 최소가 최대보다 클 수 없습니다.")
        public boolean isLeadTimeRangeValid() {
            return leadTimeMinDays == null || leadTimeDays == null || leadTimeMinDays <= leadTimeDays;
        }

        /**
         * 메인 사진(대표 · 착용 · 디테일 · 누끼)은 종류마다 한 장, 상세 이미지는 {@value ProductImageKind#STORY_MAX} 장까지.
         * 대표가 두 장이면 목록 카드에 무엇을 그릴지 정할 수 없다.
         */
        @AssertTrue(message = "메인 사진은 종류마다 한 장, 상세 이미지는 " + ProductImageKind.STORY_MAX + "장까지 올릴 수 있습니다.")
        public boolean isImageCountValid() {
            if (images == null) {
                return true;
            }
            java.util.Map<ProductImageKind, Long> counts = images.stream()
                    .filter(i -> i != null && i.kind() != null)
                    .collect(java.util.stream.Collectors.groupingBy(Image::kind, java.util.stream.Collectors.counting()));
            return counts.entrySet().stream().allMatch(e ->
                    e.getValue() <= (e.getKey() == ProductImageKind.STORY ? ProductImageKind.STORY_MAX : 1));
        }

        /**
         * 차트를 올렸으면 설명이 있어야 한다.
         *
         * <p>엔티티({@code Product.setSizeChart})도 같은 조합을 막지만, 거기서 막으면
         * {@code IllegalArgumentException} 이 되어 <b>500</b> 으로 나간다.
         * 클라이언트가 잘못 보낸 요청에 서버 오류로 답하면 프론트는 자기 잘못인 줄 모른다.
         * 그래서 <b>경계에서</b> 잡아 400 으로 돌려주고, 엔티티 검사는 마지막 방어선으로 남긴다
         * (그쪽이 걸리면 그건 우리 코드의 버그이므로 500 이 맞다).
         */
        @AssertTrue(message = "사이즈 차트를 올렸으면 설명(대체 텍스트)이 필요합니다.")
        public boolean isSizeChartDescribed() {
            return sizeChartMediaId == null
                    || (sizeChartAlt != null && !sizeChartAlt.isBlank());
        }

        /** 세부 분류는 그 분류의 것이어야 한다 — 트라우저에 OXFORD 같은 조합을 400 으로 돌려준다. */
        @AssertTrue(message = "세부 분류가 제품 분류와 맞지 않습니다.")
        public boolean isStyleFitting() {
            return category == null || ProductStyle.fits(style, category);
        }
    }

    /** 관리자 전용 상품 정보 (V39) — 손님에게 안 보인다. 길이는 DB 칸과 같다. */
    public record Internal(
            @Size(max = NAME_MAX * 2) String adminName,
            @Size(max = NOTICE_MAX) String fabricSupplier,
            @Size(max = NOTICE_MAX) String fabricCollection,
            @Size(max = NOTICE_MAX) String fabricCode,
            @Size(max = NOTICE_MAX) String workshop,
            @jakarta.validation.constraints.PositiveOrZero Long costKrw,
            /** 소분류 — 자유 태그(V40, 엑셀 양식) */
            @Size(max = 40) String subCategory) {
    }

    public record Image(
            @NotNull UUID mediaId,
            @NotNull ProductImageKind kind,
            /*
             * 대체 텍스트는 필수다. 나중에 채우게 두면 영원히 비어 있고,
             * 상품 사진의 빈 alt 는 스크린리더 사용자에게 상품이 없는 것과 같다.
             * (빈 문자열은 허용한다 — "장식이라 읽지 말 것" 은 유효한 의도다)
             */
            @NotNull @Size(max = 120) String alt,
            Integer sortOrder) {
    }

    /** 컬러 하나(V46) — 이름은 손님 화면 · 주문서에 그대로 나간다. 구성 줄 구분 글자(/ ·)는 못 쓴다 */
    /**
     * 함께 구매 상품 하나. 둘 중 하나다:
     * <ul>
     *   <li>등록된 상품을 붙인다(V52) — productId 와 함께 구매 금액(비우면 원래 판매가)</li>
     *   <li>직접 입력한다(V53) — custom. productId 는 이미 저장된 항목을 고칠 때만(새 항목은 비운다)</li>
     * </ul>
     */
    public record Addon(UUID productId, @Positive @jakarta.validation.constraints.Max(100_000_000) Long priceKrw,
                        @Valid Custom custom) {

        @AssertTrue(message = "함께 구매 상품을 고르거나 내용을 입력해 주세요.")
        public boolean isTargetGiven() {
            return productId != null || custom != null;
        }
    }

    /** 직접 입력한 함께 구매 항목(V53) — 이름 · 설명 · 사진 · 금액 · 컬러 · 사이즈. 컬러 · 사이즈를 비우면 고르지 않는다 */
    public record Custom(
            @NotNull @jakarta.validation.constraints.NotBlank @Size(max = NAME_MAX) String name,
            @Size(max = 200) String description,
            UUID mediaId,
            @NotNull @Positive @jakarta.validation.constraints.Max(100_000_000) Long priceKrw,
            @Size(max = 12) List<@jakarta.validation.constraints.NotBlank @Size(max = 20)
                    @Pattern(regexp = "^[^/·]*$", message = "컬러 이름에는 / 와 · 를 쓸 수 없습니다.") String> colors,
            @Size(max = 20) List<@jakarta.validation.constraints.NotBlank @Size(max = 20) String> sizes) {

        @AssertTrue(message = "같은 컬러 · 사이즈를 두 번 넣었습니다.")
        public boolean isOptionUnique() {
            return unique(colors) && unique(sizes);
        }

        private static boolean unique(List<String> values) {
            if (values == null) {
                return true;
            }
            List<String> t = values.stream().filter(java.util.Objects::nonNull).map(String::trim).toList();
            return t.size() == new java.util.HashSet<>(t).size();
        }
    }

    public record Color(
            @NotBlank @Size(max = 20)
            @Pattern(regexp = "^[^/·]+$", message = "컬러 이름에는 / 와 · 를 쓸 수 없습니다.") String name,
            @NotBlank @Pattern(regexp = "^#[0-9A-Fa-f]{6}$", message = "색은 #RRGGBB 로 적어 주세요.") String hex) {
    }

    /** {@code stock} — 남은 수량(V30). 비우면 세지 않는다(주문 후 제작). 0 이면 품절. */
    public record Sku(
            @NotBlank @Size(max = 20) String size,
            Integer sortOrder,
            Boolean orderable,
            @jakarta.validation.constraints.PositiveOrZero @jakarta.validation.constraints.Max(99999) Integer stock,
            @Valid List<Measurement> measurements,
            /** 수트 조각(V43). 비우면 보통 사이즈 */
            Product.Piece piece,
            /** 이 사이즈를 누르면 보여 줄 메인 사진 칸(V45). 비우면 사진을 바꾸지 않는다 */
            ProductImageKind imageKind) {

        /** 구성 줄("3피스 · 자켓 L / 베스트 M / 팬츠 L")을 나누는 글자라 조각 사이즈에는 못 쓴다. */
        @AssertTrue(message = "수트 조각 사이즈에는 / 와 · 를 쓸 수 없습니다.")
        public boolean isPieceSizeValid() {
            return piece == null || size == null || !(size.contains("/") || size.contains("·"));
        }

        @AssertTrue(message = "사이즈 사진은 메인 사진 다섯 칸 중에서 고를 수 있습니다.")
        public boolean isImageKindValid() {
            return imageKind == null || (imageKind != ProductImageKind.CUTOUT && imageKind != ProductImageKind.STORY);
        }
    }

    public record Measurement(
            @NotNull MeasurementPart part,
            // 5,1 자리다. 999.9cm 를 넘는 실측은 단위를 잘못 넣은 것이다.
            @NotNull @Positive BigDecimal valueCm,
            BigDecimal toleranceCm) {
    }
}
