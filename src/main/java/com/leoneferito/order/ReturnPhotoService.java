package com.leoneferito.order;

import com.leoneferito.media.MediaAsset;
import com.leoneferito.media.MediaService;
import com.leoneferito.media.MediaUrls;
import com.leoneferito.order.ReturnService.ReturnException;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;


@Service
public class ReturnPhotoService {

    private static final Logger log = LoggerFactory.getLogger(ReturnPhotoService.class);
    public static final int MAX_PER_REQUEST = 5;
    static final int MAX_PENDING = 10;

    private final ReturnPhotoRepository photos;
    private final MediaService media;
    private final MediaUrls urls;

    public ReturnPhotoService(ReturnPhotoRepository photos, MediaService media, MediaUrls urls) {
        this.photos = photos;
        this.media = media;
        this.urls = urls;
    }

    @Transactional
    public ReturnPhoto upload(UUID memberId, MultipartFile file) throws IOException {
        if (photos.countByMemberIdAndRequestIsNull(memberId) >= MAX_PENDING) {
            throw new ReturnException("올린 사진이 너무 많습니다. 신청을 마친 뒤 다시 올려 주세요.");
        }
        // 아이폰 HEIC 까지 받는다 — 손님은 사진을 찍은 그대로 올린다
        MediaAsset asset = media.upload(file, com.leoneferito.media.ImageFormat.EVIDENCE);
        ReturnPhoto photo = photos.save(new ReturnPhoto(memberId, asset, urls.urlFor(asset)));
        log.info("교환·반품 사진 업로드 photoId={}", photo.getId());
        return photo;
    }

    void attach(UUID memberId, List<UUID> ids, ReturnRequest request) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        if (ids.size() > MAX_PER_REQUEST || new HashSet<>(ids).size() != ids.size()) {
            throw new ReturnException("사진은 " + MAX_PER_REQUEST + "장까지 붙일 수 있습니다.");
        }
        List<ReturnPhoto> found = photos.findAllById(ids);
        if (found.size() != ids.size()
                || found.stream().anyMatch(p -> !p.isOwnedBy(memberId) || p.isAttached())) {
            throw new ReturnException("사진을 다시 올려 주세요.");
        }
        found.forEach(request::addPhoto);
    }
}