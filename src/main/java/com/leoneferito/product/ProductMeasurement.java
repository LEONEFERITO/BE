package com.leoneferito.product;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 부위 실측 한 줄.
 *
 * <p>사이즈마다 값이 다르므로 상품이 아니라 SKU 에 붙는다.
 * 이 사이트의 주장("치수를 다 공개한다")이 실제로 서는 자리다.
 *
 * <p>{@link #toleranceCm}(허용 오차)를 함께 두는 이유: 실측을 공개하면
 * "1cm 다르다" 는 문의가 반드시 온다. 봉제 제품은 오차가 있는 게 정상이고,
 * 그 범위를 미리 밝히는 것이 분쟁을 줄인다.
 *
 * <p>값은 {@code double} 이 아니라 {@link BigDecimal} 이다. 0.5 단위를 다루는데
 * 부동소수점은 0.1 을 정확히 표현하지 못해 비교·합산에서 어긋난다.
 */
@Entity
@Table(name = "product_measurement")
public class ProductMeasurement {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_sku_id", nullable = false)
    private ProductSku sku;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MeasurementPart part;

    @Column(name = "value_cm", nullable = false)
    private BigDecimal valueCm;

    /** 허용 오차 ±cm. 아직 안 정했으면 {@code null}. */
    @Column(name = "tolerance_cm")
    private BigDecimal toleranceCm;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private Instant updatedAt;

    protected ProductMeasurement() {
        // JPA
    }

    public ProductMeasurement(UUID id, MeasurementPart part, BigDecimal valueCm,
                              BigDecimal toleranceCm) {
        this.id = Objects.requireNonNull(id, "id");
        this.part = Objects.requireNonNull(part, "part");
        this.valueCm = Objects.requireNonNull(valueCm, "valueCm");
        this.toleranceCm = toleranceCm;
    }

    /** {@link ProductSku#addMeasurement} 가 호출한다. */
    void assignTo(ProductSku sku) {
        this.sku = sku;
    }

    public UUID getId() {
        return id;
    }

    public ProductSku getSku() {
        return sku;
    }

    public MeasurementPart getPart() {
        return part;
    }

    public BigDecimal getValueCm() {
        return valueCm;
    }

    public BigDecimal getToleranceCm() {
        return toleranceCm;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ProductMeasurement other && id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
