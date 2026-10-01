package com.leoneferito.order;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 교환·반품 신청.
 *
 * <p>{@link ShopOrder} 와 같은 규칙: <b>상태는 이 클래스의 메서드로만 바뀐다.</b> 메서드마다 올 수 있는 상태를
 * 검사하고 이력({@link ReturnEvent})을 남긴다.
 *
 * <p>반품 환불은 부르는 쪽이 토스 부분 취소를 먼저 성공시킨 뒤 {@link #completeReturn} 을 부른다 —
 * 순서가 반대면 "환불 완료" 인데 돈은 안 돌아간 신청이 생긴다.
 */
@Entity
@Table(name = "return_request")
public class ReturnRequest {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", updatable = false)
    private ShopOrder order;

    @Column(name = "member_id", nullable = false, updatable = false)
    private UUID memberId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private ReturnType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private ReturnReason reason;

    @Column(updatable = false)
    private String detail;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ReturnStatus status;

    @Column(name = "admin_note")
    private String adminNote;

    @Column(name = "reject_reason")
    private String rejectReason;

    @Column(name = "refund_amount_krw", nullable = false)
    private long refundAmountKrw;

    @Column(name = "reship_courier")
    private String reshipCourier;

    @Column(name = "reship_tracking_number")
    private String reshipTrackingNumber;

    /** 애플리케이션이 찍는다 — 만든 트랜잭션 안에서 응답을 만들 때도 시각이 있어야 한다. */
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @OneToMany(mappedBy = "request", cascade = CascadeType.ALL)
    @OrderBy("id ASC")
    private List<ReturnItem> items = new ArrayList<>();

    @OneToMany(mappedBy = "request", cascade = CascadeType.ALL)
    @OrderBy("id ASC")
    private List<ReturnEvent> events = new ArrayList<>();

    protected ReturnRequest() {
        // JPA
    }

    static ReturnRequest open(ShopOrder order, ReturnType type, ReturnReason reason, String detail,
                              List<ReturnItem> items) {
        if (items.isEmpty()) {
            throw new IllegalArgumentException("빈 신청");
        }
        ReturnRequest r = new ReturnRequest();
        r.id = UUID.randomUUID();
        r.order = Objects.requireNonNull(order);
        r.memberId = order.getMemberId();
        r.type = Objects.requireNonNull(type);
        r.reason = Objects.requireNonNull(reason);
        r.detail = detail;
        r.status = ReturnStatus.REQUESTED;
        r.createdAt = Instant.now();
        for (ReturnItem item : items) {
            item.attachTo(r);
            r.items.add(item);
        }
        r.events.add(new ReturnEvent(r, null, ReturnStatus.REQUESTED, null,
                (type == ReturnType.EXCHANGE ? "교환" : "반품") + " 신청"));
        return r;
    }

    /** 손님 철회. 승인 전에만 — 승인 뒤에는 회수가 이미 잡혀 있을 수 있다. */
    void withdraw() {
        require(ReturnStatus.REQUESTED, "승인된 신청은 철회할 수 없습니다. 고객센터로 문의해 주세요.");
        transition(ReturnStatus.WITHDRAWN, null, "손님 철회");
    }

    /** 승인. note 는 손님에게 보이는 안내다 (회수 방법 · 일정). */
    void approve(UUID actorId, String note) {
        require(ReturnStatus.REQUESTED, "신청 상태에서만 승인할 수 있습니다.");
        this.adminNote = note;
        transition(ReturnStatus.APPROVED, actorId, note == null ? "승인" : note);
    }

    /** 상품을 돌려받았다. */
    void markCollected(UUID actorId, String note) {
        require(ReturnStatus.APPROVED, "승인된 신청만 회수 완료로 바꿀 수 있습니다.");
        transition(ReturnStatus.COLLECTED, actorId, note == null ? "회수 완료" : note);
    }

    /** 반품 완료 + 환불 기록. 토스 부분 취소는 부르는 쪽이 먼저 성공시킨다. */
    void completeReturn(UUID actorId, long refundAmount) {
        requireReturnCompletable();
        if (refundAmount > 0) {
            order.recordRefund(refundAmount);
        }
        this.refundAmountKrw = refundAmount;
        transition(ReturnStatus.COMPLETED, actorId, "반품 완료 · 환불 " + String.format("%,d", refundAmount) + "원");
    }

    /** 반품 완료가 가능한가 — 토스 환불을 부르기 전에 확인한다. */
    void requireReturnCompletable() {
        if (type != ReturnType.RETURN) {
            throw new ShopOrder.OrderStateException("교환 신청은 재발송으로 완료합니다.");
        }
        require(ReturnStatus.COLLECTED, "회수가 끝난 뒤에 완료할 수 있습니다.");
    }

    /** 교환 완료 — 다른 사이즈를 다시 보냈다. 송장이 손님 화면에 보인다. */
    void completeExchange(UUID actorId, String courier, String trackingNumber) {
        if (type != ReturnType.EXCHANGE) {
            throw new ShopOrder.OrderStateException("반품 신청은 환불로 완료합니다.");
        }
        require(ReturnStatus.COLLECTED, "회수가 끝난 뒤에 완료할 수 있습니다.");
        this.reshipCourier = Objects.requireNonNull(courier);
        this.reshipTrackingNumber = Objects.requireNonNull(trackingNumber);
        transition(ReturnStatus.COMPLETED, actorId, "교환 재발송 " + courier + " " + trackingNumber);
    }

    /** 거절 — 신청 단계, 또는 회수 후 검수에서. 사유는 손님에게 보인다. */
    void reject(UUID actorId, String reason) {
        if (!EnumSet.of(ReturnStatus.REQUESTED, ReturnStatus.APPROVED, ReturnStatus.COLLECTED).contains(status)) {
            throw new ShopOrder.OrderStateException("이미 끝난 신청입니다.");
        }
        this.rejectReason = Objects.requireNonNull(reason);
        transition(ReturnStatus.REJECTED, actorId, reason);
    }

    private void require(ReturnStatus expected, String message) {
        if (status != expected) {
            throw new ShopOrder.OrderStateException(message);
        }
    }

    private void transition(ReturnStatus to, UUID actorId, String note) {
        ReturnStatus from = this.status;
        this.status = to;
        events.add(new ReturnEvent(this, from, to, actorId, note == null ? null
                : note.length() > 300 ? note.substring(0, 300) : note));
    }

    public boolean isOwnedBy(UUID memberId) {
        return this.memberId.equals(memberId);
    }

    public UUID getId() {
        return id;
    }

    public ShopOrder getOrder() {
        return order;
    }

    public UUID getMemberId() {
        return memberId;
    }

    public ReturnType getType() {
        return type;
    }

    public ReturnReason getReason() {
        return reason;
    }

    public String getDetail() {
        return detail;
    }

    public ReturnStatus getStatus() {
        return status;
    }

    public String getAdminNote() {
        return adminNote;
    }

    public String getRejectReason() {
        return rejectReason;
    }

    public long getRefundAmountKrw() {
        return refundAmountKrw;
    }

    public String getReshipCourier() {
        return reshipCourier;
    }

    public String getReshipTrackingNumber() {
        return reshipTrackingNumber;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public List<ReturnItem> getItems() {
        return items;
    }

    public List<ReturnEvent> getEvents() {
        return events;
    }
}
