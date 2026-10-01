package com.leoneferito.order;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * 주문 상태가 바뀐 한 줄. 손님 화면의 진행 단계와 관리자 기록이 같은 데이터다.
 * actorId 가 null 이면 손님 본인이거나 시스템(결제 승인)이다.
 */
@Entity
@Table(name = "order_event")
public class OrderEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", updatable = false)
    private ShopOrder order;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", updatable = false)
    private OrderStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false, updatable = false)
    private OrderStatus toStatus;

    @Column(name = "actor_id", updatable = false)
    private UUID actorId;

    @Column(updatable = false)
    private String note;

    /** 애플리케이션이 찍는다 — 같은 트랜잭션에서 다시 읽어도 시각이 있어야 한다 (MemberAdminLog 와 같은 이유). */
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected OrderEvent() {
        // JPA
    }

    OrderEvent(ShopOrder order, OrderStatus fromStatus, OrderStatus toStatus, UUID actorId, String note) {
        this.order = order;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.actorId = actorId;
        this.note = note;
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public OrderStatus getFromStatus() {
        return fromStatus;
    }

    public OrderStatus getToStatus() {
        return toStatus;
    }

    public UUID getActorId() {
        return actorId;
    }

    public String getNote() {
        return note;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
