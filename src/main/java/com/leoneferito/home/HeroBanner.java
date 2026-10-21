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
 * 영상 배너(V29)도 같은 자리를 쓴다 — figure · mobile 이 영상이고, 영상이 오기 전 · 움직임을 줄인 사용자에게 보일
 * 첫 장면 사진(poster · mobilePoster)을 둔다.
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
        IMAGE,
        /** 영상 하나가 히어로 화면 전체를 덮고 소리 없이 반복 재생된다. 버튼은 원하는 주소로 (V29). */
        VIDEO
    }

    /**
     * 배너 문구. 비운 줄은 그리지 않는다 — 상품 배너는 비우면 상품 이름 · 가격이 나간다.
     * {@code ctaEnabled} 가 false 면 버튼이 안 나간다(V35) — 글자 · 주소는 보관만 한다.
     * {@code eyebrowPx} · {@code titlePx} · {@code subtitlePx} 는 PC 글자 크기(V37) — null 이면 기본 크기.
     */
    public record Text(String eyebrow, String title, String subtitle, String ctaLabel, String ctaUrl,
                       boolean ctaEnabled, Integer eyebrowPx, Integer titlePx, Integer subtitlePx) {
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

    /** 버튼을 내보내는가 (V35). 끄면 글자 · 주소는 남고 화면에서만 빠진다. */
    @Column(name = "cta_enabled", nullable = false)
    private boolean ctaEnabled = true;

    /** PC 글자 크기 px (V37). null 이면 디자이너 기준 크기. 휴대폰은 늘 자동 크기. */
    @Column(name = "eyebrow_px")
    private Integer eyebrowPx;

    @Column(name = "title_px")
    private Integer titlePx;

    @Column(name = "subtitle_px")
    private Integer subtitlePx;

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

    /** 영상 배너의 첫 장면 사진 — 영상이 오기 전, 움직임을 줄인 사용자에게 (V29). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "poster_media_id")
    private MediaAsset poster;

    /** 휴대폰용 영상의 첫 장면 사진 (V29). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "mobile_poster_media_id")
    private MediaAsset mobilePoster;

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
     * 휴대폰 사진 · 영상은 이미지 한 장 · 영상에만, 첫 장면 사진은 영상에만. 종류를 바꾸면 다른 쪽 값이 남아
     * 엉뚱하게 쓰이지 않게. 문구는 앞뒤 공백을 걷는다.
     */
    public void change(Kind kind, Product product, Text text, Photo figure, Photo frameLeft, Photo frameRight,
                       Photo mobile, MediaAsset poster, MediaAsset mobilePoster, boolean visible) {
        boolean custom = kind == Kind.CUSTOM;
        boolean video = kind == Kind.VIDEO;
        // 화면 전체를 덮는 배너 — 이미지 한 장 · 영상
        boolean image = kind == Kind.IMAGE || video;
        this.kind = kind;
        this.product = kind == Kind.PRODUCT ? product : null;
        this.eyebrow = clean(text.eyebrow());
        this.title = clean(text.title());
        this.subtitle = clean(text.subtitle());
        this.ctaLabel = clean(text.ctaLabel());
        this.ctaUrl = clean(text.ctaUrl());
        this.ctaEnabled = text.ctaEnabled();
        this.eyebrowPx = text.eyebrowPx();
        this.titlePx = text.titlePx();
        this.subtitlePx = text.subtitlePx();
        this.figure = custom || image ? figure.media() : null;
        this.figureAlt = custom || image ? clean(figure.alt()) : "";
        this.frameLeft = custom ? frameLeft.media() : null;
        this.frameLeftAlt = custom ? clean(frameLeft.alt()) : "";
        this.frameRight = custom ? frameRight.media() : null;
        this.frameRightAlt = custom ? clean(frameRight.alt()) : "";
        this.mobile = image ? mobile.media() : null;
        this.mobileAlt = image ? clean(mobile.alt()) : "";
        this.poster = video ? poster : null;
        this.mobilePoster = video ? mobilePoster : null;
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

    public boolean isCtaEnabled() {
        return ctaEnabled;
    }

    public Integer getEyebrowPx() {
        return eyebrowPx;
    }

    public Integer getTitlePx() {
        return titlePx;
    }

    public Integer getSubtitlePx() {
        return subtitlePx;
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

    public MediaAsset getPoster() {
        return poster;
    }

    public MediaAsset getMobilePoster() {
        return mobilePoster;
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
