package com.leoneferito.order;

import com.leoneferito.media.MediaAsset;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "return_photo")
public class ReturnPhoto {
    @Id
    private UUID id;
    @Column(name ="member_id", nullable = false, updatable = false)
    private UUID memberId;
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "media_id", nullable =false, updatable = false)
    private MediaAsset media;
    @Column(nullable =false, updatable = false)
    private String url;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "return_id")
    private ReturnRequest request;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    protected ReturnPhoto(){}
    public ReturnPhoto(UUID memberId, MediaAsset media, String url){
        this.id = UUID.randomUUID();
        this.memberId = Objects.requireNonNull(memberId);
        this.media = Objects.requireNonNull(media);
        this.url = Objects.requireNonNull(url);
        this.createdAt = Instant.now();
    }
    void attachTo(ReturnRequest request){
        if(this.request != null){
            throw new IllegalStateException("이미 붙은 사진");
        }
        this.request = Objects.requireNonNull(request);
    }
    public boolean isOwnedBy(UUID memberId){
        return this.memberId.equals(memberId);
    }
    public boolean isAttached(){
        return request != null;
    }
    public UUID getId(){
        return id;
    }
    public String getUrl(){
        return url;
    }
    public Instant getCreatedAt(){
        return createdAt;
    }
}
