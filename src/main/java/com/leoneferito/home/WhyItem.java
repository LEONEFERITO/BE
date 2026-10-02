package com.leoneferito.home;

import com.leoneferito.media.MediaAsset;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "why_item")
public class WhyItem {

    @Id
    private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "section_id", nullable = false)
    private WhySection section;
    @Column(nullable = false)
    private String title;
    @Column(nullable = false)
    private String body;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "media_id")
    private MediaAsset media;
    @Column(name = "sort_order", nullable = false)
    private int sortOrder;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected WhyItem() {}
    public WhyItem(String title, String body, MediaAsset media) {
        this.id = UUID.randomUUID();
        this.title = Objects.requireNonNull(title).trim();
        this.body = Objects.requireNonNull(body).trim();
        this.media = media;
        this.createdAt = Instant.now();
    }
    void attachTo(WhySection section, int sortOrder) {
        this.section = section;
        this.sortOrder = sortOrder;
    }
    public UUID getId() {return id;}
    public String getTitle() {return title;}
    public String getBody() {return body;}
    public MediaAsset getMedia() {return media;}
    public int getSortOrder() {return sortOrder;}
}