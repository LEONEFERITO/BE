package com.leoneferito.media;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** 업로드된 이미지 한 장. 바이트는 스토리지에 있고 여기에는 그 위치와 메타만 둔다. */
@Entity
@Table(name = "media_asset")
public class MediaAsset {

    @Id
    private UUID id;

    @Column(name = "original_filename", nullable = false)
    private String originalFilename;

    @Column(name = "content_type", nullable = false)
    private String contentType;

    @Column(name = "byte_size", nullable = false)
    private long byteSize;

    @Column(name = "width")
    private Integer width;

    @Column(name = "height")
    private Integer height;

    @Column(name = "storage_key", nullable = false, unique = true)
    private String storageKey;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    protected MediaAsset() {
        // JPA
    }

    public MediaAsset(UUID id, String originalFilename, ImageFormat format, long byteSize,
                      Integer width, Integer height, String storageKey) {
        this.id = id;
        this.originalFilename = originalFilename;
        this.contentType = format.contentType();
        this.byteSize = byteSize;
        this.width = width;
        this.height = height;
        this.storageKey = storageKey;
    }

    public UUID getId() {
        return id;
    }

    public String getOriginalFilename() {
        return originalFilename;
    }

    public String getContentType() {
        return contentType;
    }

    public long getByteSize() {
        return byteSize;
    }

    public Integer getWidth() {
        return width;
    }

    public Integer getHeight() {
        return height;
    }

    public String getStorageKey() {
        return storageKey;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
