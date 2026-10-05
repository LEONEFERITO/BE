package com.leoneferito.home;

import com.leoneferito.media.MediaAsset;
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
 * 사이트 사진 한 칸 (V20). 칸은 마이그레이션이 미리 심고, 관리자는 그 칸의 사진과 설명만 바꾼다.
 *
 * <p>사진이 없으면({@code media == null}) 손님 화면은 코드의 기본 사진을 쓴다.
 */
@Entity
@Table(name = "site_image")
public class SiteImage {

    @Id
    @Enumerated(EnumType.STRING)
    @Column(name = "slot", nullable = false, updatable = false)
    private SiteImageSlot slot;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "media_id")
    private MediaAsset media;

    @Column(nullable = false)
    private String alt;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private Instant updatedAt;

    protected SiteImage() {
    }

    /** 사진과 설명을 바꾼다. 사진을 {@code null} 로 주면 기본 사진으로 돌아간다. */
    public void change(MediaAsset media, String alt) {
        this.media = media;
        this.alt = alt == null ? "" : alt.trim();
    }

    public SiteImageSlot getSlot() {
        return slot;
    }

    public MediaAsset getMedia() {
        return media;
    }

    public String getAlt() {
        return alt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
