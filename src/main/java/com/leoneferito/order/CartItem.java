package com.leoneferito.order;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * 장바구니 한 줄. 가격을 담지 않는다 — 담아 둔 사이에 가격이 바뀔 수 있어서 볼 때마다 상품에서 읽는다.
 * 같은 상품·사이즈는 한 줄이고(DB UNIQUE), 다시 담으면 수량이 는다.
 */
@Entity
@Table(name = "cart_item")
public class CartItem {

    /** 한 줄 최대 수량. 주문 제작이라 대량 주문은 상담으로 받는다. DB CHECK 와 같은 값. */
    public static final int MAX_QUANTITY = 10;

    @Id
    private UUID id;

    @Column(name = "member_id", nullable = false, updatable = false)
    private UUID memberId;

    @Column(name = "product_id", nullable = false, updatable = false)
    private UUID productId;

    @Column(nullable = false, updatable = false)
    private String size;

    @Column(nullable = false)
    private short quantity;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    protected CartItem() {
        // JPA
    }

    CartItem(UUID memberId, UUID productId, String size, int quantity) {
        this.id = UUID.randomUUID();
        this.memberId = memberId;
        this.productId = productId;
        this.size = size;
        setQuantity(quantity);
    }

    /** 수량. 1~10 밖이면 거부한다 — 화면 검사는 우회할 수 있다. */
    void setQuantity(int quantity) {
        if (quantity < 1 || quantity > MAX_QUANTITY) {
            throw new CartException("수량은 1~" + MAX_QUANTITY + "개까지 담을 수 있습니다.");
        }
        this.quantity = (short) quantity;
    }

    public UUID getId() {
        return id;
    }

    public UUID getMemberId() {
        return memberId;
    }

    public UUID getProductId() {
        return productId;
    }

    public String getSize() {
        return size;
    }

    public short getQuantity() {
        return quantity;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    /** 장바구니 규칙 위반 (없는 사이즈, 수량 초과 등). 400 으로 나가고 메시지를 그대로 보여준다. */
    public static class CartException extends RuntimeException {
        public CartException(String userFacingMessage) {
            super(userFacingMessage);
        }
    }
}
