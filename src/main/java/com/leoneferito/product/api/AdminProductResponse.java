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
            List<Sku> skus) {
    }
    public record Image(UUID mediaId, String url, String kind, String alt, int sortOrder) {
    }
    public record Sku(String size, int sortOrder, boolean orderable, List<Measurement> measurements) {
    }
    public record Measurement(String part, BigDecimal valueCm, BigDecimal toleranceCm) {
    }
}