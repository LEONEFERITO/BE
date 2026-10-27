package com.leoneferito.order;

import com.leoneferito.media.MediaUrls;
import com.leoneferito.order.CartItem.CartException;
import com.leoneferito.product.Product;
import com.leoneferito.product.ProductRepository;
import com.leoneferito.product.ProductSku;
import com.leoneferito.product.ProductStatus;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 장바구니.
 *
 * <p>가격은 볼 때마다 상품에서 읽는다. 담은 뒤에 상품이 내려가거나, 사이즈가 주문 불가가 되거나,
 * 가격이 바뀔 수 있다 — 그런 줄은 지우지 않고 <b>"주문할 수 없음"</b> 으로 보여 준다.
 * 조용히 지우면 손님은 담았던 걸 찾아 헤맨다.
 */
@Service
public class CartService {

    private final CartItemRepository carts;
    private final ProductRepository products;
    private final MediaUrls mediaUrls;
    private final ShippingPolicy shipping;

    public CartService(CartItemRepository carts, ProductRepository products, MediaUrls mediaUrls,
                       ShippingPolicy shipping) {
        this.carts = carts;
        this.products = products;
        this.mediaUrls = mediaUrls;
        this.shipping = shipping;
    }

    @Transactional(readOnly = true)
    public CartView view(UUID memberId) {
        List<CartItem> items = carts.findByMemberIdOrderByCreatedAtAsc(memberId);
        Map<UUID, Product> byId = products.findAllById(items.stream().map(CartItem::getProductId).toList())
                .stream().collect(Collectors.toMap(Product::getId, Function.identity()));

        List<Line> lines = items.stream().map(i -> toLine(i, byId.get(i.getProductId()))).toList();
        long itemsAmount = lines.stream().filter(Line::available).mapToLong(Line::lineAmountKrw).sum();
        Long fee = itemsAmount == 0 ? null : boxed(shipping.feeFor(itemsAmount));
        return new CartView(lines, itemsAmount, fee, fee == null ? null : itemsAmount + fee,
                shipping.isReady(), shipping.getFreeThresholdKrw());
    }

    /** 담기. 같은 상품·사이즈가 있으면 수량을 더한다. */
    @Transactional
    public void add(UUID memberId, String slug, String size, int quantity) {
        Product product = products.findPublishedBySlug(slug)
                .orElseThrow(() -> new CartException("판매 중인 상품이 아닙니다."));
        if (product.getPriceKrw() == null) {
            throw new CartException("아직 가격이 정해지지 않은 상품입니다.");
        }

        var existing = carts.findByMemberIdAndProductIdAndSize(memberId, product.getId(), size);
        // 이미 담은 수량까지 더해서 본다 — 재고가 2벌인데 1벌씩 세 번 담는 걸 막는다
        orderableSku(product, size, existing.map(e -> (int) e.getQuantity()).orElse(0) + quantity);
        existing.ifPresentOrElse(
                e -> e.setQuantity(e.getQuantity() + quantity),
                () -> carts.save(new CartItem(memberId, product.getId(), size, quantity)));
    }

    @Transactional
    public void changeQuantity(UUID memberId, UUID itemId, int quantity) {
        CartItem item = carts.findByIdAndMemberId(itemId, memberId)
                .orElseThrow(() -> new CartException("장바구니에 없는 상품입니다."));
        // 수량을 세는 사이즈면 남은 수량까지만 (V30). 상품이 내려갔거나 사이즈가 없으면 여기서 막지 않는다 — 줄이 "주문할 수 없음" 으로 보인다
        products.findById(item.getProductId())
                .flatMap(p -> p.getSkus().stream().filter(s -> s.getSize().equals(item.getSize())).findFirst())
                .filter(s -> s.isOrderable() && !s.canSupply(quantity))
                .ifPresent(s -> {
                    throw new CartException(stockMessage(s));
                });
        item.setQuantity(quantity);
    }

    @Transactional
    public void remove(UUID memberId, UUID itemId) {
        carts.findByIdAndMemberId(itemId, memberId).ifPresent(carts::delete);
    }

    /** 이 사이즈를 이만큼 주문할 수 있는가 — 제작할 수 있고, 수량을 센다면(V30) 그만큼 남았다. 아니면 이유와 함께 거절. */
    static ProductSku orderableSku(Product product, String size, int quantity) {
        ProductSku sku = product.getSkus().stream()
                .filter(s -> s.getSize().equals(size))
                .filter(ProductSku::isOrderable)
                .findFirst()
                .orElseThrow(() -> new CartException(size + " 사이즈는 지금 주문할 수 없습니다."));
        if (!sku.canSupply(quantity)) {
            throw new CartException(stockMessage(sku));
        }
        return sku;
    }

    /** "95 사이즈는 품절입니다." · "95 사이즈는 2벌 남았습니다." */
    static String stockMessage(ProductSku sku) {
        return sku.getStock() == null || sku.getStock() == 0
                ? sku.getSize() + " 사이즈는 품절입니다."
                : sku.getSize() + " 사이즈는 " + sku.getStock() + "벌 남았습니다. 수량을 줄여 주세요.";
    }

    private Line toLine(CartItem item, Product p) {
        boolean published = p != null && p.getStatus() == ProductStatus.PUBLISHED;
        ProductSku sku = published ? p.getSkus().stream()
                .filter(s -> s.getSize().equals(item.getSize()) && s.isOrderable())
                .findFirst().orElse(null) : null;
        boolean sizeOk = sku != null;
        boolean stockOk = sizeOk && sku.canSupply(item.getQuantity());
        Long price = published ? p.getPriceKrw() : null;
        boolean available = stockOk && price != null;
        String reason = !published ? "판매가 끝난 상품입니다."
                : !sizeOk ? "이 사이즈는 지금 주문할 수 없습니다."
                : !stockOk ? stockMessage(sku)
                : price == null ? "가격이 정해지지 않은 상품입니다." : null;
        return new Line(
                item.getId(),
                p == null ? null : p.getSlug(),
                p == null ? "판매 종료 상품" : p.getName(),
                p == null ? null : p.mainImage().map(i -> mediaUrls.urlFor(i.getMedia())).orElse(null),
                item.getSize(),
                item.getQuantity(),
                price,
                available ? price * item.getQuantity() : 0,
                p == null ? null : p.getLeadTimeDays(),
                p == null ? null : p.getLeadTimeMinDays(),
                available,
                reason);
    }

    private static Long boxed(java.util.OptionalLong v) {
        return v.isPresent() ? v.getAsLong() : null;
    }

    /**
     * 장바구니 화면.
     * {@code shippingFeeKrw} 가 null 이면 배송비 정책이 아직 없다(주문 불가) — 화면이 "확인 중" 을 띄운다.
     */
    public record CartView(List<Line> items, long itemsAmountKrw, Long shippingFeeKrw, Long totalAmountKrw,
                           boolean shippingPolicyReady, Long freeShippingThresholdKrw) {
    }

    public record Line(UUID id, String slug, String name, String imageUrl, String size, int quantity,
                       Long unitPriceKrw, long lineAmountKrw, Short leadTimeDays,
                       /** 제작 기간 최소(V40) — 있으면 화면이 "최소~leadTimeDays일" */
                       Short leadTimeMinDays,
                       boolean available, String unavailableReason) {
    }
}
