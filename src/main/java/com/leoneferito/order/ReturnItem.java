package com.leoneferito.order;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.Objects;
import java.util.UUID;

/** 신청에 담긴 한 줄 — 어느 주문 항목을 몇 벌, 교환이면 어떤 사이즈로. */
@Entity
@Table(name = "return_request_item")
public class ReturnItem {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "return_id", updatable = false)
    private ReturnRequest request;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_item_id", updatable = false)
    private OrderItem orderItem;

    @Column(nullable = false, updatable = false)
    private short quantity;

    @Column(name = "exchange_size", updatable = false)
    private String exchangeSize;

    protected ReturnItem() {
        // JPA
    }

    ReturnItem(OrderItem orderItem, int quantity, String exchangeSize) {
        this.id = UUID.randomUUID();
        this.orderItem = Objects.requireNonNull(orderItem);
        this.quantity = (short) quantity;
        this.exchangeSize = exchangeSize;
    }

    void attachTo(ReturnRequest request) {
        this.request = request;
    }

    public UUID getId() {
        return id;
    }

    public OrderItem getOrderItem() {
        return orderItem;
    }

    public short getQuantity() {
        return quantity;
    }

    public String getExchangeSize() {
        return exchangeSize;
    }
}
