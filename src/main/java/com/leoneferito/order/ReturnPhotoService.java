package com.leoneferito.order;

import com.leoneferito.media.MediaAsset;
import com.leoneferito.media.MediaService;
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
    private final String apiBase;

    /**
     * @param apiBase API 의 바깥 주소(app.api.base-url). 사진 주소는 /api/returns/photos/{id} — 손님 화면이 다른 도메인이라
     *                절대 주소여야 한다. 같은 사이트(leoneferito.kr ↔ api.leoneferito.kr)라 쿠키가 실린다.
     */
    public ReturnPhotoService(ReturnPhotoRepository photos, MediaService media,
                              @org.springframework.beans.factory.annotation.Value("${app.api.base-url}") String apiBase) {
        this.photos = photos;
        this.media = media;
        this.apiBase = apiBase.replaceAll("/+$", "");
    }

    @Transactional
    public ReturnPhoto upload(UUID memberId, MultipartFile file) throws IOException {
        if (photos.countByMemberIdAndRequestIsNull(memberId) >= MAX_PENDING) {
            throw new ReturnException("올린 사진이 너무 많습니다. 신청을 마친 뒤 다시 올려 주세요.");
        }
        // 아이폰 HEIC 까지 받는다 — 손님은 사진을 찍은 그대로 올린다
        MediaAsset asset = media.upload(file, com.leoneferito.media.ImageFormat.EVIDENCE);
        // 증빙 사진은 공개 주소(/media/**)로 내보내지 않는다(V34). 주인과 관리자만 아래 serve 로 본다
        asset.markPrivate();
        UUID photoId = UUID.randomUUID();
        ReturnPhoto photo = photos.save(new ReturnPhoto(photoId, memberId, asset, apiBase + "/api/returns/photos/" + photoId));
        log.info("교환·반품 사진 업로드 photoId={}", photo.getId());
        return photo;
    }

    /** 어디에 저장됐는지(키)와 타입 — 컨트롤러가 파일을 내보낼 때 필요한 둘. 엔티티(지연 로딩)를 트랜잭션 밖으로 내보내지 않는다. */
    public record PhotoFile(String storageKey, String contentType) {
    }

    /** 보기 — 주인이거나 관리자. 아니면 404(존재를 알리지 않는다). */
    @Transactional(readOnly = true)
    public PhotoFile view(UUID photoId, UUID memberId, boolean admin) {
        ReturnPhoto photo = photos.findById(photoId)
                .filter(p -> admin || p.isOwnedBy(memberId))
                .orElseThrow(() -> new com.leoneferito.common.error.ResourceNotFoundException("사진 없음 id=" + photoId));
        MediaAsset a = photo.getMedia();
        return new PhotoFile(a.getStorageKey(), a.getContentType());
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