package com.leoneferito.order;

import com.leoneferito.common.error.ResourceNotFoundException;
import com.leoneferito.payment.TossPaymentsClient;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 관리자 주문 처리 — 제작 시작 · 발송(송장) · 배송 완료 · 취소/환불.
 *
 * <p>상태가 바뀔 수 있는지는 {@link ShopOrder} 가 판단한다. 여기서는 순서를 지킨다:
 * 취소는 <b>토스 환불이 먼저 성공해야</b> 상태를 바꾼다. 반대로 하면 "취소됨" 인데 돈은 안 돌아간 주문이 생긴다.
 */
@Service
public class AdminOrderService {

    private static final Logger log = LoggerFactory.getLogger(AdminOrderService.class);

    static final int PAGE_SIZE = 20;
    /** 숫자가 없는 검색어일 때 연락처 조건이 아무것도 걸지 않게 (AdminMemberService 와 같은 이유). */
    private static final String MATCH_NOTHING = "#";

    private final ShopOrderRepository orders;
    private final TossPaymentsClient toss;

    public AdminOrderService(ShopOrderRepository orders, TossPaymentsClient toss) {
        this.orders = orders;
        this.toss = toss;
    }

    /** 검색. status 가 없으면 결제 전 주문서를 뺀 전부. */
    @Transactional(readOnly = true)
    public Page<ShopOrder> search(String query, OrderStatus status, int page) {
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        String pattern = "%" + q.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
        String digits = q.replaceAll("[^0-9]", "");
        Set<OrderStatus> statuses = status == null ? OrderService.VISIBLE : EnumSet.of(status);
        Page<ShopOrder> found = orders.search(pattern, digits.isEmpty() ? MATCH_NOTHING : "%" + digits + "%",
                statuses, PageRequest.of(Math.max(page, 0), PAGE_SIZE, Sort.by(Sort.Direction.DESC, "createdAt")));
        found.forEach(o -> o.getItems().size());
        return found;
    }

    @Transactional(readOnly = true)
    public ShopOrder detail(String orderNumber) {
        return OrderService.loaded(find(orderNumber));
    }

    @Transactional
    public void startProduction(UUID actorId, String orderNumber) {
        find(orderNumber).startProduction(actorId);
        log.info("제작 시작 orderNumber={} actorId={}", orderNumber, actorId);
    }

    /** 발송. 택배사 · 송장번호를 남긴다 — 손님 주문 상세에 그대로 보인다. */
    @Transactional
    public void ship(UUID actorId, String orderNumber, String courier, String trackingNumber) {
        find(orderNumber).ship(actorId, courier.trim(), trackingNumber.trim(), Instant.now());
        log.info("발송 orderNumber={} actorId={}", orderNumber, actorId);
    }

    @Transactional
    public void markDelivered(UUID actorId, String orderNumber) {
        find(orderNumber).markDelivered(actorId, Instant.now());
        log.info("배송 완료 orderNumber={} actorId={}", orderNumber, actorId);
    }

    /** 취소 + 전액 환불. 발송 전(결제 완료 · 제작 중)에만. 발송 뒤에는 반품 절차로 간다. */
    @Transactional
    public void cancel(UUID actorId, String orderNumber, String reason) {
        // 잠그고 읽는다 — 손님 취소와 관리자 취소가 동시에 오면 환불이 두 번 나갈 수 있다.
        ShopOrder order = orders.findByOrderNumberForUpdate(orderNumber)
                .orElseThrow(() -> new ResourceNotFoundException("주문 없음 orderNumber=" + orderNumber));
        if (!order.getStatus().adminCancellable()) {
            throw new ShopOrder.OrderStateException(
                    "발송 전 주문만 취소할 수 있습니다. 발송된 주문은 반품으로 처리해 주세요.");
        }
        toss.cancel(order.getPaymentKey(), reason.trim(), "cancel-" + order.getOrderNumber());
        order.cancel(actorId, reason.trim(), order.getTotalAmountKrw(), Instant.now());
        log.info("주문 취소(관리자) orderNumber={} actorId={}", orderNumber, actorId);
    }

    private ShopOrder find(String orderNumber) {
        return orders.findByOrderNumber(orderNumber)
                .orElseThrow(() -> new ResourceNotFoundException("주문 없음 orderNumber=" + orderNumber));
    }
}
