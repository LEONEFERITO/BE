package com.leoneferito.product;

import com.leoneferito.common.error.ResourceNotFoundException;
import com.leoneferito.media.MediaAsset;
import com.leoneferito.media.MediaAssetRepository;
import com.leoneferito.product.api.AdminProductRequests;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 관리자 상품 등록·수정.
 *
 * <p><b>공개 상태를 여기서 바꾸지 않는다.</b> 저장과 공개는 다른 결정이다 —
 * 저장은 "작업 중인 내용을 잃지 않으려고", 공개는 "이제 손님에게 보여도 된다" 다.
 * 저장할 때마다 공개되면 문구를 고치는 중에 반쪽짜리가 노출된다.
 * 공개는 {@link #publish} 를 명시적으로 불러야 한다.
 */
@Service
public class AdminProductService {

    private static final Logger log = LoggerFactory.getLogger(AdminProductService.class);

    private final ProductRepository products;
    private final MediaAssetRepository mediaAssets;

    public AdminProductService(ProductRepository products, MediaAssetRepository mediaAssets) {
        this.products = products;
        this.mediaAssets = mediaAssets;
    }

    @Transactional
    public UUID create(AdminProductRequests.Save request) {
        if (products.existsBySlug(request.slug())) {
            throw new DuplicateSlugException(request.slug());
        }

        Product product = new Product(
                UUID.randomUUID(), request.slug(), request.category(), request.line());
        apply(product, request);
        products.save(product);

        log.info("상품 등록 productId={} slug={}", product.getId(), product.getSlug());
        return product.getId();
    }

    @Transactional
    public void update(UUID id, AdminProductRequests.Save request) {
        Product product = products.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("상품 없음: id=" + id));

        /*
         * slug 는 바꾸지 못하게 한다.
         *
         * 공개된 뒤에 바꾸면 그 주소로 걸린 링크·검색 결과·공유된 카드가 전부 죽는다.
         * 정말 바꿔야 하면 리다이렉트를 함께 넣어야 하는데, 그건 별도 기능이다.
         * 조용히 무시하지 않고 거부한다 — 무시하면 관리자는 바뀐 줄 안다.
         */
        if (!product.getSlug().equals(request.slug())) {
            throw new SlugChangeNotAllowedException(product.getSlug());
        }

        apply(product, request);
        log.info("상품 수정 productId={}", id);
    }

    @Transactional
    public void publish(UUID id) {
        Product product = products.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("상품 없음: id=" + id));
        // 무엇이 비었는지는 엔티티가 판단한다. 규칙이 두 곳에 있으면 한쪽만 바뀐다.
        product.publish();
        log.info("상품 공개 productId={}", id);
    }

    @Transactional
    public void unpublish(UUID id) {
        Product product = products.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("상품 없음: id=" + id));
        product.unpublish();
        log.info("상품 비공개 전환 productId={}", id);
    }

    private void apply(Product product, AdminProductRequests.Save request) {
        product.setName(request.name());
        product.setSummary(request.summary());
        product.setPriceKrw(request.priceKrw());
        product.setListPriceKrw(request.listPriceKrw());
        product.setDescription(request.description());
        product.setIntent(request.intent());
        product.setFeatures(request.features());
        product.setFabric(request.fabric());
        product.setCare(request.care());
        product.setModel(request.modelHeightCm(), request.modelWeightKg(), request.modelSize());
        product.setLeadTimeDays(request.leadTimeDays());
        product.setDisplayOrder(request.displayOrder() == null ? 0 : request.displayOrder());

        /*
         * 순서가 중요하다. 비우고 **flush 해서 DELETE 를 먼저 내보낸 뒤** 채운다.
         *
         * 한 번에 하면 Hibernate 가 INSERT 를 먼저 보내고, 같은 사이즈를 그대로 다시
         * 저장하는 흔한 경우에 (product_id, size) 유니크 제약이 터진다.
         * 실제로 이 순서를 안 지켜서 '이미지 붙이고 공개' 가 500 으로 죽었다.
         */
        product.clearChildren();
        products.flush();

        product.replaceImages(toImages(request.images()));
        product.replaceSkus(toSkus(request.skus()));
    }

    private List<ProductImage> toImages(List<AdminProductRequests.Image> requested) {
        if (requested == null) return List.of();

        List<ProductImage> result = new ArrayList<>();
        for (int i = 0; i < requested.size(); i++) {
            var r = requested.get(i);
            /*
             * 존재하는 이미지인지 확인한다. 없는 id 를 그대로 저장하면 FK 위반으로
             * 500 이 나가고, 관리자는 "저장이 안 된다" 는 것만 알 뿐 이유를 모른다.
             */
            MediaAsset media = mediaAssets.findById(r.mediaId())
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "이미지 없음: mediaId=" + r.mediaId()));

            // 순서를 안 주면 보낸 순서를 그대로 쓴다. 관리자가 끌어 놓은 순서가 곧 순서다.
            int sortOrder = r.sortOrder() == null ? i : r.sortOrder();
            result.add(new ProductImage(UUID.randomUUID(), media, r.kind(), r.alt(), sortOrder));
        }
        return result;
    }

    private List<ProductSku> toSkus(List<AdminProductRequests.Sku> requested) {
        if (requested == null) return List.of();

        List<ProductSku> result = new ArrayList<>();
        for (int i = 0; i < requested.size(); i++) {
            var r = requested.get(i);
            /*
             * 사이즈 정렬은 보낸 순서를 따른다. 문자열 정렬이면 100 이 95 앞에 오고,
             * 숫자 변환은 S/M/L 이 들어오는 순간 깨진다. 사람이 정한 순서가 가장 정확하다.
             */
            ProductSku sku = new ProductSku(
                    UUID.randomUUID(), r.size(), r.sortOrder() == null ? i : r.sortOrder());
            if (r.orderable() != null) sku.setOrderable(r.orderable());

            if (r.measurements() != null) {
                for (var m : r.measurements()) {
                    sku.addMeasurement(new ProductMeasurement(
                            UUID.randomUUID(), m.part(), m.valueCm(), m.toleranceCm()));
                }
            }
            result.add(sku);
        }
        return result;
    }

    /** slug 는 URL 이라 중복될 수 없다. */
    public static class DuplicateSlugException extends RuntimeException {
        public DuplicateSlugException(String slug) {
            super("이미 있는 slug: " + slug);
        }
    }

    /** 공개된 주소를 바꾸면 그 주소로 걸린 링크가 전부 죽는다. */
    public static class SlugChangeNotAllowedException extends RuntimeException {
        public SlugChangeNotAllowedException(String slug) {
            super("slug 변경 불가: " + slug);
        }
    }
}
