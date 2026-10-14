package com.leoneferito.order;

import com.leoneferito.common.error.ResourceNotFoundException;
import com.leoneferito.media.MediaUrls;
import com.leoneferito.order.CartItem.CartException;
import com.leoneferito.payment.PaymentGateway;
import com.leoneferito.payment.PaymentGateway.PaymentException;
import com.leoneferito.product.FrontRebuildTrigger;
import com.leoneferito.product.Product;
import com.leoneferito.product.ProductRepository;
import com.leoneferito.product.ProductSku;
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
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 주문과 결제 (손님 쪽).
 *
 * <h2>흐름</h2>
 * <ol>
 *   <li>{@link #create} — 장바구니 줄로 주문서를 만든다. 금액은 여기서 계산해 굳힌다(PENDING_PAYMENT).</li>
 *   <li>화면이 결제사 결제창을 연다. 주문번호 · 금액은 1에서 받은 값이다.</li>
 *   <li>승인 — 서버는 <b>저장된 금액과 대조한 뒤</b> 결제사에 승인을 요청한다. 화면이 금액을 바꿔 보내도 여기서 걸린다.
 *     <ul>
 *       <li>나이스페이(기본): 결제창이 API 로 바로 돌아온다 → {@link #approveReturned} (로그인 세션 없이, 서명으로 확인).</li>
 *       <li>토스: 화면의 successUrl 로 돌아오고 화면이 {@link #confirm} 을 부른다.</li>
 *     </ul>
 *   </li>
 * </ol>
 * 결제사는 {@link PaymentGateway} 하나로 본다 — 어느 결제사인지는 설정(app.payment.provider)이 정한다.
 *
 * <h2>결제를 열지 않는 경우 (fail closed)</h2>
 * 결제사 키가 없거나 배송비 정책이 없으면 주문서를 만들지 않는다. 결제할 수 없는 주문서가 쌓이거나,
 * 지어낸 배송비가 결제되는 것보다 낫다.
 *
 * <h2>재고 (V30, 사이즈별 남은 수량)</h2>
 * 수량을 세는 사이즈는 주문서를 만들 때 한 번 보고(모자라면 주문서를 만들지 않는다), <b>결제를 승인하기 직전</b>에
 * 그 사이즈 행을 잠그고 다시 본 뒤 줄인다({@link #takeStock}). 모자라면 결제사에 승인을 보내지 않는다 —
 * 승인하지 않은 결제는 청구되지 않는다. 승인이 실패하면 트랜잭션이 되돌아가 줄인 수량도 돌아온다.
 * 발송 전 취소(손님 · 관리자)면 돌려놓는다({@link #restock}). 반품은 자동으로 돌려놓지 않는다 — 돌아온 옷의 상태를 사람이 본다.
 * 손님 화면(정적)은 빌드 때 품절 · "몇 벌 남음" 을 굳힌다 — 그래서 수량이 화면에 보이는 범위(5벌 이하 · 품절 · 품절에서 돌아옴)로
 * 바뀌면 다시 만든다({@link #shownStockChanged}). 화면이 늦어도 장바구니 · 주문서는 서버가 지금 수량으로 막는다.
 *
 * <h2>웹훅으로 한 번 더 맞춘다 ({@link #reconcile})</h2>
 * 결제사는 승인했는데 우리 저장이 실패하는 경우(승인 직후 서버가 죽는 등)를 위해서다. 웹훅 본문은 믿지 않고
 * 결제 키로 결제사에 다시 물어 맞춘다.
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
    private final PaymentGateway gateway;
    private final ApplicationEventPublisher events;

    public OrderService(ShopOrderRepository orders, CartItemRepository carts, ProductRepository products,
                        MediaUrls mediaUrls, ShippingPolicy shipping, PaymentGateway gateway,
                        ApplicationEventPublisher events) {
        this.events = events;
        this.orders = orders;
        this.carts = carts;
        this.products = products;
        this.mediaUrls = mediaUrls;
        this.shipping = shipping;
        this.gateway = gateway;
    }

    /** 결제를 받을 수 있는 상태인가. 화면이 결제 버튼을 열지 정할 때 쓴다. */
    public boolean paymentReady() {
        return gateway.isReady() && shipping.isReady();
    }

    /** 결제사 이름 — "nicepay" · "toss". */
    public String paymentProvider() {
        return gateway.name();
    }

    /** 결제창 공개 키(나이스 clientId · 토스 클라이언트 키). */
    public String paymentClientKey() {
        return gateway.clientKey();
    }

    /**
     * 주문서 작성. 고른 장바구니 줄로 만든다(바로 구매는 한 줄만 고른다).
     * 줄 중 하나라도 지금 주문할 수 없으면 전체를 거절한다 — 일부만 조용히 빼고 결제하면 안 된다.
     */
    @Transactional
    public ShopOrder create(UUID memberId, List<UUID> cartItemIds, ShopOrder.Recipient recipient) {
        if (!gateway.isReady()) {
            throw new PaymentException("NOT_CONFIGURED", "결제 준비 중입니다.");
        }
        Priced priced = price(memberId, cartItemIds, recipient.zipCode());
        ShopOrder order = ShopOrder.open(memberId, newOrderNumber(), priced.items(), priced.fee().totalKrw(),
                recipient, Instant.now());
        orders.save(order);
        log.info("주문서 작성 orderNumber={} total={}", order.getOrderNumber(), order.getTotalAmountKrw());
        return order;
    }

    /**
     * 주문서 화면의 금액 — 고른 줄만으로 계산한다(바로 구매는 한 줄). 주문서 작성과 <b>같은 계산</b>을 쓴다.
     * 화면이 합계를 따로 더하면 결제 금액과 어긋나는 날이 온다.
     * 우편번호를 주면 제주 · 도서산간 추가 배송비까지 — 주소를 넣기 전에는 기본 배송비만이다.
     */
    @Transactional(readOnly = true)
    public Quote quote(UUID memberId, List<UUID> cartItemIds, String zipCode) {
        Priced p = price(memberId, cartItemIds, zipCode);
        long items = p.items().stream().mapToLong(OrderItem::getLineAmountKrw).sum();
        return new Quote(items, p.fee().totalKrw(), items + p.fee().totalKrw(),
                p.items().stream().mapToInt(OrderItem::getLeadTimeDays).max().orElse(0),
                p.fee().baseKrw(), p.fee().extraKrw(), p.fee().extraLabel(), shipping.getFreeThresholdKrw());
    }

    /**
     * shippingFeeKrw = 기본(shippingBaseKrw) + 제주 · 도서산간(remoteExtraKrw). remoteAreaLabel 은 추가가 붙을 때만(예: 제주).
     * freeShippingThresholdKrw — 이 금액 이상이면 기본 배송비 0원(없으면 null).
     */
    public record Quote(long itemsAmountKrw, long shippingFeeKrw, long totalAmountKrw, int longestLeadTimeDays,
                        long shippingBaseKrw, long remoteExtraKrw, String remoteAreaLabel, Long freeShippingThresholdKrw) {
    }

    private record Priced(List<OrderItem> items, ShippingPolicy.Fee fee) {
    }

    private Priced price(UUID memberId, List<UUID> cartItemIds, String zipCode) {
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
            CartService.orderableSku(p, line.getSize(), line.getQuantity());
            items.add(new OrderItem(UUID.randomUUID(), p.getId(), p.getSlug(), p.getName(),
                    p.mainImage().map(img -> mediaUrls.urlFor(img.getMedia())).orElse(null),
                    line.getSize(), p.getPriceKrw(), line.getQuantity(), p.getLeadTimeDays(), i));
        }

        long itemsAmount = items.stream().mapToLong(OrderItem::getLineAmountKrw).sum();
        ShippingPolicy.Fee fee = shipping.feeFor(itemsAmount, zipCode).orElseThrow(ShippingPolicyPendingException::new);
        return new Priced(items, fee);
    }

    /**
     * 결제 승인 (토스 — 화면의 successUrl 이 부른다). 내 주문만.
     *
     * <p>같은 결제로 두 번 불려도(새로고침) 한 번만 승인한다 — 이미 그 결제 키로 결제된 주문이면 그대로 돌려준다.
     */
    @Transactional
    public ShopOrder confirm(UUID memberId, String orderNumber, String paymentKey, long amount) {
        return approveLocked(mineLocked(memberId, orderNumber), paymentKey, amount);
    }

    /**
     * 결제 승인 (나이스페이 — 결제창이 API 로 바로 돌려보낸다). 결제창에서 돌아오는 요청에는 로그인 쿠키가 실리지 않아
     * 주문번호로 찾는다. 그래도 안전한 이유: 부르는 쪽이 나이스페이 서명을 먼저 확인했고, 승인 금액은 저장된 금액이며,
     * 승인은 나이스페이가 그 결제 키(tid)로 직접 확인한다.
     */
    @Transactional
    public ShopOrder approveReturned(String orderNumber, String paymentKey, long amount) {
        ShopOrder order = orders.findByOrderNumberForUpdate(orderNumber)
                .orElseThrow(() -> new ResourceNotFoundException("주문 없음 " + orderNumber));
        return approveLocked(order, paymentKey, amount);
    }

    private ShopOrder approveLocked(ShopOrder order, String paymentKey, long amount) {
        String orderNumber = order.getOrderNumber();
        if (order.getStatus() != OrderStatus.PENDING_PAYMENT) {
            if (paymentKey.equals(order.getPaymentKey())) {
                return loaded(order); // 새로고침 — 이미 처리했다
            }
            throw new ShopOrder.OrderStateException("이미 처리된 주문입니다.");
        }

        // 결제사에 보내기 전에 대조한다. 화면 · 결제창이 보낸 금액은 믿지 않는다.
        if (amount != order.getTotalAmountKrw()) {
            log.warn("결제 금액 불일치 orderNumber={} expected={} got={}",
                    orderNumber, order.getTotalAmountKrw(), amount);
            throw new AmountMismatchException();
        }

        // 재고 — 승인을 보내기 전에 잠그고 줄인다. 모자라면 여기서 멈춘다(결제사에 승인을 보내지 않으니 청구되지 않는다)
        if (takeStock(order)) {
            events.publishEvent(new FrontRebuildTrigger.CatalogChanged("재고"));
        }

        PaymentGateway.Payment payment = gateway.approve(paymentKey, orderNumber, order.getTotalAmountKrw());

        if (payment.totalAmount() == null || payment.totalAmount() != order.getTotalAmountKrw()
                || payment.status() != PaymentGateway.Status.PAID) {
            // 결제사가 승인했는데 금액·상태가 다르다 — 있어선 안 되는 일. 결제 키를 남기고 사람이 본다.
            log.error("결제 승인 결과 불일치 orderNumber={} paymentKey={} status={} amount={}",
                    orderNumber, paymentKey, payment.status(), payment.totalAmount());
            throw new PaymentException("MISMATCH", "결제 확인 중 문제가 생겼습니다. 고객센터로 문의해 주세요.");
        }

        order.markPaid(paymentKey, payment.method(),
                payment.approvedAt() == null ? Instant.now() : payment.approvedAt());
        // 주문 알림 — 손님 · 관리자 메일. 커밋된 뒤에 나간다(OrderNotifier)
        events.publishEvent(new OrderNotifier.Notice(OrderNotifier.Kind.PAID, orderNumber));

        // 결제한 줄은 장바구니에서 뺀다. 결제 실패면 그대로 남아 다시 시도할 수 있다.
        UUID memberId = order.getMemberId();
        List<UUID> paidLines = carts.findByMemberIdOrderByCreatedAtAsc(memberId).stream()
                .filter(c -> order.getItems().stream().anyMatch(
                        i -> i.getProductId().equals(c.getProductId()) && i.getSize().equals(c.getSize())))
                .map(CartItem::getId)
                .toList();
        if (!paidLines.isEmpty()) {
            carts.deleteByMemberIdAndIdIn(memberId, paidLines);
        }

        log.info("결제 완료 orderNumber={} gateway={} method={}", orderNumber, gateway.name(), payment.method());
        return loaded(order);
    }

    /**
     * 결제사 웹훅 (결제 상태 변경). 본문은 믿지 않는다 — 결제 키로 결제사에 직접 조회한 값만 쓴다.
     *
     * <ul>
     *   <li>결제사는 결제 완료인데 우리는 결제 전 → 결제 완료로 맞춘다 (금액이 같을 때만).</li>
     *   <li>그 밖에 어긋나면(결제사 가맹점관리자에서 직접 취소 등) 고치지 않고 로그를 남긴다 — 사람이 본다.
     *       돈의 상태를 웹훅 하나로 자동으로 되돌리면, 잘못된 판단 하나가 환불 사고가 된다.</li>
     * </ul>
     */
    @Transactional
    public void reconcile(String paymentKey) {
        PaymentGateway.Payment p = gateway.fetch(paymentKey);
        if (p.orderId() == null) {
            return;
        }
        ShopOrder order = orders.findByOrderNumberForUpdate(p.orderId()).orElse(null);
        if (order == null) {
            log.warn("웹훅: 우리 주문이 아님 orderId={}", p.orderId());
            return;
        }
        if (p.status() == PaymentGateway.Status.PAID && order.getStatus() == OrderStatus.PENDING_PAYMENT) {
            if (p.totalAmount() == null || p.totalAmount() != order.getTotalAmountKrw()) {
                log.error("웹훅: 결제 금액 불일치 orderNumber={} paymentKey={} amount={}",
                        order.getOrderNumber(), paymentKey, p.totalAmount());
                return;
            }
            order.markPaid(paymentKey, p.method(), p.approvedAt() == null ? Instant.now() : p.approvedAt());
            events.publishEvent(new OrderNotifier.Notice(OrderNotifier.Kind.PAID, order.getOrderNumber()));
            // 이미 결제된 돈이다 — 모자라도 막지 않고 남은 만큼만 줄인 뒤 사람이 보게 남긴다
            if (takeStockLeniently(order)) {
                events.publishEvent(new FrontRebuildTrigger.CatalogChanged("재고"));
            }
            log.warn("웹훅으로 결제 반영 orderNumber={} — 승인 응답을 놓친 주문", order.getOrderNumber());
            return;
        }
        boolean cancelled = p.status() == PaymentGateway.Status.CANCELLED
                || p.status() == PaymentGateway.Status.PARTIAL_CANCELLED;
        if (cancelled && p.refundedAmount() != order.getRefundedAmountKrw()) {
            log.error("웹훅: 환불액 불일치 — 사람이 확인 orderNumber={} gateway={} ours={}",
                    order.getOrderNumber(), p.refundedAmount(), order.getRefundedAmountKrw());
        }
    }

    /** 손님 취소. 결제 직후 · 제작 시작 전에만. 결제사 환불이 먼저 성공해야 상태를 바꾼다. */
    @Transactional
    public ShopOrder cancelByCustomer(UUID memberId, String orderNumber) {
        ShopOrder order = mineLocked(memberId, orderNumber);
        if (!order.getStatus().customerCancellable()) {
            throw new ShopOrder.OrderStateException(
                    "제작이 시작된 주문은 직접 취소할 수 없습니다. 고객센터로 문의해 주세요.");
        }
        String reason = "고객 요청 취소";
        gateway.cancel(order.getPaymentKey(), order.getOrderNumber(), reason, "cancel-" + order.getOrderNumber(), null);
        order.cancel(null, reason, order.getTotalAmountKrw(), Instant.now());
        events.publishEvent(new OrderNotifier.Notice(OrderNotifier.Kind.CANCELLED_BY_CUSTOMER, orderNumber));
        if (restock(products, order)) {
            events.publishEvent(new FrontRebuildTrigger.CatalogChanged("재고"));
        }
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

    /**
     * 결제 승인 직전 — 수량을 세는 사이즈를 잠그고 줄인다. 하나라도 모자라면 전부 그대로 두고 거절한다(트랜잭션).
     * 손님 화면에 보이는 수량이 바뀌었으면 true.
     */
    private boolean takeStock(ShopOrder order) {
        boolean shown = false;
        for (OrderItem item : order.getItems()) {
            ProductSku sku = products.findSkuForUpdate(item.getProductId(), item.getSize()).orElse(null);
            if (sku == null || sku.getStock() == null) {
                continue;
            }
            if (!sku.canSupply(item.getQuantity())) {
                log.info("재고 부족으로 결제 승인 안 함 orderNumber={} size={} stock={} want={}",
                        order.getOrderNumber(), item.getSize(), sku.getStock(), item.getQuantity());
                throw new PaymentException("OUT_OF_STOCK", item.getProductName() + " " + item.getSize()
                        + " 사이즈가 그사이 품절되어 결제를 진행하지 않았습니다. 결제 금액은 청구되지 않습니다.");
            }
            int before = sku.getStock();
            sku.take(item.getQuantity());
            shown |= shownStockChanged(before, sku.getStock());
        }
        return shown;
    }

    /** 이미 결제된 주문(웹훅으로 늦게 안 경우) — 모자라면 0 까지만 줄이고 로그를 남긴다. */
    private boolean takeStockLeniently(ShopOrder order) {
        boolean shown = false;
        for (OrderItem item : order.getItems()) {
            ProductSku sku = products.findSkuForUpdate(item.getProductId(), item.getSize()).orElse(null);
            if (sku == null || sku.getStock() == null) {
                continue;
            }
            int before = sku.getStock();
            if (before < item.getQuantity()) {
                log.error("재고보다 많이 결제됨 — 사람이 확인 orderNumber={} size={} stock={} want={}",
                        order.getOrderNumber(), item.getSize(), before, item.getQuantity());
            }
            sku.take(Math.min(before, item.getQuantity()));
            shown |= shownStockChanged(before, sku.getStock());
        }
        return shown;
    }

    /** 발송 전 취소 — 수량을 세는 사이즈는 돌려놓는다. 사이즈가 지워졌으면 그냥 지나간다. 화면에 보이는 수량이 바뀌었으면 true. */
    static boolean restock(ProductRepository products, ShopOrder order) {
        boolean shown = false;
        for (OrderItem item : order.getItems()) {
            ProductSku sku = products.findSkuForUpdate(item.getProductId(), item.getSize()).orElse(null);
            if (sku == null || sku.getStock() == null) {
                continue;
            }
            int before = sku.getStock();
            sku.putBack(item.getQuantity());
            shown |= shownStockChanged(before, sku.getStock());
        }
        return shown;
    }

    /** 손님 화면은 5벌 이하일 때만 수량을 보이고(품절 포함), 그 위는 "있음" 하나다 — 그 범위에 닿는 변화만 다시 만들 이유가 된다. */
    static boolean shownStockChanged(int before, int after) {
        return before != after && Math.min(before, after) <= 5;
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
