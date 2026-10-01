package com.leoneferito.member;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * 관리자가 회원에게 한 일 한 줄. 고치지도 지우지도 않는다 (setter 없음).
 * actorId 가 null 이면 서버 명령(create-admin)으로 한 것이다.
 */
@Entity
@Table(name = "member_admin_log")
public class MemberAdminLog {

    public enum Action {
        VIEWED,
        ROLE_CHANGED,
        SUSPENDED,
        REACTIVATED,
        UNLOCKED,
        CREATED_BY_COMMAND
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "member_id", nullable = false, updatable = false)
    private UUID memberId;

    @Column(name = "actor_id", updatable = false)
    private UUID actorId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private Action action;

    @Column(updatable = false)
    private String detail;

    /**
     * 애플리케이션이 찍는다(DB 기본값에 맡기지 않는다). DB 가 채우게 두면 방금 저장한 기록을
     * 같은 트랜잭션에서 다시 읽을 때 시각이 비어 있다 — 상세 화면의 "상세 열람" 줄이 그랬다.
     */
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected MemberAdminLog() {
        // JPA
    }

    public MemberAdminLog(UUID memberId, UUID actorId, Action action, String detail) {
        this.memberId = memberId;
        this.actorId = actorId;
        this.action = action;
        this.detail = detail;
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public UUID getMemberId() {
        return memberId;
    }

    public UUID getActorId() {
        return actorId;
    }

    public Action getAction() {
        return action;
    }

    public String getDetail() {
        return detail;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}