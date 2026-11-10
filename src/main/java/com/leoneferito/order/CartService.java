package com.leoneferito.order;

import com.leoneferito.media.MediaUrls;
import com.leoneferito.order.CartItem.CartException;
import com.leoneferito.product.Product;
import com.leoneferito.product.ProductRepository;
import com.leoneferito.product.ProductSku;
import com.leoneferito.product.ProductStatus;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
        Map<UUID, Product> byId = productsOf(products, items);
        Set<UUID> present = items.stream().map(CartItem::getProductId).collect(Collectors.toSet());

        List<Line> lines = items.stream().map(i -> toLine(i, byId.get(i.getProductId()), byId, present)).toList();
        long itemsAmount = lines.stream().filter(Line::available).mapToLong(Line::lineAmountKrw).sum();
        Long fee = itemsAmount == 0 ? null : boxed(shipping.feeFor(itemsAmount));
        return new CartView(lines, itemsAmount, fee, fee == null ? null : itemsAmount + fee,
                shipping.isReady(), shipping.getFreeThresholdKrw());
    }

    /** 줄의 상품과, 함께 담은 상품(V52)까지 한 번에 읽는다 */
    static Map<UUID, Product> productsOf(ProductRepository products, List<CartItem> items) {
        Set<UUID> ids = new java.util.HashSet<>();
        items.forEach(i -> {
            ids.add(i.getProductId());
            if (i.getAddonOf() != null) ids.add(i.getAddonOf());
        });
        return products.findAllById(ids).stream().collect(Collectors.toMap(Product::getId, Function.identity()));
    }

    /**
     * 함께 구매 금액(V52) — 이 줄이 함께 담은 상품(수트)이 같은 장바구니 · 주문(present)에 있고, 그 수트가 이 상품을 붙여 금액을 정했으면 그 금액.
     * 아니면 null — 원래 판매가로 계산한다(주문할 수 없게 막지는 않는다).
     */
    static Long bundleBase(CartItem item, Map<UUID, Product> byId, Set<UUID> present) {
        if (item.getAddonOf() == null || !present.contains(item.getAddonOf())) {
            return null;
        }
        Product parent = byId.get(item.getAddonOf());
        if (parent == null || parent.getStatus() != ProductStatus.PUBLISHED) {
            return null;
        }
        return parent.addonFor(item.getProductId()).map(Product.Addon::priceKrw).orElse(null);
    }

    /**
     * 담기. 같은 상품·사이즈·컬러(·함께 담은 상품)가 있으면 수량을 더한다. 컬러가 있는 상품은 목록 안의 컬러를 골라야 한다(V46).
     * addonOfSlug — 수트 상세의 «함께 구매» 로 담을 때 그 수트(V52). 그 수트가 이 상품을 붙이지 않았으면 거절한다.
     */
    @Transactional
    public void add(UUID memberId, String slug, String size, String color, int quantity, String addonOfSlug) {
        String c = color == null ? "" : color.trim();
        Product product = products.findPublishedBySlug(slug)
                .orElseThrow(() -> new CartException("판매 중인 상품이 아닙니다."));
        UUID addonOf = null;
        if (addonOfSlug != null && !addonOfSlug.isBlank()) {
            Product parent = products.findPublishedBySlug(addonOfSlug.trim())
                    .orElseThrow(() -> new CartException("함께 구매할 상품이 판매 중이 아닙니다."));
            if (parent.addonFor(product.getId()).isEmpty()) {
                throw new CartException("이 상품은 함께 구매할 수 없습니다. 상품 화면에서 다시 골라 주세요.");
            }
            addonOf = parent.getId();
        }
        if (product.getPriceKrw() == null) {
            throw new CartException("아직 가격이 정해지지 않은 상품입니다.");
        }
        // 수트 구성(V43) — 구성 줄을 못 읽거나 팔지 않는 구성이면 값이 없다
        if (product.priceFor(size) == null) {
            throw new CartException("구성과 사이즈를 다시 골라 주세요.");
        }
        if (!product.acceptsColor(c)) {
            throw new CartException(c.isEmpty() ? "컬러를 골라 주세요." : c + " 컬러는 지금 주문할 수 없습니다. 다시 골라 주세요.");
        }

        UUID parentId = addonOf;
        var existing = carts.findByMemberIdAndProductIdAndSizeAndColorAndAddonOf(memberId, product.getId(), size, c, parentId);
        // 이미 담은 수량까지 더해서 본다 — 재고가 2벌인데 1벌씩 세 번 담는 걸 막는다
        orderableSkus(product, size, existing.map(e -> (int) e.getQuantity()).orElse(0) + quantity);
        existing.ifPresentOrElse(
                e -> e.setQuantity(e.getQuantity() + quantity),
                () -> carts.save(new CartItem(memberId, product.getId(), size, c, quantity, parentId)));
    }

    @Transactional
    public void changeQuantity(UUID memberId, UUID itemId, int quantity) {
        CartItem item = carts.findByIdAndMemberId(itemId, memberId)
                .orElseThrow(() -> new CartException("장바구니에 없는 상품입니다."));
        // 수량을 세는 사이즈면 남은 수량까지만 (V30). 상품이 내려갔거나 사이즈가 없으면 여기서 막지 않는다 — 줄이 "주문할 수 없음" 으로 보인다
        products.findById(item.getProductId())
                .flatMap(p -> p.skusFor(item.getSize()).stream()
                        .filter(s -> s.isOrderable() && !s.canSupply(quantity)).findFirst())
                .ifPresent(s -> {
                    throw new CartException(stockMessage(s));
                });
        item.setQuantity(quantity);
    }

    @Transactional
    public void remove(UUID memberId, UUID itemId) {
        carts.findByIdAndMemberId(itemId, memberId).ifPresent(carts::delete);
    }

    /**
     * 이 사이즈를 이만큼 주문할 수 있는가 — 제작할 수 있고, 수량을 센다면(V30) 그만큼 남았다. 아니면 이유와 함께 거절.
     * 수트 구성(V43)은 조각마다 본다 — 자켓 L 이 품절이면 "자켓 L 사이즈는 품절입니다."
     */
    static List<ProductSku> orderableSkus(Product product, String size, int quantity) {
        List<ProductSku> skus = product.skusFor(size);
        if (skus.isEmpty()) {
            throw new CartException(product.isSuitSet()
                    ? "고른 구성 · 사이즈는 지금 주문할 수 없습니다. 다시 골라 주세요."
                    : size + " 사이즈는 지금 주문할 수 없습니다.");
        }
        for (ProductSku sku : skus) {
            if (!sku.isOrderable()) {
                throw new CartException(sku.displayName() + " 사이즈는 지금 주문할 수 없습니다.");
            }
            if (!sku.canSupply(quantity)) {
                throw new CartException(stockMessage(sku));
            }
        }
        return skus;
    }

    /** "95 사이즈는 품절입니다." · "95 사이즈는 2벌 남았습니다." · 수트 조각은 "자켓 L 사이즈는 …" · 신발은 "2켤레"(2026-11-10) */
    static String stockMessage(ProductSku sku) {
        return sku.getStock() == null || sku.getStock() == 0
                ? sku.displayName() + " 사이즈는 품절입니다."
                : sku.displayName() + " 사이즈는 " + sku.getStock() + (sku.getProduct().getCategory() == com.leoneferito.product.ProductCategory.SHOES ? "켤레" : "벌")
                        + " 남았습니다. 수량을 줄여 주세요.";
    }

    private Line toLine(CartItem item, Product p, Map<UUID, Product> byId, Set<UUID> present) {
        boolean published = p != null && p.getStatus() == ProductStatus.PUBLISHED;
        // 보통 상품은 한 칸, 수트 구성(V43)은 조각마다 한 칸 — 모두 제작할 수 있어야 한다
        List<ProductSku> skus = published ? p.skusFor(item.getSize()) : List.of();
        boolean sizeOk = !skus.isEmpty() && skus.stream().allMatch(ProductSku::isOrderable);
        ProductSku short_ = sizeOk ? skus.stream().filter(s -> !s.canSupply(item.getQuantity())).findFirst().orElse(null) : null;
        boolean stockOk = sizeOk && short_ == null;
        // 함께 구매 금액(V52) — 함께 담은 수트가 장바구니에 있을 때만. 없으면 원래 판매가
        Long bundle = published ? bundleBase(item, byId, present) : null;
        Long price = published ? p.priceFor(item.getSize(), bundle) : null;
        Product parent = item.getAddonOf() == null ? null : byId.get(item.getAddonOf());
        // 담은 뒤에 관리자가 컬러를 지웠으면(V46) 그 줄은 주문할 수 없다 — 지우지 않고 이유를 보여 준다
        boolean colorOk = published && p.acceptsColor(item.getColor());
        boolean available = stockOk && price != null && colorOk;
        String reason = !published ? "판매가 끝난 상품입니다."
                : !colorOk ? "이 컬러는 지금 주문할 수 없습니다."
                : !sizeOk ? "이 사이즈는 지금 주문할 수 없습니다."
                : !stockOk ? stockMessage(short_)
                : price == null ? "가격이 정해지지 않은 상품입니다." : null;
        return new Line(
                item.getId(),
                p == null ? null : p.getSlug(),
                p == null ? "판매 종료 상품" : p.getName(),
                p == null ? null : p.mainImage().map(i -> mediaUrls.urlFor(i.getMedia())).orElse(null),
                item.getSize(),
                item.getColor(),
                item.getQuantity(),
                price,
                available ? price * item.getQuantity() : 0,
                p == null ? null : p.getLeadTimeDays(),
                p == null ? null : p.getLeadTimeMinDays(),
                available,
                reason,
                parent == null ? null : parent.getName(),
                bundle != null);
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

    /**
     * color — 고른 컬러 이름(V46). 컬러가 없는 상품은 빈 값.
     * addonOf — «함께 구매» 로 담은 줄이면 함께 담은 상품 이름(V52), bundleApplied — 함께 구매 금액이 지금 적용됐는가
     * (그 상품이 장바구니에서 빠지면 false — 원래 판매가로 계산된다).
     */
    public record Line(UUID id, String slug, String name, String imageUrl, String size, String color, int quantity,
                       Long unitPriceKrw, long lineAmountKrw, Short leadTimeDays,
                       /** 제작 기간 최소(V40) — 있으면 화면이 "최소~leadTimeDays일" */
                       Short leadTimeMinDays,
                       boolean available, String unavailableReason,
                       String addonOf, boolean bundleApplied) {
    }
}
