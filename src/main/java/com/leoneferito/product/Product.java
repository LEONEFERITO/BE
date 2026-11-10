package com.leoneferito.product;

import com.leoneferito.media.MediaAsset;
import jakarta.persistence.CascadeType;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.hibernate.annotations.BatchSize;

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

    /** 라인. 라인 구분이 없는 상품(바지 · 신발 · 액세서리 등)은 {@code null} — V48, 2026-11-05 요청. */
    @Enumerated(EnumType.STRING)
    @Column
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

    /**
     * 수트 베스트 금액 (V44, 2026-10-31 "베스트 금액만큼 빠지게"). 2피스 = {@link #priceKrw} − 이 값.
     * {@code null} 이면 베스트가 있는 수트는 3피스만 판다. 베스트가 없는 수트(페리토)는 판매가가 곧 2피스 값이다.
     */
    @Column(name = "vest_price_krw")
    private Long vestPriceKrw;

    /**
     * 굽높이 옵션 (V49, 2026-11-06 고객 "270 사이즈에 굽높이 3cm 추가"). 켜면 손님이 사이즈와 함께 굽높이를 고르고,
     * 장바구니 · 주문의 사이즈 줄이 "270 · 굽 +3cm" 가 된다({@link HeelChoice}). 추가 금액은 없다. 수트 구성과는 함께 쓰지 않는다.
     */
    @Column(name = "heel_option", nullable = false)
    private boolean heelOption;

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

    /** 제작 기간 최소(V40). 있으면 "최소~leadTimeDays일". 발송 예정 계산은 늘 leadTimeDays(최대). */
    @Column(name = "lead_time_min_days")
    private Short leadTimeMinDays;

    /** 소분류 — 자유 태그, 관리자 전용(V40) */
    @Column(name = "sub_category")
    private String subCategory;

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

    /*
     * 관리자 전용 정보 (V39, 2026-10-27 고객 일괄 등록 양식). 손님 응답에는 넣지 않는다 — 원가 · 공급처가 밖으로 나가면 안 된다.
     */
    @Column(name = "admin_name")
    private String adminName;

    @Column(name = "fabric_supplier")
    private String fabricSupplier;

    @Column(name = "fabric_collection")
    private String fabricCollection;

    @Column(name = "fabric_code")
    private String fabricCode;

    @Column
    private String workshop;

    @Column(name = "cost_krw")
    private Long costKrw;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ProductStatus status = ProductStatus.DRAFT;

    /** 주문 이력이 있어 행만 남기고 지운 시각(V47). 있으면 관리자 목록 · 손님 화면 어디에도 없다. */
    @Column(name = "deleted_at")
    private Instant deletedAt;

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

    /**
     * 컬러 옵션 (V46, 2026-11-03 고객 "컬러별로 컬러명칭 · 여러 컬러 선택"). 관리자가 놓은 순서 그대로.
     * 비어 있으면 컬러를 고르지 않는 상품이다(지금까지처럼). 재고는 사이즈별 — 컬러끼리 나눠 세지 않는다.
     * 목록 화면이 여러 상품을 한 번에 그릴 때 한 상품씩 따로 읽지 않게 묶어서 읽는다(BatchSize).
     */
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "product_color", joinColumns = @JoinColumn(name = "product_id"))
    @OrderColumn(name = "sort_order")
    @BatchSize(size = 50)
    private List<Color> colors = new ArrayList<>();

    /**
     * 굽높이 추가 금액 (V50, 2026-11-09 고객 "금액은 직접 작성"). 0 번째가 +1cm, 5 번째가 +6cm. 기본 굽은 언제나 0원.
     * 비어 있거나 짧으면 없는 단계는 0원이다. 목록 화면이 여러 상품을 한 번에 그릴 때 묶어서 읽는다(BatchSize).
     */
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "product_heel_price", joinColumns = @JoinColumn(name = "product_id"))
    @OrderColumn(name = "sort_order")
    @Column(name = "price_krw", nullable = false)
    @BatchSize(size = 50)
    private List<Long> heelPrices = new ArrayList<>();

    /**
     * 함께 구매 상품 (V52, 2026-11-10 고객 "수트에 착장의 셔츠 · 타이 · 신발을 함께 고르게, 금액은 직접 설정").
     * 관리자가 놓은 순서. 금액(priceKrw)이 없으면 그 상품의 원래 판매가다. 함께 구매 금액은 이 상품(수트)과 같은 장바구니 · 주문에 있을 때만.
     */
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "product_addon", joinColumns = @JoinColumn(name = "product_id"))
    @OrderColumn(name = "sort_order")
    @BatchSize(size = 50)
    private List<Addon> addons = new ArrayList<>();

    /** 함께 구매 상품 하나 — 붙인 상품과 함께 구매 금액(없으면 원래 판매가) */
    @Embeddable
    public record Addon(@Column(name = "addon_product_id", nullable = false) UUID productId,
                        @Column(name = "price_krw") Long priceKrw) {
    }

    /** 컬러 하나 — 이름(손님 화면 · 주문서에 그대로)과 동그라미 색(#RRGGBB). */
    @Embeddable
    public record Color(@Column(nullable = false) String name, @Column(nullable = false) String hex) {
    }

    protected Product() {
        // JPA
    }

    public Product(UUID id, String slug, ProductCategory category, ProductLine line) {
        this.id = Objects.requireNonNull(id, "id");
        this.slug = Objects.requireNonNull(slug, "slug");
        this.category = Objects.requireNonNull(category, "category");
        this.line = line; // 없으면 라인 구분 없음(V48)
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

    /** 컬러 목록도 통째로 교체한다(V46). 값 목록이라 clearChildren 없이 바로 바꾼다. */
    public void replaceColors(List<Color> next) {
        colors.clear();
        colors.addAll(next);
    }

    public List<Color> getColors() {
        return List.copyOf(colors);
    }

    public List<Addon> getAddons() {
        return List.copyOf(addons);
    }

    /** 함께 구매 상품을 통째로 바꾼다(V52). 값 목록이라 컬러처럼 바로 바꾼다 */
    public void replaceAddons(List<Addon> next) {
        addons.clear();
        addons.addAll(next);
    }

    /** 이 상품에 붙인 함께 구매 상품이면 그 줄 */
    public Optional<Addon> addonFor(UUID productId) {
        return addons.stream().filter(a -> a.productId().equals(productId)).findFirst();
    }

    /**
     * 이 컬러로 담을 수 있는가 — 컬러가 있는 상품이면 목록 안의 이름이어야 하고, 없는 상품이면 빈 값이어야 한다.
     * 손님 화면을 거치지 않은 요청(옛 화면 · 직접 호출)도 여기서 막는다.
     */
    public boolean acceptsColor(String color) {
        String c = color == null ? "" : color;
        return colors.isEmpty() ? c.isEmpty() : colors.stream().anyMatch(x -> x.name().equals(c));
    }

    /** 초안으로 되돌린다. 공개된 상품을 내릴 때 쓴다. */
    public void unpublish() {
        this.status = ProductStatus.DRAFT;
    }

    /**
     * 지운다 — 주문 이력이 있는 상품(V47). 행은 주문 항목이 가리키므로 남기고, 손님 화면에서 내린 뒤
     * 주소(slug)를 비워 같은 주소로 새 상품을 만들 수 있게 한다. 주문 항목은 당시 주소를 따로 갖고 있다.
     */
    public void markDeleted(Instant now) {
        this.status = ProductStatus.DRAFT;
        this.slug = slug + "--deleted-" + id.toString().substring(0, 8);
        this.deletedAt = now;
    }

    /** 지우지 않은 상품인가. 관리자 화면은 이것만 다룬다. */
    public boolean isLive() {
        return deletedAt == null;
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

    /**
     * 분류를 바꾼다 (2026-11-09 고객 "이미 올린 제품도 카테고리 변경"). 주문 항목은 분류를 복사해 두지 않아 지난 주문에는 영향이 없다.
     * 새 분류에 맞지 않는 세부 분류(트라우저 핏 · 신발 종류)는 비운다 — 부르는 쪽(AdminProductService.apply)이 곧바로 요청의 값을 다시 넣는다.
     */
    public void setCategory(ProductCategory category) {
        this.category = Objects.requireNonNull(category, "category");
        if (!ProductStyle.fits(style, category)) {
            this.style = null;
        }
    }

    public ProductLine getLine() {
        return line;
    }

    /** 라인을 바꾸거나 비운다(라인 구분 없음, V48). 분류와 달리 수정에서 바꿀 수 있다 — 주문 항목은 라인을 복사해 두지 않는다. */
    public void setLine(ProductLine line) {
        this.line = line;
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

    public Long getVestPriceKrw() {
        return vestPriceKrw;
    }

    public void setVestPriceKrw(Long vestPriceKrw) {
        this.vestPriceKrw = vestPriceKrw;
    }

    public boolean isHeelOption() {
        return heelOption;
    }

    /** +1cm ~ +6cm 추가 금액(V50). 적은 만큼만 — 없는 단계는 0원 */
    public List<Long> getHeelPrices() {
        // 줄이 띄엄띄엄 있으면(직접 넣은 데이터) 빈 칸이 null 로 온다 — 0원으로 본다
        return heelPrices.stream().map(v -> v == null ? 0L : v).toList();
    }

    /** 굽 추가 금액을 통째로 바꾼다(V50). 뒤쪽의 0원 단계는 줄을 남기지 않는다 */
    public void replaceHeelPrices(List<Long> next) {
        List<Long> trimmed = new ArrayList<>(next.stream().map(v -> v == null ? 0L : v).toList());
        while (!trimmed.isEmpty() && trimmed.getLast() == 0L) {
            trimmed.removeLast();
        }
        heelPrices.clear();
        heelPrices.addAll(trimmed);
    }

    /** 이 굽의 추가 금액 — 기본 · 모르는 굽 · 적지 않은 단계는 0원 */
    public long heelExtraKrw(String heel) {
        int step = HeelChoice.HEELS.indexOf(heel); // 0 = 기본, 1 = +1cm …
        Long price = step >= 1 && step <= heelPrices.size() ? heelPrices.get(step - 1) : null;
        return price == null ? 0L : price;
    }

    /** 손님 화면용 — 굽 목록(HEELS)과 같은 순서 · 길이의 추가 금액. 기본은 0 */
    public List<Long> heelExtrasForDisplay() {
        return HeelChoice.HEELS.stream().map(this::heelExtraKrw).toList();
    }

    public void setHeelOption(boolean heelOption) {
        this.heelOption = heelOption;
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

    public String getAdminName() {
        return adminName;
    }

    public String getFabricSupplier() {
        return fabricSupplier;
    }

    public String getFabricCollection() {
        return fabricCollection;
    }

    public String getFabricCode() {
        return fabricCode;
    }

    public String getWorkshop() {
        return workshop;
    }

    public Long getCostKrw() {
        return costKrw;
    }

    /** 관리자 전용 정보 여섯 칸은 같이 저장된다(V39). */
    public void setInternal(String adminName, String fabricSupplier, String fabricCollection, String fabricCode,
                            String workshop, Long costKrw) {
        this.adminName = adminName;
        this.fabricSupplier = fabricSupplier;
        this.fabricCollection = fabricCollection;
        this.fabricCode = fabricCode;
        this.workshop = workshop;
        this.costKrw = costKrw;
    }

    public Short getLeadTimeDays() {
        return leadTimeDays;
    }

    public void setLeadTimeDays(Short leadTimeDays) {
        this.leadTimeDays = leadTimeDays;
    }

    public Short getLeadTimeMinDays() {
        return leadTimeMinDays;
    }

    /** 최대와 같거나 크면 범위가 아니다 — null 로 둔다(한 값) */
    public void setLeadTimeMinDays(Short leadTimeMinDays) {
        this.leadTimeMinDays = leadTimeMinDays != null && leadTimeDays != null && leadTimeMinDays >= leadTimeDays
                ? null : leadTimeMinDays;
    }

    public String getSubCategory() {
        return subCategory;
    }

    public void setSubCategory(String subCategory) {
        this.subCategory = subCategory;
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

    // ── 수트 구성 (V43, 2026-10-28 디자이너 "3피스 / 2피스 + 자켓 · 베스트 · 팬츠 사이즈 따로") ─────────────

    /** 수트 조각. 손님 화면 · 주문 줄에는 한글 이름이 나간다. */
    public enum Piece {
        JACKET("자켓"), VEST("베스트"), PANTS("팬츠");

        private final String label;

        Piece(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** 구성 — 3피스(자켓 + 베스트 + 팬츠, 값은 판매가) · 2피스(자켓 + 팬츠, 값은 판매가 − 베스트 금액 · 베스트가 없으면 판매가). */
    public enum SuitSet {
        THREE("3피스", List.of(Piece.JACKET, Piece.VEST, Piece.PANTS)),
        TWO("2피스", List.of(Piece.JACKET, Piece.PANTS));

        private final String label;
        private final List<Piece> pieces;

        SuitSet(String label, List<Piece> pieces) {
            this.label = label;
            this.pieces = pieces;
        }

        public String label() {
            return label;
        }

        public List<Piece> pieces() {
            return pieces;
        }
    }

    /**
     * 고른 구성 한 벌. 장바구니 · 주문의 size 칸에는 {@link #label()} 이 들어간다 — "3피스 · 자켓 L / 베스트 M / 팬츠 L".
     * 그래서 주문서 · 관리자 주문 · 엑셀 · 메일이 따로 고치지 않아도 구성과 조각별 사이즈를 그대로 보여 준다.
     *
     * <p>{@link #parse} 는 그 줄을 다시 읽는다. 읽은 값으로 다시 만든 줄이 들어온 줄과 글자 하나까지 같아야 받는다 —
     * 모양이 조금이라도 다르면(공백 · 순서 · 빠진 조각) 모르는 줄이다.
     */
    public record SuitChoice(SuitSet set, Map<Piece, String> sizes) {

        public SuitChoice {
            Objects.requireNonNull(set, "set");
            sizes = Map.copyOf(sizes);
        }

        public String label() {
            return set.label() + " · " + set.pieces().stream()
                    .map(p -> p.label() + " " + sizes.get(p))
                    .collect(Collectors.joining(" / "));
        }

        public static Optional<SuitChoice> parse(String text) {
            if (text == null) {
                return Optional.empty();
            }
            for (SuitSet set : SuitSet.values()) {
                String head = set.label() + " · ";
                if (!text.startsWith(head)) {
                    continue;
                }
                String[] parts = text.substring(head.length()).split(" / ", -1);
                if (parts.length != set.pieces().size()) {
                    return Optional.empty();
                }
                Map<Piece, String> sizes = new EnumMap<>(Piece.class);
                for (int i = 0; i < parts.length; i++) {
                    Piece piece = set.pieces().get(i);
                    String prefix = piece.label() + " ";
                    if (!parts[i].startsWith(prefix) || parts[i].length() == prefix.length()) {
                        return Optional.empty();
                    }
                    sizes.put(piece, parts[i].substring(prefix.length()));
                }
                SuitChoice choice = new SuitChoice(set, sizes);
                return choice.label().equals(text) ? Optional.of(choice) : Optional.empty();
            }
            return Optional.empty();
        }
    }

    /**
     * 고른 사이즈 + 굽높이 (V49). 장바구니 · 주문의 size 칸에는 {@link #label()} 이 들어간다 — "270 · 굽 기본", "270 · 굽 +3cm".
     * {@link #parse} 는 그 줄을 다시 읽는다. 다시 만든 줄이 글자 하나까지 같고, 굽이 {@link #HEELS} 안에 있어야 받는다.
     */
    public record HeelChoice(String size, String heel) {

        /** 고를 수 있는 굽높이 — 기본, 1 ~ 6cm 추가 (2026-11-06 고객). 손님 화면도 이 목록을 받아 그대로 보여 준다 */
        public static final List<String> HEELS = List.of("기본", "+1cm", "+2cm", "+3cm", "+4cm", "+5cm", "+6cm");

        private static final String MARK = " · 굽 ";

        public String label() {
            return size + MARK + heel;
        }

        public static Optional<HeelChoice> parse(String text) {
            if (text == null) {
                return Optional.empty();
            }
            int at = text.lastIndexOf(MARK);
            if (at <= 0) {
                return Optional.empty();
            }
            HeelChoice choice = new HeelChoice(text.substring(0, at), text.substring(at + MARK.length()));
            return HEELS.contains(choice.heel()) && choice.label().equals(text) ? Optional.of(choice) : Optional.empty();
        }
    }

    /** 조각이 붙은 사이즈가 하나라도 있으면 수트 구성 상품이다(관리자 저장이 섞지 못하게 막는다 — 전부 붙거나 전부 없거나). */
    public boolean isSuitSet() {
        return skus.stream().anyMatch(s -> s.getPiece() != null);
    }

    /** 베스트 사이즈가 있는가 — 3피스가 되는 수트(레오네). 페리토 수트는 자켓 + 팬츠뿐이다. */
    public boolean hasVest() {
        return skus.stream().anyMatch(s -> s.getPiece() == Piece.VEST);
    }

    /**
     * 2피스(자켓 + 팬츠) 한 벌 값 (V44). 베스트가 있는 수트는 판매가 − 베스트 금액(금액이 없거나 판매가 이상이면 2피스를 팔지 않는다),
     * 베스트가 없는 수트는 판매가 그대로.
     */
    public Long twoPiecePriceKrw() {
        if (priceKrw == null) {
            return null;
        }
        if (!hasVest()) {
            return priceKrw;
        }
        return vestPriceKrw != null && vestPriceKrw < priceKrw ? priceKrw - vestPriceKrw : null;
    }

    /** 이 구성을 파는가 — 수트 구성 상품이고, 그 구성의 값이 있고, 구성의 조각마다 사이즈가 하나 이상 있다. */
    public boolean offers(SuitSet set) {
        Long price = set == SuitSet.THREE ? priceKrw : twoPiecePriceKrw();
        return isSuitSet() && price != null
                && set.pieces().stream().allMatch(p -> skus.stream().anyMatch(s -> s.getPiece() == p));
    }

    /**
     * 이 사이즈 줄(장바구니 · 주문의 size)로 한 벌 살 때의 값. 보통 상품은 판매가, 수트 구성은 고른 구성의 값.
     * 모르는 줄이거나 팔지 않는 구성이면 {@code null} — 담기 · 주문이 거절한다. 화면이 보낸 값은 믿지 않고 여기서 다시 정한다.
     */
    public Long priceFor(String size) {
        return priceFor(size, null);
    }

    /**
     * 판매가 대신 쓸 값(base)이 있을 때 — 함께 구매 금액(V52). 굽 추가 금액은 그대로 더한다. 수트 구성 상품은 함께 구매로 붙지 않으므로 base 를 쓰지 않는다.
     */
    public Long priceFor(String size, Long base) {
        Long price = base != null && !isSuitSet() ? base : priceKrw;
        // 굽높이 옵션(V49) — 굽까지 고른 줄이어야 한다. 값 = 판매가 + 고른 굽의 추가 금액(V50)
        if (heelOption) {
            return price == null ? null : HeelChoice.parse(size).map(h -> price + heelExtraKrw(h.heel())).orElse(null);
        }
        if (!isSuitSet()) {
            return price;
        }
        return SuitChoice.parse(size)
                .filter(c -> offers(c.set()))
                .map(c -> c.set() == SuitSet.THREE ? priceKrw : twoPiecePriceKrw())
                .orElse(null);
    }

    /**
     * 이 사이즈 줄이 가리키는 사이즈 칸들 — 보통 상품은 한 칸, 수트 구성은 조각마다 한 칸(제작 가능 여부 · 재고는 부르는 쪽이 본다).
     * 하나라도 없으면(지워진 사이즈 · 모르는 줄 · 팔지 않는 구성) 빈 목록.
     */
    public List<ProductSku> skusFor(String size) {
        // 굽높이 옵션(V49) — "270 · 굽 +3cm" 의 재고 · 제작 가능 여부는 270 사이즈 칸이 정한다. 굽이 없는 줄은 모르는 줄
        if (heelOption) {
            return HeelChoice.parse(size).map(h -> plainSkus(h.size())).orElse(List.of());
        }
        if (!isSuitSet()) {
            return plainSkus(size);
        }
        Optional<SuitChoice> choice = SuitChoice.parse(size).filter(c -> offers(c.set()));
        if (choice.isEmpty()) {
            return List.of();
        }
        List<ProductSku> found = new ArrayList<>();
        for (Piece piece : choice.get().set().pieces()) {
            String want = choice.get().sizes().get(piece);
            Optional<ProductSku> sku = skus.stream()
                    .filter(s -> s.getPiece() == piece && s.getSize().equals(want))
                    .findFirst();
            if (sku.isEmpty()) {
                return List.of();
            }
            found.add(sku.get());
        }
        return found;
    }

    private List<ProductSku> plainSkus(String size) {
        return skus.stream().filter(s -> s.getPiece() == null && s.getSize().equals(size)).limit(1).toList();
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
