package com.leoneferito.order.api;

import com.leoneferito.order.OrderEvent;
import com.leoneferito.order.OrderItem;
import com.leoneferito.order.OrderStatus;
import com.leoneferito.order.ShopOrder;
import java.time.Instant;
import java.util.List;

/**
 * 주문 응답. 손님과 관리자가 같은 모양을 쓰되, 관리자 쪽에만 결제 키 같은 내부 값이 붙는다.
 * 손님 응답에는 결제 키를 넣지 않는다 — 손님이 쓸 데가 없고, 환불 API 의 열쇠다.
 */
public final class OrderResponse {

    private OrderResponse() {
    }

    /** 주문서를 만든 직후 — 화면이 이 값으로 토스 결제창을 연다. 금액은 서버가 계산한 값이다. */
    public record Created(String orderNumber, String orderName, long amount,
                          String customerKey, String customerName, String customerEmail) {
    }

    public record Summary(String orderNumber, OrderStatus status, String orderName, long totalAmountKrw,
                          int itemCount, String imageUrl, Instant createdAt, Instant paidAt) {

        public static Summary of(ShopOrder o) {
            List<OrderItem> items = o.getItems();
            return new Summary(o.getOrderNumber(), o.getStatus(), o.getOrderName(), o.getTotalAmountKrw(),
                    items.stream().mapToInt(OrderItem::getQuantity).sum(),
                    items.isEmpty() ? null : items.getFirst().getImageUrl(),
                    o.getCreatedAt(), o.getPaidAt());
        }
    }

    public record Item(String slug, String name, String imageUrl, String size, long unitPriceKrw,
                       int quantity, long lineAmountKrw, int leadTimeDays) {

        static Item of(OrderItem i) {
            return new Item(i.getProductSlug(), i.getProductName(), i.getImageUrl(), i.getSize(),
                    i.getUnitPriceKrw(), i.getQuantity(), i.getLineAmountKrw(), i.getLeadTimeDays());
        }
    }

    public record Recipient(String name, String phone, String zipCode, String address1, String address2,
                            String memo) {
    }

    public record Event(OrderStatus status, String note, Instant at) {
        static Event of(OrderEvent e) {
            return new Event(e.getToStatus(), e.getNote(), e.getCreatedAt());
        }
    }

    /** cancellable: 손님이 지금 직접 취소할 수 있는가 (결제 완료 · 제작 전). */
    public record Detail(String orderNumber, OrderStatus status, String orderName,
                         long itemsAmountKrw, long shippingFeeKrw, long totalAmountKrw, long refundedAmountKrw,
                         List<Item> items, Recipient recipient,
                         String paymentMethod, Instant paidAt,
                         String courier, String trackingNumber, Instant shippedAt, Instant deliveredAt,
                         Instant cancelledAt, String cancelReason,
                         Instant createdAt, List<Event> events,
                         boolean cancellable) {

        public static Detail of(ShopOrder o) {
            return new Detail(o.getOrderNumber(), o.getStatus(), o.getOrderName(),
                    o.getItemsAmountKrw(), o.getShippingFeeKrw(), o.getTotalAmountKrw(), o.getRefundedAmountKrw(),
                    o.getItems().stream().map(Item::of).toList(),
                    new Recipient(o.getRecipientName(), o.getRecipientPhone(), o.getZipCode(),
                            o.getAddress1(), o.getAddress2(), o.getDeliveryMemo()),
                    o.getPaymentMethod(), o.getPaidAt(),
                    o.getCourier(), o.getTrackingNumber(), o.getShippedAt(), o.getDeliveredAt(),
                    o.getCancelledAt(), o.getCancelReason(),
                    o.getCreatedAt(), o.getEvents().stream().map(Event::of).toList(),
                    o.getStatus().customerCancellable());
        }
    }

    public record AdminRow(String orderNumber, OrderStatus status, String orderName, long totalAmountKrw,
                           String recipientName, int itemCount, Instant createdAt, Instant paidAt) {

        static AdminRow of(ShopOrder o) {
            return new AdminRow(o.getOrderNumber(), o.getStatus(), o.getOrderName(), o.getTotalAmountKrw(),
                    o.getRecipientName(), o.getItems().stream().mapToInt(OrderItem::getQuantity).sum(),
                    o.getCreatedAt(), o.getPaidAt());
        }
    }

    public record AdminPage(List<AdminRow> items, int page, int totalPages, long totalElements) {

        static AdminPage of(org.springframework.data.domain.Page<ShopOrder> page) {
            return new AdminPage(page.map(AdminRow::of).getContent(), page.getNumber(),
                    page.getTotalPages(), page.getTotalElements());
        }
    }

    /** 관리자 상세 — 손님 상세 + 결제 키(토스 상점관리자에서 찾을 때) + 이력의 처리 주체. */
    public record AdminDetail(Detail order, String paymentKey, Instant agreedAt, List<AdminEvent> events) {
    }

    public record AdminEvent(OrderStatus from, OrderStatus to, String note, boolean byAdmin, Instant at) {
        static AdminEvent of(OrderEvent e) {
            return new AdminEvent(e.getFromStatus(), e.getToStatus(), e.getNote(), e.getActorId() != null,
                    e.getCreatedAt());
        }
    }

    static AdminDetail adminDetail(ShopOrder o) {
        return new AdminDetail(Detail.of(o), o.getPaymentKey(), o.getAgreedAt(),
                o.getEvents().stream().map(AdminEvent::of).toList());
    }
}
