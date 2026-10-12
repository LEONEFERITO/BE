package com.leoneferito.home;

import com.leoneferito.product.Product;
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

/**
 * 메인 배너 설정 (V24). 한 행뿐이다(id = 1) — "어느 게 진짜인가" 를 DB 가 막는다.
 *
 * <p>배너를 무엇으로 채울지 관리자가 고른다({@link Mode}). 고른 상품이 비공개가 되거나
 * CUSTOM 인데 인물 사진(사이트 사진 칸 HERO_FIGURE)이 없으면 손님 화면은 AUTO 로 돌아간다.
 */
@Entity
@Table(name = "hero_config")
public class HeroConfig {

    /** 배너를 채우는 방법. */
    public enum Mode {
        /** 진열 순서 맨 앞 공개 상품 — 관리자가 아무것도 안 해도 배너가 있다. */
        AUTO,
        /** 관리자가 고른 공개 상품 하나. */
        PRODUCT,
        /** 관리자가 직접 올린 사진(HERO_FIGURE · HERO_FRAME_LEFT · HERO_FRAME_RIGHT)과 문구 · 버튼. */
        CUSTOM
    }

    @Id
    private Short id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Mode mode;

    /** mode = PRODUCT 일 때만 채운다. 상품이 지워지면 DB 가 비운다(ON DELETE SET NULL). */
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

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private Instant updatedAt;

    protected HeroConfig() {
    }

    /**
     * 설정을 통째로 바꾼다. 상품은 PRODUCT 일 때만 남기고, 문구는 앞뒤 공백을 걷는다.
     * 문구는 모드와 상관없이 저장한다 — CUSTOM 에서 잠깐 다른 모드로 바꿨다 돌아와도 다시 쓰지 않아도 되게.
     */
    public void change(Mode mode, Product product, String eyebrow, String title, String subtitle,
                       String ctaLabel, String ctaUrl) {
        this.mode = mode;
        this.product = mode == Mode.PRODUCT ? product : null;
        this.eyebrow = clean(eyebrow);
        this.title = clean(title);
        this.subtitle = clean(subtitle);
        this.ctaLabel = clean(ctaLabel);
        this.ctaUrl = clean(ctaUrl);
    }

    private static String clean(String s) {
        return s == null ? "" : s.trim();
    }

    public Short getId() {
        return id;
    }

    public Mode getMode() {
        return mode;
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

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
