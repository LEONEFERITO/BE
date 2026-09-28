package com.leoneferito.product;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * <p><b>재고 수량 필드가 없다.</b> 주문 후 제작이라 세어 둘 물건이 없다.
 * 대신 {@link #orderable} 이 "이 사이즈를 제작할 수 있는가" 를 뜻한다 —
 * 패턴이 없거나 원단이 끊긴 경우 {@code false} 다.
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

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "is_orderable", nullable = false)
    private boolean orderable = true;

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
        this.id = Objects.requireNonNull(id, "id");
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

    public int getSortOrder() {
        return sortOrder;
    }

    public boolean isOrderable() {
        return orderable;
    }

    public void setOrderable(boolean orderable) {
        this.orderable = orderable;
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
