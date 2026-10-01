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
        orderableSku(product, size);
        if (product.getPriceKrw() == null) {
            throw new CartException("아직 가격이 정해지지 않은 상품입니다.");
        }

        carts.findByMemberIdAndProductIdAndSize(memberId, product.getId(), size)
                .ifPresentOrElse(
                        existing -> existing.setQuantity(existing.getQuantity() + quantity),
                        () -> carts.save(new CartItem(memberId, product.getId(), size, quantity)));
    }

    @Transactional
    public void changeQuantity(UUID memberId, UUID itemId, int quantity) {
        CartItem item = carts.findByIdAndMemberId(itemId, memberId)
                .orElseThrow(() -> new CartException("장바구니에 없는 상품입니다."));
        item.setQuantity(quantity);
    }

    @Transactional
    public void remove(UUID memberId, UUID itemId) {
        carts.findByIdAndMemberId(itemId, memberId).ifPresent(carts::delete);
    }

    static ProductSku orderableSku(Product product, String size) {
        return product.getSkus().stream()
                .filter(s -> s.getSize().equals(size))
                .filter(ProductSku::isOrderable)
                .findFirst()
                .orElseThrow(() -> new CartException(size + " 사이즈는 지금 주문할 수 없습니다."));
    }

    private Line toLine(CartItem item, Product p) {
        boolean published = p != null && p.getStatus() == ProductStatus.PUBLISHED;
        boolean sizeOk = published && p.getSkus().stream()
                .anyMatch(s -> s.getSize().equals(item.getSize()) && s.isOrderable());
        Long price = published ? p.getPriceKrw() : null;
        boolean available = sizeOk && price != null;
        String reason = !published ? "판매가 끝난 상품입니다."
                : !sizeOk ? "이 사이즈는 지금 주문할 수 없습니다."
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
                       boolean available, String unavailableReason) {
    }
}
