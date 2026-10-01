package com.leoneferito.content;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 공지사항. 본문은 글자 그대로 — HTML 을 받지 않는다(화면이 줄바꿈만 살려서 보여 준다).
 * 처음 공개한 시각(publishedAt)이 목록의 날짜다. 내렸다 다시 올려도 바뀌지 않는다.
 */
@Entity
@Table(name = "notice")
public class Notice {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false)
    private String body;

    @Column(nullable = false)
    private boolean pinned;

    @Column(nullable = false)
    private boolean published;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private Instant updatedAt;

    protected Notice() {
        // JPA
    }

    public Notice(String title, String body, boolean pinned, boolean published) {
        this.id = UUID.randomUUID();
        this.createdAt = Instant.now();
        update(title, body, pinned, published);
    }

    public void update(String title, String body, boolean pinned, boolean published) {
        this.title = Objects.requireNonNull(title).trim();
        this.body = Objects.requireNonNull(body).trim();
        this.pinned = pinned;
        this.published = published;
        if (published && publishedAt == null) {
            publishedAt = Instant.now();
        }
    }

    public UUID getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public String getBody() {
        return body;
    }

    public boolean isPinned() {
        return pinned;
    }

    public boolean isPublished() {
        return published;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
