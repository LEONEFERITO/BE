package com.leoneferito.product;

import com.leoneferito.common.error.ResourceNotFoundException;
import com.leoneferito.media.MediaUrls;
import com.leoneferito.product.api.ProductResponse;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 공개 상품 조회.
 *
 * <p>엔티티를 DTO 로 바꾸는 일이 <b>트랜잭션 안에서</b> 끝나야 한다.
 * {@code open-in-view: false} 라서 컨트롤러로 엔티티를 그대로 넘기면 지연 로딩 컬렉션을
 * 읽는 순간 {@code LazyInitializationException} 이 난다. 그래서 변환까지 여기서 한다.
 *
 * <p>{@code readOnly = true} 를 붙이는 이유는 성능만이 아니다. Hibernate 가 더티 체킹을
 * 하지 않으므로 <b>조회 도중 실수로 엔티티를 고쳐도 DB 에 반영되지 않는다.</b>
 */
@Service
@Transactional(readOnly = true)
public class ProductQueryService {

    private final ProductRepository products;
    private final MediaUrls mediaUrls;

    public ProductQueryService(ProductRepository products, MediaUrls mediaUrls) {
        this.products = products;
        this.mediaUrls = mediaUrls;
    }

    /**
     * 목록. {@code category} 와 {@code line} 은 없으면 거르지 않는다.
     *
     * <p>라인 필터를 SQL 이 아니라 메모리에서 거르는 이유: 상품 수가 적고(수십 점),
     * 카테고리·라인 조합마다 쿼리를 만들면 메서드가 넷이 된다. 수백 점으로 늘면
     * Specification 이나 QueryDSL 로 옮긴다 — 그때가 오기 전에는 이게 더 읽기 쉽다.
     */
    public List<ProductResponse.Summary> list(ProductCategory category, ProductLine line) {
        List<Product> found = category == null
                ? products.findPublished()
                : products.findPublishedByCategory(category);

        return found.stream()
                .filter(p -> line == null || p.getLine() == line)
                .map(this::toSummary)
                .toList();
    }

    public ProductResponse.Detail detail(String slug) {
        Product product = products.findPublishedBySlug(slug)
                // 비공개 상품도 같은 예외다. 403 으로 나누면 slug 의 존재가 샌다.
                .orElseThrow(() -> new ResourceNotFoundException("공개 상품 없음: slug=" + slug));

        return toDetail(product);
    }

    private ProductResponse.Summary toSummary(Product p) {
        return new ProductResponse.Summary(
                p.getSlug(),
                p.getName(),
                p.getCategory().name(),
                p.getLine().name(),
                p.getPriceKrw(),
                p.getListPriceKrw(),
                p.mainImage().map(this::toImage).orElse(null));
    }

    private ProductResponse.Detail toDetail(Product p) {
        return new ProductResponse.Detail(
                p.getSlug(),
                p.getName(),
                p.getCategory().name(),
                p.getLine().name(),
                p.getPriceKrw(),
                p.getListPriceKrw(),
                p.getDescription(),
                p.getIntent(),
                p.getFeatures(),
                p.getFabric(),
                p.getCare(),
                new ProductResponse.Model(p.getModelHeightCm(), p.getModelWeightKg(), p.getModelSize()),
                p.getLeadTimeDays(),
                p.getImages().stream()
                        .sorted(Comparator.comparingInt(ProductImage::getSortOrder))
                        .map(this::toImage)
                        .toList(),
                p.getSkus().stream()
                        .sorted(Comparator.comparingInt(ProductSku::getSortOrder))
                        .map(this::toSku)
                        .toList());
    }

    private ProductResponse.Image toImage(ProductImage image) {
        return new ProductResponse.Image(
                mediaUrls.urlFor(image.getMedia()),
                image.getKind().name(),
                image.getAlt());
    }

    private ProductResponse.Sku toSku(ProductSku sku) {
        return new ProductResponse.Sku(
                sku.getSize(),
                sku.isOrderable(),
                sku.getMeasurements().stream()
                        // 부위 순서를 enum 선언 순서로 고정한다. 저장 순서에 맡기면
                        // 상품마다 표의 행 순서가 달라져서 비교가 안 된다.
                        .sorted(Comparator.comparing(ProductMeasurement::getPart))
                        .map(m -> new ProductResponse.Measurement(
                                m.getPart().name(), m.getValueCm(), m.getToleranceCm()))
                        .toList());
    }
}
