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
 * 이미지 한 장 배너(V26)는 큰 사진(figure) 한 장이 히어로 화면 전체를 덮고, 휴대폰용 세로 사진(mobile)을 따로 둘 수 있다.
 * 손님 화면에 실제로 나가는지는 {@link #isShown()} — 꺼 두었거나, 상품이 비공개거나, 주 사진이 없으면 안 나간다.
 */
@Entity
@Table(name = "hero_banner")
public class HeroBanner {

    /** 배너를 채우는 방법. */
    public enum Kind {
        /** 공개 상품 하나 — 상품의 누끼 · 착용 · 디테일 사진. */
        PRODUCT,
        /** 직접 올린 누끼 사진(인물 · 액자 둘)과 문구 · 버튼. */
        CUSTOM,
        /** 이미지 한 장이 히어로 화면 전체를 덮는다. 버튼은 원하는 주소로 (V26). */
        IMAGE
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

    /** 이미지 한 장 배너의 휴대폰용 세로 사진. 비우면 큰 사진을 잘라 쓴다 (V26). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "mobile_media_id")
    private MediaAsset mobile;

    @Column(name = "mobile_alt", nullable = false)
    private String mobileAlt;

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
     * 내용을 통째로 바꾼다. 종류에 맞는 값만 남긴다 — 상품은 상품 배너에만, 액자는 직접 올리기에만,
     * 휴대폰 사진은 이미지 한 장에만. 종류를 바꾸면 다른 쪽 값이 남아 엉뚱하게 쓰이지 않게. 문구는 앞뒤 공백을 걷는다.
     */
    public void change(Kind kind, Product product, Text text, Photo figure, Photo frameLeft, Photo frameRight,
                       Photo mobile, boolean visible) {
        boolean custom = kind == Kind.CUSTOM;
        boolean image = kind == Kind.IMAGE;
        this.kind = kind;
        this.product = kind == Kind.PRODUCT ? product : null;
        this.eyebrow = clean(text.eyebrow());
        this.title = clean(text.title());
        this.subtitle = clean(text.subtitle());
        this.ctaLabel = clean(text.ctaLabel());
        this.ctaUrl = clean(text.ctaUrl());
        this.figure = custom || image ? figure.media() : null;
        this.figureAlt = custom || image ? clean(figure.alt()) : "";
        this.frameLeft = custom ? frameLeft.media() : null;
        this.frameLeftAlt = custom ? clean(frameLeft.alt()) : "";
        this.frameRight = custom ? frameRight.media() : null;
        this.frameRightAlt = custom ? clean(frameRight.alt()) : "";
        this.mobile = image ? mobile.media() : null;
        this.mobileAlt = image ? clean(mobile.alt()) : "";
        this.visible = visible;
    }

    void moveTo(int sortOrder) {
        this.sortOrder = sortOrder;
    }

    /** 손님 화면에 실제로 나가는가 — 켜 두었고, 상품이 공개 중이거나 주 사진(인물 · 큰 사진)이 있다. */
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

    public MediaAsset getMobile() {
        return mobile;
    }

    public String getMobileAlt() {
        return mobileAlt;
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
