package com.leoneferito.product.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.util.List;

/**
 * 공개 API 의 상품 응답.
 *
 * <p><b>엔티티를 그대로 내보내지 않는다.</b> 이유가 셋이다:
 * <ol>
 *   <li>{@code status}·내부 id·{@code createdAt} 같은 운영 정보가 그대로 샌다.</li>
 *   <li>엔티티 필드 이름을 바꾸는 순간 API 가 말없이 깨진다. DTO 가 있으면 컴파일이 막아준다.</li>
 *   <li>지연 로딩 프록시가 직렬화 중에 열려 트랜잭션 밖에서 터진다.</li>
 * </ol>
 *
 * <p>상품 식별자로 UUID 가 아니라 {@code slug} 를 쓴다. 손님에게 보이는 주소가 slug 이고,
 * 내부 id 를 노출하면 "몇 번째 상품인지" 같은 정보가 따라 나간다.
 *
 * <p>{@link JsonInclude.Include#NON_NULL} 을 걸지 <b>않았다.</b> 아직 안 정해진 값
 * ({@code name}, {@code priceKrw})이 JSON 에서 아예 빠져버리면, 프론트는 "필드가 없다" 와
 * "값이 없다" 를 구분하지 못한다. {@code null} 로 명시해서 내보낸다.
 */
public final class ProductResponse {

    private ProductResponse() {
    }

    /** 목록용. 카드 한 장을 그리는 데 필요한 만큼만 담는다. */
    public record Summary(
            String slug,
            String name,
            /** 카드 아래 한 줄. 없으면 null. */
            String summary,
            String category,
            String line,
            /** 세부 분류(REGULAR · STRAIGHT · FLARE · OXFORD · LOAFER). 없으면 null */
            String style,
            Long priceKrw,
            Long listPriceKrw,
            Image mainImage) {
    }

    /** 상세용. */
    public record Detail(
            String slug,
            String name,
            String summary,
            String category,
            String line,
            /** 세부 분류(REGULAR · STRAIGHT · FLARE · OXFORD · LOAFER). 없으면 null */
            String style,
            Long priceKrw,
            Long listPriceKrw,
            String description,
            String intent,
            String features,
            String fabric,
            String care,
            Model model,
            /*
             * 제작 기간. 주문 후 제작이라 재고 대신 이 값이 나간다.
             * 결제 전에 반드시 보여야 하는 값이고, 상품이 공개되려면 값이 있어야 한다
             * (Product.isPublishable). 그래서 상세 응답에서는 사실상 항상 채워져 있다.
             */
            Short leadTimeDays,
            /** 상세 사이즈 차트. 없으면 null — 화면이 그 자리를 비운다. */
            SizeChart sizeChart,
            /** 인스타그램 게시물 주소. 없으면 null — 화면이 버튼을 숨긴다. */
            String instagramUrl,
            /** 상품정보제공고시 중 상품마다 다른 항목. 소재·세탁은 위 fabric·care. */
            Notice notice,
            List<Image> images,
            List<Sku> skus) {
    }

    /**
     * 상세 사이즈 차트.
     *
     * <p>{@code width}/{@code height} 를 함께 주는 이유: 화면이 자리를 미리 잡아야
     * 이미지가 도착하면서 아래 구매 버튼이 밀리지 않는다(레이아웃 이동).
     */
    public record SizeChart(String url, String alt, Integer width, Integer height) {
    }

    /**
     * width · height: 원본 크기(모르면 null — WebP · AVIF 등). 화면이 자리를 미리 잡아
     * 긴 상세 이미지가 늦게 떠도 아래 내용이 밀려 내려가지 않게 한다.
     */
    public record Image(String url, String kind, String alt, Integer width, Integer height) {
    }

    /** 상품정보제공고시 (V11). 공개된 상품은 넷 다 채워져 있다 (Product.hasNotice). */
    public record Notice(String color, String manufacturer, String countryOfOrigin, String manufacturedOn) {
    }

    /** 피팅 모델 스펙. 실측만큼 중요한 판단 재료다. */
    public record Model(Short heightCm, Short weightKg, String size) {
    }

    /**
     * 사이즈 한 줄.
     *
     * <p>{@code orderable} 은 재고가 아니라 <b>제작 가능 여부</b>다. 프론트에서 "품절" 로
     * 표기하면 안 된다 — 만들 수 없는 사이즈라는 뜻이다.
     */
    public record Sku(String size, boolean orderable, List<Measurement> measurements) {
    }

    public record Measurement(String part, BigDecimal valueCm, BigDecimal toleranceCm) {
    }
}
