package com.leoneferito.order;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 주문.
 *
 * <p>클래스 이름이 {@code Order} 가 아닌 이유: JPQL 에서 ORDER 는 예약어라 엔티티 이름으로 쓰면
 * 쿼리마다 이스케이프가 필요하고, {@code jakarta.persistence.criteria.Order} 와도 겹친다.
 *
 * <p><b>상태는 이 클래스의 메서드로만 바뀐다.</b> 메서드마다 "어느 상태에서 올 수 있는가" 를 검사하고
 * 이력({@link OrderEvent})을 남긴다. 상태 setter 가 있으면 검사와 이력을 건너뛰는 길이 생긴다.
 *
 * <p>금액은 만들 때 한 번 계산해 굳힌다. 결제 승인은 이 금액과 대조한다 — 브라우저가 보낸 금액은 믿지 않는다.
 */
@Entity(name = "ShopOrder")
@Table(name = "orders")
public class ShopOrder {

    @Id
    private UUID id;

    @Column(name = "order_number", nullable = false, unique = true, updatable = false)
    private String orderNumber;

    @Column(name = "member_id", nullable = false, updatable = false)
    private UUID memberId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrderStatus status;

    @Column(name = "order_name", nullable = false, updatable = false)
    private String orderName;

    @Column(name = "items_amount_krw", nullable = false, updatable = false)
    private long itemsAmountKrw;

    @Column(name = "shipping_fee_krw", nullable = false, updatable = false)
    private long shippingFeeKrw;

    @Column(name = "total_amount_krw", nullable = false, updatable = false)
    private long totalAmountKrw;

    @Column(name = "recipient_name", nullable = false)
    private String recipientName;

    @Column(name = "recipient_phone", nullable = false)
    private String recipientPhone;

    @Column(name = "zip_code", nullable = false)
    private String zipCode;

    @Column(nullable = false)
    private String address1;

    @Column
    private String address2;

    @Column(name = "delivery_memo")
    private String deliveryMemo;

    @Column(name = "agreed_at", nullable = false, updatable = false)
    private Instant agreedAt;

    @Column(name = "payment_key", unique = true)
    private String paymentKey;

    @Column(name = "payment_method")
    private String paymentMethod;

    @Column(name = "paid_at")
    private Instant paidAt;

    @Column
    private String courier;

    @Column(name = "tracking_number")
    private String trackingNumber;

    @Column(name = "shipped_at")
    private Instant shippedAt;

    @Column(name = "delivered_at")
    private Instant deliveredAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "cancel_reason")
    private String cancelReason;

    @Column(name = "refunded_amount_krw", nullable = false)
    private long refundedAmountKrw;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private Instant updatedAt;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL)
    @OrderBy("sortOrder ASC")
    private List<OrderItem> items = new ArrayList<>();

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL)
    @OrderBy("id ASC")
    private List<OrderEvent> events = new ArrayList<>();

    protected ShopOrder() {
        // JPA
    }

    /** 주문을 연다. 결제 전 상태다. 금액은 항목에서 계산한다 — 넘겨받지 않는다. */
    static ShopOrder open(UUID memberId, String orderNumber, List<OrderItem> items, long shippingFeeKrw,
                          Recipient recipient, Instant agreedAt) {
        if (items.isEmpty()) {
            throw new IllegalArgumentException("빈 주문");
        }
        ShopOrder order = new ShopOrder();
        order.id = UUID.randomUUID();
        order.orderNumber = Objects.requireNonNull(orderNumber);
        order.memberId = Objects.requireNonNull(memberId);
        order.status = OrderStatus.PENDING_PAYMENT;
        for (OrderItem item : items) {
            item.attachTo(order);
            order.items.add(item);
        }
        order.itemsAmountKrw = items.stream().mapToLong(OrderItem::getLineAmountKrw).reduce(0, Math::addExact);
        order.shippingFeeKrw = shippingFeeKrw;
        order.totalAmountKrw = Math.addExact(order.itemsAmountKrw, shippingFeeKrw);
        String first = items.getFirst().getProductName();
        order.orderName = items.size() == 1 ? first : first + " 외 " + (items.size() - 1) + "건";
        if (order.orderName.length() > 100) {
            order.orderName = order.orderName.substring(0, 100);
        }
        order.recipientName = recipient.name();
        order.recipientPhone = recipient.phone();
        order.zipCode = recipient.zipCode();
        order.address1 = recipient.address1();
        order.address2 = recipient.address2();
        order.deliveryMemo = recipient.memo();
        order.agreedAt = Objects.requireNonNull(agreedAt);
        order.events.add(new OrderEvent(order, null, OrderStatus.PENDING_PAYMENT, null, "주문서 작성"));
        return order;
    }

    /** 결제 승인 완료. 토스가 승인한 결제 키를 남긴다 — 환불은 이 키로 한다. */
    void markPaid(String paymentKey, String method, Instant paidAt) {
        require(OrderStatus.PENDING_PAYMENT);
        this.paymentKey = Objects.requireNonNull(paymentKey);
        this.paymentMethod = method;
        this.paidAt = paidAt;
        transition(OrderStatus.PAID, null, "결제 완료");
    }

    void startProduction(UUID actorId) {
        require(OrderStatus.PAID);
        transition(OrderStatus.IN_PRODUCTION, actorId, "제작 시작");
    }

    void ship(UUID actorId, String courier, String trackingNumber, Instant now) {
        if (status != OrderStatus.PAID && status != OrderStatus.IN_PRODUCTION) {
            throw new OrderStateException("발송할 수 없는 상태입니다: " + status);
        }
        this.courier = Objects.requireNonNull(courier);
        this.trackingNumber = Objects.requireNonNull(trackingNumber);
        this.shippedAt = now;
        transition(OrderStatus.SHIPPED, actorId, courier + " " + trackingNumber);
    }

    void markDelivered(UUID actorId, Instant now) {
        require(OrderStatus.SHIPPED);
        this.deliveredAt = now;
        transition(OrderStatus.DELIVERED, actorId, "배송 완료");
    }

    /** 취소 + 전액 환불 기록. 토스 취소는 부르는 쪽이 먼저 성공시킨다. */
    void cancel(UUID actorId, String reason, long refundedAmount, Instant now) {
        this.cancelReason = reason;
        this.cancelledAt = now;
        this.refundedAmountKrw = refundedAmount;
        transition(OrderStatus.CANCELLED, actorId, reason);
    }

    private void require(OrderStatus expected) {
        if (status != expected) {
            throw new OrderStateException("지금 상태(" + status + ")에서는 할 수 없습니다.");
        }
    }

    private void transition(OrderStatus to, UUID actorId, String note) {
        OrderStatus from = this.status;
        this.status = to;
        events.add(new OrderEvent(this, from, to, actorId, note == null ? null
                : note.length() > 300 ? note.substring(0, 300) : note));
    }

    public boolean isOwnedBy(UUID memberId) {
        return this.memberId.equals(memberId);
    }

    /** 배송지. 주문할 때 한 번 받는다. */
    public record Recipient(String name, String phone, String zipCode, String address1,
                            String address2, String memo) {
    }

    /** 지금 상태에서 할 수 없는 일. 409 로 나간다. */
    public static class OrderStateException extends RuntimeException {
        public OrderStateException(String message) {
            super(message);
        }
    }

    public UUID getId() {
        return id;
    }

    public String getOrderNumber() {
        return orderNumber;
    }

    public UUID getMemberId() {
        return memberId;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public String getOrderName() {
        return orderName;
    }

    public long getItemsAmountKrw() {
        return itemsAmountKrw;
    }

    public long getShippingFeeKrw() {
        return shippingFeeKrw;
    }

    public long getTotalAmountKrw() {
        return totalAmountKrw;
    }

    public String getRecipientName() {
        return recipientName;
    }

    public String getRecipientPhone() {
        return recipientPhone;
    }

    public String getZipCode() {
        return zipCode;
    }

    public String getAddress1() {
        return address1;
    }

    public String getAddress2() {
        return address2;
    }

    public String getDeliveryMemo() {
        return deliveryMemo;
    }

    public Instant getAgreedAt() {
        return agreedAt;
    }

    public String getPaymentKey() {
        return paymentKey;
    }

    public String getPaymentMethod() {
        return paymentMethod;
    }

    public Instant getPaidAt() {
        return paidAt;
    }

    public String getCourier() {
        return courier;
    }

    public String getTrackingNumber() {
        return trackingNumber;
    }

    public Instant getShippedAt() {
        return shippedAt;
    }

    public Instant getDeliveredAt() {
        return deliveredAt;
    }

    public Instant getCancelledAt() {
        return cancelledAt;
    }

    public String getCancelReason() {
        return cancelReason;
    }

    public long getRefundedAmountKrw() {
        return refundedAmountKrw;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public List<OrderItem> getItems() {
        return items;
    }

    public List<OrderEvent> getEvents() {
        return events;
    }
}
