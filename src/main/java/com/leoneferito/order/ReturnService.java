package com.leoneferito.order;

import com.leoneferito.common.error.ResourceNotFoundException;
import com.leoneferito.product.Product;
import com.leoneferito.product.ProductRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 교환·반품 (손님 쪽) — 신청 · 철회 · 주문 상세에 붙는 신청 내역.
 *
 * <p>신청 조건: 내 주문 · 배송 완료 · 사유별 기간 안({@link ReturnPolicy}) · 진행 중인 신청 없음 ·
 * 주문 수량을 넘지 않음(거절·철회된 신청은 다시 신청할 수 있게 빼고 센다).
 * 받을지 말지(주문 제작품의 단순 변심 등)는 관리자가 승인·거절로 정한다 — 여기서 미리 막지 않는다.
 */
@Service
public class ReturnService {

    private static final Logger log = LoggerFactory.getLogger(ReturnService.class);

    private final ShopOrderRepository orders;
    private final ReturnRequestRepository returns;
    private final ProductRepository products;
    private final ReturnPolicy policy;

    public ReturnService(ShopOrderRepository orders, ReturnRequestRepository returns, ProductRepository products,
                         ReturnPolicy policy) {
        this.orders = orders;
        this.returns = returns;
        this.products = products;
        this.policy = policy;
    }

    /** 신청할 한 줄. exchangeSize 는 교환일 때만 쓴다. */
    public record Line(UUID orderItemId, int quantity, String exchangeSize) {
    }

    @Transactional
    public ReturnRequest request(UUID memberId, String orderNumber, ReturnType type, ReturnReason reason,
                                 String detail, List<Line> lines) {
        // 주문 행을 잠근다 — 신청이 두 번 동시에 오면 둘 다 "진행 중 없음" 을 볼 수 있다.
        ShopOrder order = orders.findByOrderNumberForUpdate(orderNumber)
                .filter(o -> o.isOwnedBy(memberId))
                .orElseThrow(() -> new ResourceNotFoundException("주문 없음 orderNumber=" + orderNumber));

        if (order.getStatus() != OrderStatus.DELIVERED) {
            throw new ShopOrder.OrderStateException("배송이 끝난 주문만 교환·반품을 신청할 수 있습니다.");
        }
        if (!policy.open(order, reason, Instant.now())) {
            throw new ReturnException(reason.sellerFault()
                    ? "신청 기간이 지났습니다. 고객센터로 문의해 주세요."
                    : "교환·반품 신청 기간이 지났습니다. 상품에 문제가 있다면 '불량' 이나 '오배송' 으로 신청해 주세요.");
        }
        List<ReturnRequest> past = returns.findForOrder(order.getId());
        if (past.stream().anyMatch(r -> ReturnStatus.ACTIVE.contains(r.getStatus()))) {
            throw new ShopOrder.OrderStateException("이미 진행 중인 교환·반품 신청이 있습니다.");
        }
        if (lines == null || lines.isEmpty()) {
            throw new ReturnException("교환·반품할 상품을 골라 주세요.");
        }

        Map<UUID, OrderItem> byId = new HashMap<>();
        order.getItems().forEach(i -> byId.put(i.getId(), i));
        Map<UUID, Integer> taken = new HashMap<>();
        for (ReturnRequest r : past) {
            if (r.getStatus().holdsQuantity()) {
                r.getItems().forEach(ri -> taken.merge(ri.getOrderItem().getId(), (int) ri.getQuantity(), Integer::sum));
            }
        }

        Set<UUID> seen = new HashSet<>();
        List<ReturnItem> items = new ArrayList<>();
        for (Line line : lines) {
            OrderItem oi = byId.get(line.orderItemId());
            if (oi == null || !seen.add(line.orderItemId())) {
                throw new ReturnException("주문에 없는 상품이 있습니다. 화면을 새로고침해 주세요.");
            }
            int left = oi.getQuantity() - taken.getOrDefault(oi.getId(), 0);
            if (line.quantity() < 1 || line.quantity() > left) {
                throw new ReturnException(oi.getProductName() + " 은(는) " + Math.max(left, 0) + "벌까지 신청할 수 있습니다.");
            }
            String size = null;
            if (type == ReturnType.EXCHANGE) {
                size = line.exchangeSize() == null ? "" : line.exchangeSize().trim();
                if (size.isEmpty()) {
                    throw new ReturnException("교환받을 사이즈를 골라 주세요.");
                }
                requireOrderableSize(oi, size);
            }
            items.add(new ReturnItem(oi, line.quantity(), size));
        }

        ReturnRequest created = ReturnRequest.open(order, type, reason,
                detail == null || detail.isBlank() ? null : detail.trim(), items);
        returns.save(created);
        log.info("교환·반품 신청 orderNumber={} returnId={} type={} reason={}",
                orderNumber, created.getId(), type, reason);
        return loaded(created);
    }

    /** 승인 전 철회. */
    @Transactional
    public ReturnRequest withdraw(UUID memberId, UUID returnId) {
        ReturnRequest r = returns.findByIdForUpdate(returnId)
                .filter(x -> x.isOwnedBy(memberId))
                .orElseThrow(() -> new ResourceNotFoundException("신청 없음 id=" + returnId));
        r.withdraw();
        log.info("교환·반품 철회 returnId={}", returnId);
        return loaded(r);
    }

    /** 주문 상세에 붙는 것 — 이 주문의 신청 내역과 지금 신청할 수 있는지. */
    public record ForOrder(List<ReturnRequest> requests, boolean requestable, Instant changeOfMindDeadline,
                           Instant sellerFaultDeadline) {
    }

    @Transactional(readOnly = true)
    public ForOrder forOrder(ShopOrder order) {
        List<ReturnRequest> list = returns.findForOrder(order.getId());
        list.forEach(ReturnService::loaded);
        Instant now = Instant.now();
        boolean active = list.stream().anyMatch(r -> ReturnStatus.ACTIVE.contains(r.getStatus()));
        // 모든 벌을 이미 교환·반품했으면 더 신청할 것이 없다
        int held = list.stream().filter(r -> r.getStatus().holdsQuantity())
                .flatMap(r -> r.getItems().stream()).mapToInt(ReturnItem::getQuantity).sum();
        int ordered = order.getItems().stream().mapToInt(OrderItem::getQuantity).sum();
        return new ForOrder(list, !active && held < ordered && policy.anyOpen(order, now),
                policy.deadline(order, ReturnReason.SIZE).orElse(null),
                policy.deadline(order, ReturnReason.DEFECT).orElse(null));
    }

    /** 진행 중인 교환·반품이 있는가. 있으면 탈퇴를 막는다 — 환불·재발송 받을 곳이 사라진다. */
    @Transactional(readOnly = true)
    public boolean hasActive(UUID memberId) {
        return returns.existsByMemberIdAndStatusIn(memberId, ReturnStatus.ACTIVE);
    }

    private void requireOrderableSize(OrderItem oi, String size) {
        Product p = products.findById(oi.getProductId())
                .orElseThrow(() -> new ReturnException("이 상품은 교환할 수 없습니다. 반품으로 신청해 주세요."));
        boolean ok = p.getSkus().stream().anyMatch(s -> s.getSize().equals(size) && s.isOrderable());
        if (!ok) {
            throw new ReturnException(size + " 사이즈로는 지금 교환할 수 없습니다.");
        }
    }

    /** 항목 · 이력 · 주문 항목을 트랜잭션 안에서 읽어 둔다 (open-in-view 꺼짐 — OrderService.loaded 와 같은 이유). */
    static ReturnRequest loaded(ReturnRequest r) {
        r.getItems().forEach(i -> i.getOrderItem().getProductName());
        r.getEvents().size();
        r.getOrder().getOrderNumber();
        return r;
    }

    /** 신청 내용이 규칙에 맞지 않는다. 400. */
    public static class ReturnException extends RuntimeException {
        public ReturnException(String message) {
            super(message);
        }
    }
}
