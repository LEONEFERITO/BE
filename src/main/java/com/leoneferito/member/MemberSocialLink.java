package com.leoneferito.member;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "member_social_link")
public class MemberSocialLink {
    @Id
    private UUID id;
    @Column(name = "member_id", nullable = false, updatable = false)
    private UUID memberId;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private MemberProvider provider;
    @Column(name = "provider_user_id", nullable = false, updatable = false)
    private String providerUserId;
    @Column(name = "linked_at", nullable = false, updatable = false)
    private Instant linkedAt;
    protected MemberSocialLink() {}
    public MemberSocialLink(UUID memberId, MemberProvider provider, String providerUserId, Instant linkedAt) {
        if (provider == MemberProvider.LOCAL) {throw new IllegalArgumentException("간편 로그인 제공자가 아니다: " + provider);}
        this.id = UUID.randomUUID();
        this.memberId = Objects.requireNonNull(memberId, "memberId");
        this.provider = provider;
        this.providerUserId = Objects.requireNonNull(providerUserId, "providerUserId");
        this.linkedAt = Objects.requireNonNull(linkedAt, "linkedAt");
    }
    public UUID getId() {return id;}
    public UUID getMemberId() {return memberId;}
    public MemberProvider getProvider() {return provider;}
    public String getProviderUserId() {return providerUserId;}
    public Instant getLinkedAt() {return linkedAt;}
}