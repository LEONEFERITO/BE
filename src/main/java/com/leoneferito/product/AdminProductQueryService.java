package com.leoneferito.product;

import com.leoneferito.common.error.ResourceNotFoundException;
import com.leoneferito.media.MediaAsset;
import com.leoneferito.media.MediaUrls;
import com.leoneferito.product.api.AdminProductResponse;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class AdminProductQueryService {

    private final ProductRepository products;
    private final MediaUrls mediaUrls;

    public AdminProductQueryService(ProductRepository products, MediaUrls mediaUrls) {
        this.products = products;
        this.mediaUrls = mediaUrls;
    }
    public List<AdminProductResponse.Row> list() {
        return products.findAllForAdmin().stream()
                .map(this::toRow)
                .toList();
    }

    public AdminProductResponse.Edit edit(UUID id) {
        Product product = products.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("상품 없음: id=" + id));
        return toEdit(product);
    }

    private AdminProductResponse.Row toRow(Product p) {
        return new AdminProductResponse.Row(
                p.getId(),
                p.getSlug(),
                p.getName(),
                p.getCategory().name(),
                p.getLine().name(),
                p.getStatus().name(),
                p.getPriceKrw(),
                p.mainImage().map(i -> mediaUrls.urlFor(i.getMedia())).orElse(null),
                p.missingForPublish(),
                p.getUpdatedAt(),
                p.getDisplayOrder());
    }

    private AdminProductResponse.Edit toEdit(Product p) {
        MediaAsset chart = p.getSizeChart();
        return new AdminProductResponse.Edit(
                p.getId(),
                p.getStatus().name(),
                p.missingForPublish(),
                p.getSlug(),
                p.getName(),
                p.getCategory().name(),
                p.getLine().name(),
                p.getPriceKrw(),
                p.getListPriceKrw(),
                p.getSummary(),
                p.getDescription(),
                p.getIntent(),
                p.getFeatures(),
                p.getFabric(),
                p.getCare(),
                p.getColor(),
                p.getManufacturer(),
                p.getCountryOfOrigin(),
                p.getManufacturedOn(),
                p.getModelHeightCm(),
                p.getModelWeightKg(),
                p.getModelSize(),
                p.getLeadTimeDays(),
                p.getDisplayOrder(),
                chart == null ? null : chart.getId(),
                chart == null ? null : mediaUrls.urlFor(chart),
                p.getSizeChartAlt(),
                p.getInstagramUrl(),
                p.getImages().stream()
                        .sorted(Comparator.comparingInt(ProductImage::getSortOrder))
                        .map(i -> new AdminProductResponse.Image(
                                i.getMedia().getId(),
                                mediaUrls.urlFor(i.getMedia()),
                                i.getKind().name(),
                                i.getAlt(),
                                i.getSortOrder()))
                        .toList(),
                p.getSkus().stream()
                        .sorted(Comparator.comparingInt(ProductSku::getSortOrder))
                        .map(this::toSku)
                        .toList());
    }

    private AdminProductResponse.Sku toSku(ProductSku sku) {
        return new AdminProductResponse.Sku(
                sku.getSize(),
                sku.getSortOrder(),
                sku.isOrderable(),
                sku.getMeasurements().stream()
                        // 공개 응답과 같은 순서(enum 선언 순서). 화면이 둘을 비교할 수 있게.
                        .sorted(Comparator.comparing(ProductMeasurement::getPart))
                        .map(m -> new AdminProductResponse.Measurement(
                                m.getPart().name(), m.getValueCm(), m.getToleranceCm()))
                        .toList());
    }
}