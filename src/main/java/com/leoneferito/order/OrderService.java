package com.leoneferito.order;

import com.leoneferito.common.error.ResourceNotFoundException;
import com.leoneferito.media.MediaUrls;
import com.leoneferito.order.CartItem.CartException;
import com.leoneferito.payment.TossPaymentsClient;
import com.leoneferito.payment.TossPaymentsClient.PaymentException;
import com.leoneferito.product.Product;
import com.leoneferito.product.ProductRepository;
import com.leoneferito.product.ProductStatus;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 주문과 결제 (손님 쪽).
 *
 * <h2>흐름</h2>
 * <ol>
 *   <li>{@link #create} — 장바구니 줄로 주문서를 만든다. 금액은 여기서 계산해 굳힌다(PENDING_PAYMENT).</li>
 *   <li>화면이 토스 결제창을 연다. 주문번호 · 금액은 1에서 받은 값이다.</li>
 *   <li>토스가 successUrl 로 돌려보내면 화면이 {@link #confirm} 을 부른다. 서버는 <b>저장된 금액과 대조한 뒤</b>
 *       토스에 승인을 요청한다. 화면이 금액을 바꿔 보내도 여기서 걸린다.</li>
 * </ol>
 *
 * <h2>결제를 열지 않는 경우 (fail closed)</h2>
 * 토스 키가 없거나 배송비 정책이 없으면 주문서를 만들지 않는다. 결제할 수 없는 주문서가 쌓이거나,
 * 지어낸 배송비가 결제되는 것보다 낫다.
 *
 * <h2>웹훅으로 한 번 더 맞춘다 ({@link #reconcile})</h2>
 * 토스는 승인했는데 우리 저장이 실패하는 경우(승인 직후 서버가 죽는 등)를 위해서다. 웹훅 본문은 믿지 않고
 * 결제 키로 토스에 다시 물어 맞춘다.
 */
@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    /** 헷갈리는 글자(0/O, 1/I/L)를 뺀 32자. 손님이 전화로 불러 줄 수 있어야 한다. */
    private static final char[] ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789".toCharArray();
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    /** 손님 화면에 보이는 주문 — 결제 전에 떠난 주문서는 뺀다. */
    static final Set<OrderStatus> VISIBLE = EnumSet.complementOf(EnumSet.of(OrderStatus.PENDING_PAYMENT));

    private final ShopOrderRepository orders;
    private final CartItemRepository carts;
    private final ProductRepository products;
    private final MediaUrls mediaUrls;
    private final ShippingPolicy shipping;
    private final TossPaymentsClient toss;

    public OrderService(ShopOrderRepository orders, CartItemRepository carts, ProductRepository products,
                        MediaUrls mediaUrls, ShippingPolicy shipping, TossPaymentsClient toss) {
        this.orders = orders;
        this.carts = carts;
        this.products = products;
        this.mediaUrls = mediaUrls;
        this.shipping = shipping;
        this.toss = toss;
    }

    /** 결제를 받을 수 있는 상태인가. 화면이 결제 버튼을 열지 정할 때 쓴다. */
    public boolean paymentReady() {
        return toss.isReady() && shipping.isReady();
    }

    /**
     * 주문서 작성. 고른 장바구니 줄로 만든다(바로 구매는 한 줄만 고른다).
     * 줄 중 하나라도 지금 주문할 수 없으면 전체를 거절한다 — 일부만 조용히 빼고 결제하면 안 된다.
     */
    @Transactional
    public ShopOrder create(UUID memberId, List<UUID> cartItemIds, ShopOrder.Recipient recipient) {
        if (!toss.isReady()) {
            throw new PaymentException("NOT_CONFIGURED", "결제 준비 중입니다.");
        }
        Priced priced = price(memberId, cartItemIds);
        ShopOrder order = ShopOrder.open(memberId, newOrderNumber(), priced.items(), priced.shippingFeeKrw(),
                recipient, Instant.now());
        orders.save(order);
        log.info("주문서 작성 orderNumber={} total={}", order.getOrderNumber(), order.getTotalAmountKrw());
        return order;
    }

    /**
     * 주문서 화면의 금액 — 고른 줄만으로 계산한다(바로 구매는 한 줄). 주문서 작성과 <b>같은 계산</b>을 쓴다.
     * 화면이 합계를 따로 더하면 결제 금액과 어긋나는 날이 온다.
     */
    @Transactional(readOnly = true)
    public Quote quote(UUID memberId, List<UUID> cartItemIds) {
        Priced p = price(memberId, cartItemIds);
        long items = p.items().stream().mapToLong(OrderItem::getLineAmountKrw).sum();
        return new Quote(items, p.shippingFeeKrw(), items + p.shippingFeeKrw(),
                p.items().stream().mapToInt(OrderItem::getLeadTimeDays).max().orElse(0));
    }

    public record Quote(long itemsAmountKrw, long shippingFeeKrw, long totalAmountKrw, int longestLeadTimeDays) {
    }

    private record Priced(List<OrderItem> items, long shippingFeeKrw) {
    }

    private Priced price(UUID memberId, List<UUID> cartItemIds) {
        if (cartItemIds == null || cartItemIds.isEmpty()) {
            throw new CartException("주문할 상품을 골라 주세요.");
        }

        List<CartItem> picked = carts.findByMemberIdOrderByCreatedAtAsc(memberId).stream()
                .filter(i -> cartItemIds.contains(i.getId()))
                .toList();
        if (picked.size() != Set.copyOf(cartItemIds).size()) {
            throw new CartException("장바구니가 바뀌었습니다. 다시 확인해 주세요.");
        }

        Map<UUID, Product> byId = products.findAllById(picked.stream().map(CartItem::getProductId).toList())
                .stream().collect(Collectors.toMap(Product::getId, Function.identity()));

        List<OrderItem> items = new ArrayList<>();
        for (int i = 0; i < picked.size(); i++) {
            CartItem line = picked.get(i);
            Product p = byId.get(line.getProductId());
            if (p == null || p.getStatus() != ProductStatus.PUBLISHED || p.getPriceKrw() == null
                    || p.getLeadTimeDays() == null) {
                throw new CartException("판매가 끝났거나 주문할 수 없는 상품이 있습니다. 장바구니를 확인해 주세요.");
            }
            CartService.orderableSku(p, line.getSize());
            items.add(new OrderItem(UUID.randomUUID(), p.getId(), p.getSlug(), p.getName(),
                    p.mainImage().map(img -> mediaUrls.urlFor(img.getMedia())).orElse(null),
                    line.getSize(), p.getPriceKrw(), line.getQuantity(), p.getLeadTimeDays(), i));
        }

        long itemsAmount = items.stream().mapToLong(OrderItem::getLineAmountKrw).sum();
        OptionalLong fee = shipping.feeFor(itemsAmount);
        if (fee.isEmpty()) {
            throw new ShippingPolicyPendingException();
        }
        return new Priced(items, fee.getAsLong());
    }

    /**
     * 결제 승인. 토스 successUrl 로 돌아온 화면이 부른다.
     *
     * <p>같은 결제로 두 번 불려도(새로고침) 한 번만 승인한다 — 이미 그 결제 키로 결제된 주문이면 그대로 돌려준다.
     */
    @Transactional
    public ShopOrder confirm(UUID memberId, String orderNumber, String paymentKey, long amount) {
        ShopOrder order = mineLocked(memberId, orderNumber);

        if (order.getStatus() != OrderStatus.PENDING_PAYMENT) {
            if (paymentKey.equals(order.getPaymentKey())) {
                return loaded(order); // 새로고침 — 이미 처리했다
            }
            throw new ShopOrder.OrderStateException("이미 처리된 주문입니다.");
        }

        // 토스에 보내기 전에 대조한다. 화면이 보낸 금액은 믿지 않는다.
        if (amount != order.getTotalAmountKrw()) {
            log.warn("결제 금액 불일치 orderNumber={} expected={} got={}",
                    orderNumber, order.getTotalAmountKrw(), amount);
            throw new AmountMismatchException();
        }

        TossPaymentsClient.Payment payment = toss.confirm(paymentKey, orderNumber, order.getTotalAmountKrw());

        if (payment.totalAmount() == null || payment.totalAmount() != order.getTotalAmountKrw()
                || !"DONE".equals(payment.status())) {
            // 토스가 승인했는데 금액·상태가 다르다 — 있어선 안 되는 일. 결제 키를 남기고 사람이 본다.
            log.error("결제 승인 결과 불일치 orderNumber={} paymentKey={} status={} amount={}",
                    orderNumber, paymentKey, payment.status(), payment.totalAmount());
            throw new PaymentException("MISMATCH", "결제 확인 중 문제가 생겼습니다. 고객센터로 문의해 주세요.");
        }

        order.markPaid(paymentKey, payment.method(),
                payment.approvedAt() == null ? Instant.now() : payment.approvedAt().toInstant());

        // 결제한 줄은 장바구니에서 뺀다. 결제 실패면 그대로 남아 다시 시도할 수 있다.
        List<UUID> paidLines = carts.findByMemberIdOrderByCreatedAtAsc(memberId).stream()
                .filter(c -> order.getItems().stream().anyMatch(
                        i -> i.getProductId().equals(c.getProductId()) && i.getSize().equals(c.getSize())))
                .map(CartItem::getId)
                .toList();
        if (!paidLines.isEmpty()) {
            carts.deleteByMemberIdAndIdIn(memberId, paidLines);
        }

        log.info("결제 완료 orderNumber={} method={}", orderNumber, payment.method());
        return loaded(order);
    }

    /**
     * 토스 웹훅 (결제 상태 변경). 본문은 믿지 않는다 — 결제 키로 토스에 직접 조회한 값만 쓴다.
     *
     * <ul>
     *   <li>토스는 DONE 인데 우리는 결제 전 → 결제 완료로 맞춘다 (금액이 같을 때만).</li>
     *   <li>그 밖에 어긋나면(토스 상점관리자에서 직접 취소 등) 고치지 않고 로그를 남긴다 — 사람이 본다.
     *       돈의 상태를 웹훅 하나로 자동으로 되돌리면, 잘못된 판단 하나가 환불 사고가 된다.</li>
     * </ul>
     */
    @Transactional
    public void reconcile(String paymentKey) {
        TossPaymentsClient.Payment p = toss.fetch(paymentKey);
        if (p.orderId() == null) {
            return;
        }
        ShopOrder order = orders.findByOrderNumberForUpdate(p.orderId()).orElse(null);
        if (order == null) {
            log.warn("웹훅: 우리 주문이 아님 orderId={}", p.orderId());
            return;
        }
        if ("DONE".equals(p.status()) && order.getStatus() == OrderStatus.PENDING_PAYMENT) {
            if (p.totalAmount() == null || p.totalAmount() != order.getTotalAmountKrw()) {
                log.error("웹훅: 결제 금액 불일치 orderNumber={} paymentKey={} amount={}",
                        order.getOrderNumber(), paymentKey, p.totalAmount());
                return;
            }
            order.markPaid(paymentKey, p.method(),
                    p.approvedAt() == null ? Instant.now() : p.approvedAt().toInstant());
            log.warn("웹훅으로 결제 반영 orderNumber={} — 승인 응답을 놓친 주문", order.getOrderNumber());
            return;
        }
        boolean tossCancelled = "CANCELED".equals(p.status()) || "PARTIAL_CANCELED".equals(p.status());
        long tossRefunded = p.totalAmount() == null || p.balanceAmount() == null ? 0
                : p.totalAmount() - p.balanceAmount();
        if (tossCancelled && tossRefunded != order.getRefundedAmountKrw()) {
            log.error("웹훅: 환불액 불일치 — 사람이 확인 orderNumber={} toss={} ours={}",
                    order.getOrderNumber(), tossRefunded, order.getRefundedAmountKrw());
        }
    }

    /** 손님 취소. 결제 직후 · 제작 시작 전에만. 토스 환불이 먼저 성공해야 상태를 바꾼다. */
    @Transactional
    public ShopOrder cancelByCustomer(UUID memberId, String orderNumber) {
        ShopOrder order = mineLocked(memberId, orderNumber);
        if (!order.getStatus().customerCancellable()) {
            throw new ShopOrder.OrderStateException(
                    "제작이 시작된 주문은 직접 취소할 수 없습니다. 고객센터로 문의해 주세요.");
        }
        String reason = "고객 요청 취소";
        toss.cancel(order.getPaymentKey(), reason, "cancel-" + order.getOrderNumber());
        order.cancel(null, reason, order.getTotalAmountKrw(), Instant.now());
        log.info("주문 취소(고객) orderNumber={}", orderNumber);
        return loaded(order);
    }

    @Transactional(readOnly = true)
    public List<ShopOrder> myOrders(UUID memberId) {
        List<ShopOrder> found = orders.findByMemberIdAndStatusInOrderByCreatedAtDesc(memberId, VISIBLE);
        found.forEach(o -> o.getItems().size()); // 트랜잭션 안에서 항목을 읽어 둔다
        return found;
    }

    @Transactional(readOnly = true)
    public ShopOrder myOrder(UUID memberId, String orderNumber) {
        return loaded(mine(memberId, orderNumber));
    }

    /**
     * 항목과 이력을 트랜잭션 안에서 읽어 둔다. 응답은 트랜잭션 밖(컨트롤러)에서 만들어지는데
     * open-in-view 를 꺼 두었으므로(N+1 을 숨기지 않으려고) 여기서 읽지 않으면 그때 터진다.
     */
    static ShopOrder loaded(ShopOrder order) {
        order.getItems().size();
        order.getEvents().size();
        return order;
    }

    /** 진행 중인 주문이 있는가. 있으면 탈퇴를 막는다 — 환불받을 곳이 사라진다. */
    @Transactional(readOnly = true)
    public boolean hasOrdersInProgress(UUID memberId) {
        return orders.existsByMemberIdAndStatusIn(memberId,
                EnumSet.of(OrderStatus.PAID, OrderStatus.IN_PRODUCTION, OrderStatus.SHIPPED));
    }

    /** 내 주문만 찾는다. 남의 주문번호면 "없다" 와 같은 답 — 번호가 존재한다는 것도 새면 안 된다. */
    private ShopOrder mine(UUID memberId, String orderNumber) {
        return orders.findByOrderNumber(orderNumber)
                .filter(o -> o.isOwnedBy(memberId))
                .orElseThrow(() -> new ResourceNotFoundException("주문 없음 orderNumber=" + orderNumber));
    }

    /** 승인 · 취소용 — 행을 잠그고 찾는다 (ShopOrderRepository.findByOrderNumberForUpdate). */
    private ShopOrder mineLocked(UUID memberId, String orderNumber) {
        return orders.findByOrderNumberForUpdate(orderNumber)
                .filter(o -> o.isOwnedBy(memberId))
                .orElseThrow(() -> new ResourceNotFoundException("주문 없음 orderNumber=" + orderNumber));
    }

    /** 예) LF20261001-K7MQ2XRA. 날짜 + 무작위 8자 — 순번이면 하루 주문 수가 샌다. */
    static String newOrderNumber() {
        StringBuilder sb = new StringBuilder("LF")
                .append(LocalDate.now(SEOUL).format(DateTimeFormatter.BASIC_ISO_DATE))
                .append('-');
        for (int i = 0; i < 8; i++) {
            sb.append(ALPHABET[RANDOM.nextInt(ALPHABET.length)]);
        }
        return sb.toString();
    }

    /** 화면이 보낸 결제 금액이 주문 금액과 다르다. 토스에 보내지 않는다. */
    public static class AmountMismatchException extends RuntimeException {
        public AmountMismatchException() {
            super("결제 금액 불일치");
        }
    }

    /** 배송비 정책이 아직 없다 — 주문을 받지 않는다. */
    public static class ShippingPolicyPendingException extends RuntimeException {
        public ShippingPolicyPendingException() {
            super("배송비 정책 없음");
        }
    }
}
