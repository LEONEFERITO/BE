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
import org.springframework.context.ApplicationEventPublisher;
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
    private final CategoryOrderRepository categoryOrders;
    private final ApplicationEventPublisher events;

    public AdminProductService(ProductRepository products, MediaAssetRepository mediaAssets,
                               CategoryOrderRepository categoryOrders, ApplicationEventPublisher events) {
        this.products = products;
        this.mediaAssets = mediaAssets;
        this.categoryOrders = categoryOrders;
        this.events = events;
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
        // 분류는 수정에서 바뀌지 않는다(등록 때 정해진 값). 세부 분류는 **저장된** 분류와 맞아야 한다 —
        // 요청 검증은 요청에 실린 분류로 봤으므로, 화면이 다른 분류를 실어 보내면 여기서 걸린다.
        if (!ProductStyle.fits(request.style(), product.getCategory())) {
            throw new StyleMismatchException();
        }

        apply(product, request);
        log.info("상품 수정 productId={}", id);

        // 손님이 보고 있는 상품을 고쳤으면 손님 화면을 다시 만든다. 초안 수정은 화면에 없다.
        if (product.getStatus() == ProductStatus.PUBLISHED) {
            events.publishEvent(new FrontRebuildTrigger.CatalogChanged("공개 상품 수정"));
        }
    }

    @Transactional
    public void publish(UUID id) {
        Product product = products.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("상품 없음: id=" + id));
        // 무엇이 비었는지는 엔티티가 판단한다. 규칙이 두 곳에 있으면 한쪽만 바뀐다.
        product.publish();
        log.info("상품 공개 productId={}", id);
        events.publishEvent(new FrontRebuildTrigger.CatalogChanged("상품 공개"));
    }

    @Transactional
    public void unpublish(UUID id) {
        Product product = products.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("상품 없음: id=" + id));
        product.unpublish();
        events.publishEvent(new FrontRebuildTrigger.CatalogChanged("상품 비공개"));
        log.info("상품 비공개 전환 productId={}", id);
    }

    /**
     * 복제 (2026-10-16 "상품 한번에 등록"). 같은 셔츠의 색상 · 라인 변형처럼 비슷한 상품을 빨리 만든다.
     *
     * <p>이름 · 분류 · 세부 분류 · 가격 · 설명 · 고시 · 모델 · 제작 기간 · 사진(같은 파일을 가리킨다) · 사이즈 · 실측을 전부 복사해
     * <b>초안</b>으로 만든다. slug 는 {@code 원본-copy}(겹치면 -copy-2 …), 이름 뒤에 " (복제)" — 관리자가 바로 고치게.
     * 재고는 복사하지 않는다(새 상품은 새로 센다). 진열 순서도 0 — 공개할 때 정한다.
     */
    @Transactional
    public UUID duplicate(UUID id) {
        Product src = products.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("상품 없음: id=" + id));
        String base = src.getSlug() + "-copy";
        String slug = base;
        for (int n = 2; products.existsBySlug(slug); n++) {
            slug = base + "-" + n;
        }
        Product copy = new Product(UUID.randomUUID(), slug, src.getCategory(), src.getLine());
        copy.setName((src.getName() == null ? "" : src.getName()) + " (복제)");
        copy.setStyle(src.getStyle());
        copy.setSummary(src.getSummary());
        copy.setPriceKrw(src.getPriceKrw());
        copy.setListPriceKrw(src.getListPriceKrw());
        copy.setDescription(src.getDescription());
        copy.setIntent(src.getIntent());
        copy.setFeatures(src.getFeatures());
        copy.setFabric(src.getFabric());
        copy.setCare(src.getCare());
        copy.setNotice(src.getColor(), src.getManufacturer(), src.getCountryOfOrigin(), src.getManufacturedOn());
        copy.setModel(src.getModelHeightCm(), src.getModelWeightKg(), src.getModelSize());
        copy.setLeadTimeDays(src.getLeadTimeDays());
        copy.setLeadTimeMinDays(src.getLeadTimeMinDays());
        copy.setSubCategory(src.getSubCategory());
        copy.setInstagramUrl(src.getInstagramUrl());
        copy.setInternal(src.getAdminName(), src.getFabricSupplier(), src.getFabricCollection(), src.getFabricCode(),
                src.getWorkshop(), src.getCostKrw());
        copy.setSizeChart(src.getSizeChart(), src.getSizeChartAlt());
        copy.setDisplayOrder(0);
        for (ProductImage i : src.getImages()) {
            copy.addImage(new ProductImage(UUID.randomUUID(), i.getMedia(), i.getKind(), i.getAlt(), i.getSortOrder()));
        }
        for (ProductSku s : src.getSkus()) {
            ProductSku sku = new ProductSku(UUID.randomUUID(), s.getSize(), s.getSortOrder());
            sku.setOrderable(s.isOrderable());
            for (ProductMeasurement m : s.getMeasurements()) {
                sku.addMeasurement(new ProductMeasurement(UUID.randomUUID(), m.getPart(), m.getValueCm(), m.getToleranceCm()));
            }
            copy.addSku(sku);
        }
        products.save(copy);
        log.info("상품 복제 from={} to={} slug={}", id, copy.getId(), slug);
        return copy.getId();
    }

    /**
     * 삭제 (2026-10-15 "관리자 상품 관리에서 삭제").
     *
     * <p><b>주문 이력이 있으면 지우지 않는다</b> — 결제 전 주문까지 포함. 주문 행은 5년 보관이고 주문 항목이 상품을 가리킨다.
     * 그런 상품은 비공개로 내리면 손님 화면에서 사라진다. 이력이 없으면(잘못 만든 초안, 시험 등록) 지운다:
     * 장바구니에 담긴 줄 → 상품(사진 연결 · 사이즈 · 실측 · 이 상품의 메인 배너는 DB CASCADE) 순서.
     * 올려 둔 사진 파일(media_asset)은 남긴다 — 다른 상품 · 배너가 같은 사진을 쓸 수 있다.
     */
    @Transactional
    public void delete(UUID id) {
        Product product = products.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("상품 없음: id=" + id));
        if (products.hasOrderItems(id)) {
            throw new ProductHasOrdersException();
        }
        boolean wasPublished = product.getStatus() == ProductStatus.PUBLISHED;
        int carts = products.deleteCartItems(id);
        products.delete(product);
        products.flush();
        log.info("상품 삭제 productId={} slug={} cartItems={}", id, product.getSlug(), carts);
        if (wasPublished) {
            events.publishEvent(new FrontRebuildTrigger.CatalogChanged("공개 상품 삭제"));
        }
    }

    /**
     * 진열 순서 (메인 구성). 공개 상품을 이 순서로 손님 화면에 놓는다 — 메인 첫 화면은 앞에서부터
     * 사진이 있는 4개, 제품 목록도 이 순서다. 화면이 보낸 목록은 지금 공개 상품 전부와 같아야 한다.
     */
    @Transactional
    public void reorderPublished(java.util.List<UUID> ids) {
        java.util.List<Product> published = products.findPublished();
        if (ids.size() != published.size() || !new java.util.HashSet<>(ids).equals(
                published.stream().map(Product::getId).collect(java.util.stream.Collectors.toSet()))) {
            throw new com.leoneferito.common.error.StaleListException();
        }
        java.util.Map<UUID, Product> byId = published.stream()
                .collect(java.util.stream.Collectors.toMap(Product::getId, p -> p));
        for (int i = 0; i < ids.size(); i++) {
            byId.get(ids.get(i)).setDisplayOrder((i + 1) * 10);
        }
        log.info("진열 순서 변경 count={}", ids.size());
        events.publishEvent(new FrontRebuildTrigger.CatalogChanged("진열 순서"));
    }

    /**
     * 분류 메뉴 순서 (V27). 화면이 보낸 목록은 분류 전부를 한 번씩 담아야 한다 — 빠지거나 겹치면 409(다시 불러오기).
     * 저장하면 손님 화면을 다시 만든다(헤더 메뉴가 빌드 때 순서를 받는다).
     */
    @Transactional
    public void reorderCategories(List<ProductCategory> order) {
        java.util.Set<ProductCategory> all = java.util.EnumSet.allOf(ProductCategory.class);
        if (order.size() != all.size() || !new java.util.HashSet<>(order).equals(all)) {
            throw new com.leoneferito.common.error.StaleListException();
        }
        java.util.Map<ProductCategory, CategoryOrder> rows = categoryOrders.findAll().stream()
                .collect(java.util.stream.Collectors.toMap(CategoryOrder::getCategory, r -> r));
        for (int i = 0; i < order.size(); i++) {
            CategoryOrder row = rows.get(order.get(i));
            if (row == null) {
                // 마이그레이션이 행을 심지 않은 분류 — 순서를 저장할 곳이 없다. 마이그레이션부터.
                throw new IllegalStateException("분류 순서 행 없음: " + order.get(i) + " (V27 이후 마이그레이션 확인)");
            }
            row.moveTo((i + 1) * 10);
        }
        log.info("분류 순서 변경 {}", order);
        events.publishEvent(new FrontRebuildTrigger.CatalogChanged("분류 순서"));
    }

    private void apply(Product product, AdminProductRequests.Save request) {
        product.setName(request.name());
        product.setStyle(request.style());
        product.setSummary(request.summary());
        product.setPriceKrw(request.priceKrw());
        product.setListPriceKrw(request.listPriceKrw());
        product.setDescription(request.description());
        product.setIntent(request.intent());
        product.setFeatures(request.features());
        product.setFabric(request.fabric());
        product.setCare(request.care());
        product.setNotice(request.color(), request.manufacturer(),
                request.countryOfOrigin(), request.manufacturedOn());
        product.setModel(request.modelHeightCm(), request.modelWeightKg(), request.modelSize());
        product.setLeadTimeDays(request.leadTimeDays());
        product.setLeadTimeMinDays(request.leadTimeMinDays());
        product.setInstagramUrl(request.instagramUrl());
        AdminProductRequests.Internal in = request.internal();
        if (in != null) {
            product.setInternal(blank(in.adminName()), blank(in.fabricSupplier()), blank(in.fabricCollection()),
                    blank(in.fabricCode()), blank(in.workshop()), in.costKrw());
            product.setSubCategory(blank(in.subCategory()));
        }
        product.setDisplayOrder(request.displayOrder() == null ? 0 : request.displayOrder());

        /*
         * 차트는 이미지와 설명이 함께 움직인다. 엔티티가 그 짝을 강제하고,
         * DB CHECK 가 한 번 더 막는다(V7) — 관리자 SQL 로 들어오는 경로까지 덮기 위해서다.
         */
        MediaAsset chart = request.sizeChartMediaId() == null
                ? null
                : mediaAssets.findById(request.sizeChartMediaId())
                        .orElseThrow(() -> new ResourceNotFoundException(
                                "사이즈 차트 이미지 없음: mediaId=" + request.sizeChartMediaId()));
        product.setSizeChart(chart, request.sizeChartAlt());

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
            // 재고는 보낸 값 그대로 — 비우면(null) 세지 않는다. 저장은 관리자 화면이 연 순간의 수량으로 덮는다:
            // 그 사이 팔린 수량이 있으면 다시 열어 확인하면 된다(주문이 드문 동안은 이게 가장 단순하다).
            sku.setStock(r.stock());

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

    /** 주문 이력이 있는 상품은 지울 수 없다. 409. */
    public static class ProductHasOrdersException extends RuntimeException {
        public ProductHasOrdersException() {
            super("주문 이력이 있는 상품은 삭제할 수 없습니다. 비공개로 내려 두세요.");
        }
    }

    /** 세부 분류가 저장된 분류와 맞지 않는다 (수정 요청). 400. */
    public static class StyleMismatchException extends RuntimeException {
        public StyleMismatchException() {
            super("세부 분류가 제품 분류와 맞지 않습니다.");
        }
    }

    /** 빈 칸 · 공백만 있는 칸은 null 로 — "없음" 을 한 가지로 저장한다. */
    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
