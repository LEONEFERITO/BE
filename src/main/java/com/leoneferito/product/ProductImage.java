package com.leoneferito.product;

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
import java.util.Objects;
import java.util.UUID;

/**
 * 상품 사진 한 장.
 *
 * <p>바이트는 여기 없다. {@link MediaAsset} 이 가리키는 스토리지에 있다 —
 * 이미지를 DB 에 넣으면 백업·복제 단위가 통째로 무거워진다 (V2 주석 참고).
 *
 * <p>{@link #alt} 가 {@code NOT NULL} 인 이유: 나중에 채우게 두면 영원히 비어 있다.
 * 장식이 아니라 상품 사진이므로, 빈 대체 텍스트는 스크린리더 사용자에게
 * 상품이 없는 것과 같다. 빈 문자열은 허용한다 — "장식이라 읽지 말 것" 은 유효한 의도이고,
 * {@code null}("아직 안 정함")과 다르다.
 */
@Entity
@Table(name = "product_image")
public class ProductImage {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "media_id", nullable = false)
    private MediaAsset media;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ProductImageKind kind;

    @Column(nullable = false)
    private String alt;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    protected ProductImage() {
        // JPA
    }

    public ProductImage(UUID id, MediaAsset media, ProductImageKind kind, String alt, int sortOrder) {
        this.id = Objects.requireNonNull(id, "id");
        this.media = Objects.requireNonNull(media, "media");
        this.kind = Objects.requireNonNull(kind, "kind");
        this.alt = Objects.requireNonNull(alt, "alt");
        this.sortOrder = sortOrder;
    }

    /** {@link Product#addImage} 가 호출한다. 양쪽 참조를 한 번에 맞추기 위한 것이다. */
    void assignTo(Product product) {
        this.product = product;
    }

    public UUID getId() {
        return id;
    }

    public Product getProduct() {
        return product;
    }

    public MediaAsset getMedia() {
        return media;
    }

    public ProductImageKind getKind() {
        return kind;
    }

    public String getAlt() {
        return alt;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ProductImage other && id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
