package com.leoneferito.home;

import com.leoneferito.media.MediaAsset;
import com.leoneferito.product.Product;
import com.leoneferito.product.ProductStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * 메인 배너 한 개 (V25). 켜 둔 배너가 순서(sortOrder)대로 메인 첫 화면에서 넘어간다.
 *
 * <p>상품 배너는 상품의 사진을 쓰고, 직접 올리기 배너는 이 행이 가진 사진(인물 · 액자 둘)을 쓴다.
 * 손님 화면에 실제로 나가는지는 {@link #isShown()} — 꺼 두었거나, 상품이 비공개거나, 인물 사진이 없으면 안 나간다.
 */
@Entity
@Table(name = "hero_banner")
public class HeroBanner {

    /** 배너를 채우는 방법. */
    public enum Kind {
        /** 공개 상품 하나 — 상품의 누끼 · 착용 · 디테일 사진. */
        PRODUCT,
        /** 직접 올린 사진과 문구 · 버튼. */
        CUSTOM
    }

    /** 배너 문구. 비운 줄은 그리지 않는다 — 상품 배너는 비우면 상품 이름 · 가격이 나간다. */
    public record Text(String eyebrow, String title, String subtitle, String ctaLabel, String ctaUrl) {
    }

    /** 사진 한 장과 설명. 사진이 없으면 media 가 null. */
    public record Photo(MediaAsset media, String alt) {
    }

    @Id
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Kind kind;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id")
    private Product product;

    @Column(nullable = false)
    private String eyebrow;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false)
    private String subtitle;

    @Column(name = "cta_label", nullable = false)
    private String ctaLabel;

    @Column(name = "cta_url", nullable = false)
    private String ctaUrl;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "figure_media_id")
    private MediaAsset figure;

    @Column(name = "figure_alt", nullable = false)
    private String figureAlt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "frame_left_media_id")
    private MediaAsset frameLeft;

    @Column(name = "frame_left_alt", nullable = false)
    private String frameLeftAlt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "frame_right_media_id")
    private MediaAsset frameRight;

    @Column(name = "frame_right_alt", nullable = false)
    private String frameRightAlt;

    @Column(nullable = false)
    private boolean visible;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private Instant updatedAt;

    protected HeroBanner() {
        // JPA
    }

    /** 새 배너. 내용은 바로 {@link #change} 로 채운다. */
    public HeroBanner(int sortOrder) {
        this.id = UUID.randomUUID();
        this.createdAt = Instant.now();
        this.sortOrder = sortOrder;
    }

    /**
     * 내용을 통째로 바꾼다. 상품은 상품 배너일 때만, 사진은 직접 올리기일 때만 남긴다 —
     * 종류를 바꾸면 다른 쪽 값이 남아 엉뚱하게 쓰이지 않게. 문구는 앞뒤 공백을 걷는다.
     */
    public void change(Kind kind, Product product, Text text, Photo figure, Photo frameLeft, Photo frameRight,
                       boolean visible) {
        boolean custom = kind == Kind.CUSTOM;
        this.kind = kind;
        this.product = custom ? null : product;
        this.eyebrow = clean(text.eyebrow());
        this.title = clean(text.title());
        this.subtitle = clean(text.subtitle());
        this.ctaLabel = clean(text.ctaLabel());
        this.ctaUrl = clean(text.ctaUrl());
        this.figure = custom ? figure.media() : null;
        this.figureAlt = custom ? clean(figure.alt()) : "";
        this.frameLeft = custom ? frameLeft.media() : null;
        this.frameLeftAlt = custom ? clean(frameLeft.alt()) : "";
        this.frameRight = custom ? frameRight.media() : null;
        this.frameRightAlt = custom ? clean(frameRight.alt()) : "";
        this.visible = visible;
    }

    void moveTo(int sortOrder) {
        this.sortOrder = sortOrder;
    }

    /** 손님 화면에 실제로 나가는가 — 켜 두었고, 상품이 공개 중이거나 인물 사진이 있다. */
    public boolean isShown() {
        if (!visible) {
            return false;
        }
        return kind == Kind.PRODUCT
                ? product != null && product.getStatus() == ProductStatus.PUBLISHED
                : figure != null;
    }

    private static String clean(String s) {
        return s == null ? "" : s.trim();
    }

    public UUID getId() {
        return id;
    }

    public Kind getKind() {
        return kind;
    }

    public Product getProduct() {
        return product;
    }

    public String getEyebrow() {
        return eyebrow;
    }

    public String getTitle() {
        return title;
    }

    public String getSubtitle() {
        return subtitle;
    }

    public String getCtaLabel() {
        return ctaLabel;
    }

    public String getCtaUrl() {
        return ctaUrl;
    }

    public MediaAsset getFigure() {
        return figure;
    }

    public String getFigureAlt() {
        return figureAlt;
    }

    public MediaAsset getFrameLeft() {
        return frameLeft;
    }

    public String getFrameLeftAlt() {
        return frameLeftAlt;
    }

    public MediaAsset getFrameRight() {
        return frameRight;
    }

    public String getFrameRightAlt() {
        return frameRightAlt;
    }

    public boolean isVisible() {
        return visible;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
