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

/** 교환·반품 상태가 바뀐 한 줄 ({@link OrderEvent} 와 같은 구조). actorId 가 null 이면 손님 본인이다. */
@Entity
@Table(name = "return_event")
public class ReturnEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "return_id", updatable = false)
    private ReturnRequest request;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", updatable = false)
    private ReturnStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false, updatable = false)
    private ReturnStatus toStatus;

    @Column(name = "actor_id", updatable = false)
    private UUID actorId;

    @Column(updatable = false)
    private String note;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ReturnEvent() {
        // JPA
    }

    ReturnEvent(ReturnRequest request, ReturnStatus fromStatus, ReturnStatus toStatus, UUID actorId, String note) {
        this.request = request;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.actorId = actorId;
        this.note = note;
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public ReturnStatus getFromStatus() {
        return fromStatus;
    }

    public ReturnStatus getToStatus() {
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
