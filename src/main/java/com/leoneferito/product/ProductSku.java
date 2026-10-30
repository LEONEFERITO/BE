package com.leoneferito.product;

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
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 사이즈 한 줄.
 *
 * <p>{@link #orderable} 은 "이 사이즈를 제작할 수 있는가" 다 — 패턴이 없거나 원단이 끊긴 경우 {@code false}.
 *
 * <p>{@link #stock} (V30, 2026-10-13 "사이즈별 재고") — 남은 수량. <b>비워 두면(null) 세지 않는다</b>:
 * 주문 후 제작이라 세어 둘 물건이 없는 지금까지의 방식이다. 숫자를 넣으면 그만큼만 판다 — 결제가 승인될 때 줄고
 * ({@code OrderService}), 발송 전 취소면 돌아온다. 0 이면 품절.
 *
 * <p>{@link #sortOrder} 를 따로 두는 이유: {@code size} 로 정렬하면 문자열 비교라
 * 100 이 95 앞에 온다. 숫자로 캐스팅하는 방법은 S/M/L 이 들어오는 순간 깨진다.
 */
@Entity
@Table(name = "product_sku")
public class ProductSku {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    /** 브랜드가 쓰는 표기 그대로. 숫자로 바꾸지 않는다. */
    @Column(nullable = false)
    private String size;

    /** 수트 조각(V43). {@code null} 이면 보통 사이즈. 수트 구성 상품은 사이즈마다 자켓 · 베스트 · 팬츠 중 하나가 붙는다. */
    @Enumerated(EnumType.STRING)
    @Column
    private Product.Piece piece;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "is_orderable", nullable = false)
    private boolean orderable = true;

    /** 남은 수량 (V30). null = 세지 않음(주문 후 제작), 0 = 품절. */
    @Column(name = "stock")
    private Integer stock;

    /** 이 사이즈를 누르면 보여 줄 메인 사진 칸 (V45). null 이면 사진을 바꾸지 않는다. */
    @Enumerated(EnumType.STRING)
    @Column(name = "image_kind")
    private ProductImageKind imageKind;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private Instant updatedAt;

    @OneToMany(mappedBy = "sku", cascade = CascadeType.ALL, orphanRemoval = true,
            fetch = FetchType.LAZY)
    private List<ProductMeasurement> measurements = new ArrayList<>();

    protected ProductSku() {
        // JPA
    }

    public ProductSku(UUID id, String size, int sortOrder) {
        this(id, null, size, sortOrder);
    }

    /** 수트 조각 사이즈 (V43). {@code piece} 가 null 이면 보통 사이즈. */
    public ProductSku(UUID id, Product.Piece piece, String size, int sortOrder) {
        this.id = Objects.requireNonNull(id, "id");
        this.piece = piece;
        this.size = Objects.requireNonNull(size, "size");
        this.sortOrder = sortOrder;
    }

    /** {@link Product#addSku} 가 호출한다. */
    void assignTo(Product product) {
        this.product = product;
    }

    public void addMeasurement(ProductMeasurement measurement) {
        measurements.add(measurement);
        measurement.assignTo(this);
    }

    public UUID getId() {
        return id;
    }

    public Product getProduct() {
        return product;
    }

    public String getSize() {
        return size;
    }

    public Product.Piece getPiece() {
        return piece;
    }

    /** 손님 · 관리자에게 보일 이름 — 보통 사이즈는 "L", 수트 조각은 "자켓 L". */
    public String displayName() {
        return piece == null ? size : piece.label() + " " + size;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    public boolean isOrderable() {
        return orderable;
    }

    public Integer getStock() {
        return stock;
    }

    /** null 이면 세지 않는다. 음수는 받지 않는다(요청 검증 · DB CHECK). */
    public void setStock(Integer stock) {
        if (stock != null && stock < 0) {
            throw new IllegalArgumentException("재고는 0 이상");
        }
        this.stock = stock;
    }

    /** 지금 주문할 수 있는가 — 제작할 수 있고, 수량을 센다면 하나라도 남았다. */
    public boolean isAvailable() {
        return orderable && (stock == null || stock > 0);
    }

    /** 이만큼 팔 수 있는가. */
    public boolean canSupply(int quantity) {
        return orderable && (stock == null || stock >= quantity);
    }

    /** 결제 승인 때 줄인다. 세지 않는 사이즈는 그대로. 모자라면 예외 — 부르는 쪽이 먼저 {@link #canSupply} 로 본다. */
    public void take(int quantity) {
        if (stock == null) {
            return;
        }
        if (stock < quantity) {
            throw new IllegalStateException("재고 부족 size=" + displayName() + " stock=" + stock + " want=" + quantity);
        }
        stock -= quantity;
    }

    /** 취소 때 되돌린다. 세지 않는 사이즈는 그대로. */
    public void putBack(int quantity) {
        if (stock != null) {
            stock += quantity;
        }
    }

    public void setOrderable(boolean orderable) {
        this.orderable = orderable;
    }

    public ProductImageKind getImageKind() {
        return imageKind;
    }

    /** 메인 사진 다섯 칸(대표 · 착용 · 디테일 · 추가 둘)만. 누끼 · 상세 이미지는 받지 않는다(요청 검증 · DB CHECK). */
    public void setImageKind(ProductImageKind imageKind) {
        this.imageKind = imageKind;
    }

    public List<ProductMeasurement> getMeasurements() {
        return List.copyOf(measurements);
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ProductSku other && id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
