package com.leoneferito.product;

import com.leoneferito.media.MediaAsset;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 판매 상품.
 *
 * <p><b>재고 필드가 없다.</b> 이 브랜드는 만들어 둔 물건을 파는 게 아니라 주문을 받고 만든다.
 * "재고 3개" 라는 숫자가 존재하지 않으므로, 그 자리를 {@link #leadTimeDays}(제작 기간)가 대신한다.
 * 재고 컬럼을 두면 0 이 되는 순간 화면이 품절로 바뀌는데 사실은 주문할 수 있다.
 *
 * <p>비어 있는 필드가 많은 이유: 촬영·가격·문구가 아직 오지 않았다. 넘겨짚어 채우지 않고
 * {@code null} 로 둔다. 빈 문자열로 채우면 "안 정함" 과 "비워 두기로 정함" 이 구분되지 않는다.
 * 그런 상품은 {@link ProductStatus#DRAFT} 라서 공개 조회에 나가지 않는다.
 */
@Entity
@Table(name = "product")
public class Product {

    @Id
    private UUID id;

    /** URL 에 그대로 나간다. 공개 후에는 바꾸지 않는다 — 링크가 죽는다. */
    @Column(nullable = false, unique = true)
    private String slug;

    /** 미확정이면 {@code null}. */
    @Column
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ProductCategory category;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ProductLine line;

    /** 세부 분류(트라우저 핏 · 신발 종류). 없는 분류는 {@code null}. V21. */
    @Enumerated(EnumType.STRING)
    @Column
    private ProductStyle style;

    /** 원화. 소수 단위가 없으므로 정수로 둔다. 미확정이면 {@code null}. */
    @Column(name = "price_krw")
    private Long priceKrw;

    /** 정가. 할인 표시(취소선)에 쓴다. */
    @Column(name = "list_price_krw")
    private Long listPriceKrw;

    /** 목록 카드용 한 줄. 상세 본문(description)과 쓰임이 다르다 — V6 주석 참고. */
    @Column
    private String summary;

    @Column
    private String description;

    /** 제작 의도. */
    @Column
    private String intent;

    /** 제품 특징·장점. */
    @Column
    private String features;

    /** 원단 정보 및 혼용률. */
    @Column
    private String fabric;

    /** 세탁·관리 정보. */
    @Column
    private String care;

    /*
     * 상품정보제공고시 중 상품마다 다른 항목 (V11). 소재는 fabric, 세탁은 care 가 맡는다.
     * 넷 다 있어야 공개할 수 있다 — 고시는 판매 전 의무다.
     */
    @Column
    private String color;

    @Column
    private String manufacturer;

    @Column(name = "country_of_origin")
    private String countryOfOrigin;

    @Column(name = "manufactured_on")
    private String manufacturedOn;

    @Column(name = "model_height_cm")
    private Short modelHeightCm;

    @Column(name = "model_weight_kg")
    private Short modelWeightKg;

    @Column(name = "model_size")
    private String modelSize;

    /**
     * 주문 후 제작 기간(일).
     *
     * <p>전자상거래법상 결제 전에 반드시 알려야 하는 값이다. 값이 없으면 공개하면 안 된다.
     * {@link #isPublishable()} 이 그걸 판단한다.
     */
    @Column(name = "lead_time_days")
    private Short leadTimeDays;

    /**
     * 상세 사이즈 차트 이미지.
     *
     * <p>상품 사진({@link #images})과 따로 둔다. 갤러리에 섞으면 손님이 사진을 넘기다
     * 표를 만나 상품 컷으로 오해한다. 놓이는 자리가 달라서 보관도 나눈다.
     *
     * <p>{@code LAZY} 인 이유: 목록 조회에서는 차트가 필요 없는데 EAGER 면
     * 상품마다 조인이 하나씩 더 붙는다.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "size_chart_media_id")
    private MediaAsset sizeChart;

    /**
     * 차트 대체 텍스트.
     *
     * <p>표를 이미지로 만든 이상 이 문장이 <b>스크린리더에게는 유일한 정보원</b>이다.
     * 이미지가 있는데 이게 비면 그 사용자에게 사이즈 구간이 통째로 없는 것과 같아서,
     * DB CHECK 가 그 조합을 막는다(V7).
     */
    @Column(name = "size_chart_alt")
    private String sizeChartAlt;

    /**
     * 이 상품의 인스타그램 게시물 주소. 없는 상품이 있다 — 그러면 {@code null} 이고
     * 상세 화면이 버튼을 숨긴다.
     *
     * <p>주소는 instagram.com 으로만 받는다(요청 검증 + V8 CHECK). 손님이 브랜드 페이지에서
     * 누르는 링크라, 오타나 계정 탈취로 엉뚱한 곳(피싱·{@code javascript:})을 가리키면
     * 그 책임이 브랜드로 돌아온다.
     */
    @Column(name = "instagram_url")
    private String instagramUrl;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ProductStatus status = ProductStatus.DRAFT;

    @Column(name = "display_order", nullable = false)
    private int displayOrder;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private Instant updatedAt;

    /*
     * 컬렉션은 전부 LAZY 다. open-in-view 가 꺼져 있으므로 트랜잭션 밖에서는 못 읽는다.
     * 목록 조회처럼 여러 상품을 한 번에 그릴 때는 fetch join 으로 가져온다 —
     * EAGER 로 두면 상품 20개를 읽을 때 사진·사이즈 쿼리가 40번 더 나간다(N+1).
     */
    @OneToMany(mappedBy = "product", cascade = CascadeType.ALL, orphanRemoval = true,
            fetch = FetchType.LAZY)
    @OrderBy("sortOrder ASC")
    private List<ProductImage> images = new ArrayList<>();

    @OneToMany(mappedBy = "product", cascade = CascadeType.ALL, orphanRemoval = true,
            fetch = FetchType.LAZY)
    @OrderBy("sortOrder ASC")
    private List<ProductSku> skus = new ArrayList<>();

    protected Product() {
        // JPA
    }

    public Product(UUID id, String slug, ProductCategory category, ProductLine line) {
        this.id = Objects.requireNonNull(id, "id");
        this.slug = Objects.requireNonNull(slug, "slug");
        this.category = Objects.requireNonNull(category, "category");
        this.line = Objects.requireNonNull(line, "line");
    }

    /**
     * 공개하려면 무엇이 더 필요한가. 비어 있으면 공개할 수 있다.
     *
     * <p>이름·가격·제작 기간·대표 이미지가 모두 있어야 한다. 제작 기간이 조건에 든 건
     * 디자인이 아니라 법적 요건이다 — 주문 제작품은 결제 전에 소요 기간과
     * 청약철회 제한을 고지해야 한다.
     *
     * <p>관리자 목록이 "왜 공개가 안 되는지" 를 보여주는 데 쓴다. {@link #isPublishable()} 도
     * 이걸 쓴다 — 기준이 한 곳에만 있어야 목록의 표시와 실제 공개 거부가 어긋나지 않는다.
     */
    public List<String> missingForPublish() {
        List<String> missing = new ArrayList<>();
        if (name == null) missing.add("name");
        if (priceKrw == null) missing.add("priceKrw");
        if (leadTimeDays == null) missing.add("leadTimeDays");
        if (mainImage().isEmpty()) missing.add("mainImage");
        if (!hasNotice()) missing.add("notice");
        return missing;
    }

    /**
     * 상품정보제공고시가 다 찼는가. 소재(fabric) · 세탁(care) · 색상 · 제조자 · 제조국 · 제조연월.
     * 치수는 SKU 가, 품질보증기준과 A/S 연락처는 브랜드 공통 값이 채운다.
     */
    public boolean hasNotice() {
        return filled(fabric) && filled(care) && filled(color) && filled(manufacturer)
                && filled(countryOfOrigin) && filled(manufacturedOn);
    }

    private static boolean filled(String value) {
        return value != null && !value.isBlank();
    }

    /** 판단만 한다. 상태를 바꾸지 않는다 — 공개는 사람이 하는 결정이다. */
    public boolean isPublishable() {
        return missingForPublish().isEmpty();
    }

    /** 대표 이미지. DB 부분 유니크 인덱스가 상품당 한 장만 허용한다. */
    public Optional<ProductImage> mainImage() {
        return images.stream()
                .filter(i -> i.getKind() == ProductImageKind.MAIN)
                .findFirst();
    }

    public void addImage(ProductImage image) {
        images.add(image);
        image.assignTo(this);
    }

    public void addSku(ProductSku sku) {
        skus.add(sku);
        sku.assignTo(this);
    }

    /**
     * 이미지와 사이즈를 비운다.
     *
     * <p><b>채우기와 나뉘어 있는 이유가 중요하다.</b> 한 메서드에서 비우고 바로 채우면
     * Hibernate 가 DELETE 보다 INSERT 를 먼저 내보낸다. 그러면 같은 사이즈(95·100)를
     * 그대로 다시 저장하는 흔한 경우에 유니크 제약을 위반하며 터진다 —
     * 대표 이미지도 상품당 한 장이라 같은 문제가 난다.
     *
     * <p>그래서 호출하는 쪽이 <b>비운 뒤 flush</b> 하고 나서 채운다
     * ({@code AdminProductService.apply}). 순서가 곧 정확성이라 주석으로 남긴다.
     */
    public void clearChildren() {
        images.clear();
        skus.clear();
    }

    /**
     * 이미지 목록을 채운다. 반드시 {@link #clearChildren} + flush 이후에 부른다.
     *
     * <p>수정 화면은 "현재 상태 전체" 를 보낸다. 무엇이 추가·삭제됐는지 클라이언트가
     * 계산해서 보내게 하면, 두 사람이 동시에 고칠 때 한쪽의 계산이 틀어진다.
     */
    public void replaceImages(List<ProductImage> next) {
        next.forEach(this::addImage);
    }

    /** 사이즈 목록도 같은 이유로 통째로 교체한다. */
    public void replaceSkus(List<ProductSku> next) {
        next.forEach(this::addSku);
    }

    /** 초안으로 되돌린다. 공개된 상품을 내릴 때 쓴다. */
    public void unpublish() {
        this.status = ProductStatus.DRAFT;
    }

    public void publish() {
        if (!isPublishable()) {
            throw new IllegalStateException(
                    "공개에 필요한 값이 비어 있습니다 (이름·가격·제작 기간·대표 이미지): " + slug);
        }
        this.status = ProductStatus.PUBLISHED;
    }

    public UUID getId() {
        return id;
    }

    public String getSlug() {
        return slug;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public ProductCategory getCategory() {
        return category;
    }

    public ProductLine getLine() {
        return line;
    }

    public ProductStyle getStyle() {
        return style;
    }

    /** 분류와 짝이 맞지 않으면 거부한다 — 요청 검증이 먼저 막으므로 여기 걸리면 코드 버그다. */
    public void setStyle(ProductStyle style) {
        if (!ProductStyle.fits(style, category)) {
            throw new IllegalArgumentException("세부 분류 " + style + " 는 " + category + " 에 붙을 수 없다");
        }
        this.style = style;
    }

    public Long getPriceKrw() {
        return priceKrw;
    }

    public void setPriceKrw(Long priceKrw) {
        this.priceKrw = priceKrw;
    }

    public Long getListPriceKrw() {
        return listPriceKrw;
    }

    public void setListPriceKrw(Long listPriceKrw) {
        this.listPriceKrw = listPriceKrw;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getIntent() {
        return intent;
    }

    public void setIntent(String intent) {
        this.intent = intent;
    }

    public String getFeatures() {
        return features;
    }

    public void setFeatures(String features) {
        this.features = features;
    }

    public String getFabric() {
        return fabric;
    }

    public void setFabric(String fabric) {
        this.fabric = fabric;
    }

    public String getCare() {
        return care;
    }

    public void setCare(String care) {
        this.care = care;
    }

    public String getColor() {
        return color;
    }

    public String getManufacturer() {
        return manufacturer;
    }

    public String getCountryOfOrigin() {
        return countryOfOrigin;
    }

    public String getManufacturedOn() {
        return manufacturedOn;
    }

    /** 고시 네 항목은 같이 저장된다 — 따로 고치는 화면이 없다. */
    public void setNotice(String color, String manufacturer, String countryOfOrigin, String manufacturedOn) {
        this.color = color;
        this.manufacturer = manufacturer;
        this.countryOfOrigin = countryOfOrigin;
        this.manufacturedOn = manufacturedOn;
    }

    public Short getModelHeightCm() {
        return modelHeightCm;
    }

    public Short getModelWeightKg() {
        return modelWeightKg;
    }

    public String getModelSize() {
        return modelSize;
    }

    public void setModel(Short heightCm, Short weightKg, String size) {
        this.modelHeightCm = heightCm;
        this.modelWeightKg = weightKg;
        this.modelSize = size;
    }

    public MediaAsset getSizeChart() {
        return sizeChart;
    }

    public String getSizeChartAlt() {
        return sizeChartAlt;
    }

    /**
     * 차트를 설정한다. 이미지와 설명을 <b>함께</b> 받는다.
     *
     * <p>따로 두면 이미지만 바꾸고 설명은 옛것이 남는 일이 생긴다. 한 메서드로 묶어
     * 둘이 항상 같이 움직이게 한다. 이미지를 지울 때는 {@code (null, null)} 을 넘긴다.
     */
    public void setSizeChart(MediaAsset media, String alt) {
        if (media != null && (alt == null || alt.isBlank())) {
            throw new IllegalArgumentException("사이즈 차트에는 대체 텍스트가 필요합니다.");
        }
        this.sizeChart = media;
        this.sizeChartAlt = media == null ? null : alt;
    }

    public String getInstagramUrl() {
        return instagramUrl;
    }

    public void setInstagramUrl(String instagramUrl) {
        this.instagramUrl = instagramUrl;
    }

    public Short getLeadTimeDays() {
        return leadTimeDays;
    }

    public void setLeadTimeDays(Short leadTimeDays) {
        this.leadTimeDays = leadTimeDays;
    }

    public ProductStatus getStatus() {
        return status;
    }

    public int getDisplayOrder() {
        return displayOrder;
    }

    public void setDisplayOrder(int displayOrder) {
        this.displayOrder = displayOrder;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public List<ProductImage> getImages() {
        return List.copyOf(images);
    }

    public List<ProductSku> getSkus() {
        return List.copyOf(skus);
    }

    /*
     * id 는 애플리케이션이 만들어 넣으므로 저장 전에도 값이 있다.
     * 그래서 id 기준 비교가 안전하다 — 컬렉션에 넣은 뒤 저장해도 동일성이 흔들리지 않는다.
     */
    @Override
    public boolean equals(Object o) {
        return o instanceof Product other && id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
