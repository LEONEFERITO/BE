package com.leoneferito.order.api;

import com.leoneferito.order.ReturnEvent;
import com.leoneferito.order.ReturnItem;
import com.leoneferito.order.ReturnReason;
import com.leoneferito.order.ReturnRequest;
import com.leoneferito.order.ReturnStatus;
import com.leoneferito.order.ReturnType;
import com.leoneferito.order.ShopOrder;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 교환·반품 응답. 손님과 관리자가 같은 {@link View} 를 쓰고, 관리자 쪽에만 연락처 · 처리 주체가 붙는다. */
public final class ReturnResponse {

    private ReturnResponse() {
    }

    /** unitPriceKrw: 주문 당시 단가 — 관리자가 환불액을 정할 때 기준으로 본다. */
    public record Item(UUID orderItemId, String name, String imageUrl, String size, long unitPriceKrw, int quantity,
                       String exchangeSize) {

        static Item of(ReturnItem i) {
            return new Item(i.getOrderItem().getId(), i.getOrderItem().getProductName(),
                    i.getOrderItem().getImageUrl(), i.getOrderItem().getSize(), i.getOrderItem().getUnitPriceKrw(),
                    i.getQuantity(), i.getExchangeSize());
        }
    }

    public record Event(ReturnStatus status, String note, Instant at) {
        static Event of(ReturnEvent e) {
            return new Event(e.getToStatus(), e.getNote(), e.getCreatedAt());
        }
    }

    public record View(UUID id, String orderNumber, ReturnType type, ReturnReason reason, String detail,
                       ReturnStatus status, String adminNote, String rejectReason, long refundAmountKrw,
                       String reshipCourier, String reshipTrackingNumber, Instant createdAt,
                       List<Item> items, List<Event> events,
                       /* 손님이 지금 철회할 수 있는가 (승인 전) */ boolean withdrawable) {

        public static View of(ReturnRequest r) {
            return new View(r.getId(), r.getOrder().getOrderNumber(), r.getType(), r.getReason(), r.getDetail(),
                    r.getStatus(), r.getAdminNote(), r.getRejectReason(), r.getRefundAmountKrw(),
                    r.getReshipCourier(), r.getReshipTrackingNumber(), r.getCreatedAt(),
                    r.getItems().stream().map(Item::of).toList(),
                    r.getEvents().stream().map(Event::of).toList(),
                    r.getStatus() == ReturnStatus.REQUESTED);
        }
    }

    public record AdminRow(UUID id, String orderNumber, String orderName, String recipientName,
                           ReturnType type, ReturnReason reason, ReturnStatus status, int itemCount,
                           Instant createdAt) {

        static AdminRow of(ReturnRequest r) {
            ShopOrder o = r.getOrder();
            return new AdminRow(r.getId(), o.getOrderNumber(), o.getOrderName(), o.getRecipientName(),
                    r.getType(), r.getReason(), r.getStatus(),
                    r.getItems().stream().mapToInt(ReturnItem::getQuantity).sum(), r.getCreatedAt());
        }
    }

    public record AdminPage(List<AdminRow> items, int page, int totalPages, long totalElements) {

        static AdminPage of(org.springframework.data.domain.Page<ReturnRequest> page) {
            return new AdminPage(page.map(AdminRow::of).getContent(), page.getNumber(),
                    page.getTotalPages(), page.getTotalElements());
        }
    }

    public record AdminEvent(ReturnStatus from, ReturnStatus to, String note, boolean byAdmin, Instant at) {
        static AdminEvent of(ReturnEvent e) {
            return new AdminEvent(e.getFromStatus(), e.getToStatus(), e.getNote(), e.getActorId() != null,
                    e.getCreatedAt());
        }
    }

    /**
     * 관리자 상세 — 신청 + 회수할 주소(주문 배송지) + 환불 한도.
     * refundableKrw: 이 주문에서 아직 돌려주지 않은 금액. 환불액 입력의 상한이다.
     */
    public record AdminDetail(View request, String orderName, long orderTotalKrw, long refundableKrw,
                              OrderResponse.Recipient recipient, List<AdminEvent> events) {

        static AdminDetail of(ReturnRequest r) {
            ShopOrder o = r.getOrder();
            return new AdminDetail(View.of(r), o.getOrderName(), o.getTotalAmountKrw(), o.refundableKrw(),
                    new OrderResponse.Recipient(o.getRecipientName(), o.getRecipientPhone(), o.getZipCode(),
                            o.getAddress1(), o.getAddress2(), o.getDeliveryMemo()),
                    r.getEvents().stream().map(AdminEvent::of).toList());
        }
    }
}
