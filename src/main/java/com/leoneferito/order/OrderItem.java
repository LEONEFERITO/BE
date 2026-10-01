package com.leoneferito.order;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * 주문 항목 — 주문 당시 값의 **복사본**이다.
 *
 * <p>상품을 참조만 하면 나중에 상품 이름·가격을 고친 순간 과거 주문서도 바뀐다.
 * "그때 무엇을 얼마에 샀는지" 는 영수증처럼 굳어 있어야 한다. product_id 는 추적용으로만 둔다.
 */
@Entity
@Table(name = "order_item")
public class OrderItem {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id")
    private ShopOrder order;

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Column(name = "product_slug", nullable = false)
    private String productSlug;

    @Column(name = "product_name", nullable = false)
    private String productName;

    @Column(name = "image_url")
    private String imageUrl;

    @Column(nullable = false)
    private String size;

    @Column(name = "unit_price_krw", nullable = false)
    private long unitPriceKrw;

    @Column(nullable = false)
    private short quantity;

    @Column(name = "line_amount_krw", nullable = false)
    private long lineAmountKrw;

    @Column(name = "lead_time_days", nullable = false)
    private short leadTimeDays;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    protected OrderItem() {
        // JPA
    }

    OrderItem(UUID id, UUID productId, String productSlug, String productName, String imageUrl,
              String size, long unitPriceKrw, short quantity, short leadTimeDays, int sortOrder) {
        this.id = id;
        this.productId = productId;
        this.productSlug = productSlug;
        this.productName = productName;
        this.imageUrl = imageUrl;
        this.size = size;
        this.unitPriceKrw = unitPriceKrw;
        this.quantity = quantity;
        this.lineAmountKrw = Math.multiplyExact(unitPriceKrw, (long) quantity);
        this.leadTimeDays = leadTimeDays;
        this.sortOrder = sortOrder;
    }

    void attachTo(ShopOrder order) {
        this.order = order;
    }

    public UUID getId() {
        return id;
    }

    public UUID getProductId() {
        return productId;
    }

    public String getProductSlug() {
        return productSlug;
    }

    public String getProductName() {
        return productName;
    }

    public String getImageUrl() {
        return imageUrl;
    }

    public String getSize() {
        return size;
    }

    public long getUnitPriceKrw() {
        return unitPriceKrw;
    }

    public short getQuantity() {
        return quantity;
    }

    public long getLineAmountKrw() {
        return lineAmountKrw;
    }

    public short getLeadTimeDays() {
        return leadTimeDays;
    }

    public int getSortOrder() {
        return sortOrder;
    }
}
