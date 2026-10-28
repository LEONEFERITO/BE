package com.leoneferito.product.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class AdminProductResponse {
    private AdminProductResponse() {
    }

    public record Row(
            UUID id,
            String slug,
            String name,
            String category,
            String line,
            String style,
            String status,
            Long priceKrw,
            String mainImageUrl,
            List<String> missingForPublish,
            Instant updatedAt,
            /* 진열 순서 (메인 구성) — 작을수록 앞 */ int displayOrder) {
    }
    public record Edit(
            UUID id,
            String status,
            List<String> missingForPublish,
            String slug,
            String name,
            String category,
            String line,
            String style,
            Long priceKrw,
            Long listPriceKrw,
            String summary,
            String description,
            String intent,
            String features,
            String fabric,
            String care,
            String color,
            String manufacturer,
            String countryOfOrigin,
            String manufacturedOn,
            Short modelHeightCm,
            Short modelWeightKg,
            String modelSize,
            Short leadTimeDays,
            int displayOrder,
            UUID sizeChartMediaId,
            String sizeChartUrl,
            String sizeChartAlt,
            String instagramUrl,
            List<Image> images,
            List<Sku> skus,
            /** 관리자 전용 정보 (V39) */
            Internal internal,
            /** 제작 기간 최소 (V40). leadTimeDays 가 최대 */
            Short leadTimeMinDays,
            /** 수트 2피스 판매가 (V43) */
            Long twoPiecePriceKrw) {
    }

    public record Internal(String adminName, String fabricSupplier, String fabricCollection, String fabricCode,
                           String workshop, Long costKrw, String subCategory) {
    }
    public record Image(UUID mediaId, String url, String kind, String alt, int sortOrder) {
    }
    /** {@code stock} — 남은 수량(V30). null 이면 세지 않음(주문 후 제작). */
    public record Sku(String size, int sortOrder, boolean orderable, Integer stock, List<Measurement> measurements,
                      /** 수트 조각 (V43). null 이면 보통 사이즈 */ String piece) {
    }
    public record Measurement(String part, BigDecimal valueCm, BigDecimal toleranceCm) {
    }
}