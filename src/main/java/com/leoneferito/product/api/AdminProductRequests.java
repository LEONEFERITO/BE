package com.leoneferito.product.api;

import com.leoneferito.product.MeasurementPart;
import com.leoneferito.product.ProductCategory;
import com.leoneferito.product.ProductImageKind;
import com.leoneferito.product.ProductLine;
import jakarta.validation.Valid;
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
            @NotNull ProductLine line,

            // 원화. 소수 단위가 없으므로 정수다.
            @Positive Long priceKrw,
            @Positive Long listPriceKrw,

            @Size(max = SUMMARY_MAX) String summary,
            @Size(max = BODY_MAX) String description,
            @Size(max = BODY_MAX) String intent,
            @Size(max = BODY_MAX) String features,
            @Size(max = SHORT_BODY_MAX) String fabric,
            @Size(max = SHORT_BODY_MAX) String care,

            @Min(100) @Max(250) Short modelHeightCm,
            @Min(30) @Max(200) Short modelWeightKg,
            @Size(max = 20) String modelSize,

            /*
             * 주문 후 제작 기간. 상한을 365일로 둔다 — 그보다 길면 입력 실수일 가능성이
             * 훨씬 높고, 실수를 그대로 저장하면 결제 화면에 그대로 표시된다.
             */
            @Min(1) @Max(365) Short leadTimeDays,

            Integer displayOrder,

            @Valid List<Image> images,
            @Valid List<Sku> skus) {
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

    public record Sku(
            @NotBlank @Size(max = 20) String size,
            Integer sortOrder,
            Boolean orderable,
            @Valid List<Measurement> measurements) {
    }

    public record Measurement(
            @NotNull MeasurementPart part,
            // 5,1 자리다. 999.9cm 를 넘는 실측은 단위를 잘못 넣은 것이다.
            @NotNull @Positive BigDecimal valueCm,
            BigDecimal toleranceCm) {
    }
}
