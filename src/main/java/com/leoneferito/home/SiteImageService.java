package com.leoneferito.home;

import com.leoneferito.common.error.ResourceNotFoundException;
import com.leoneferito.media.MediaAsset;
import com.leoneferito.media.MediaAssetRepository;
import com.leoneferito.product.FrontRebuildTrigger;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 사이트 사진 칸 (V20) — 읽기와 바꾸기.
 *
 * <p>WHY 구간({@link WhyService})과 같은 규칙이다: 손님 화면은 빌드 때 받고, 저장하면 다시 만든다.
 * 칸은 마이그레이션이 심는다 — 여기서 만들거나 지우지 않는다.
 */
@Service
public class SiteImageService {

    private static final Logger log = LoggerFactory.getLogger(SiteImageService.class);

    private final SiteImageRepository images;
    private final MediaAssetRepository mediaAssets;
    private final ApplicationEventPublisher events;

    public SiteImageService(SiteImageRepository images, MediaAssetRepository mediaAssets,
                            ApplicationEventPublisher events) {
        this.images = images;
        this.mediaAssets = mediaAssets;
        this.events = events;
    }

    /** 모든 칸. 선언 순서(= 화면에 나오는 순서)대로. */
    @Transactional(readOnly = true)
    public List<SiteImage> all() {
        List<SiteImage> found = images.findAll().stream()
                .sorted(Comparator.comparing(SiteImage::getSlot))
                .toList();
        found.forEach(SiteImageService::load);
        return found;
    }

    /**
     * 한 칸의 사진과 설명을 바꾼다. {@code mediaId} 가 {@code null} 이면 사진을 비운다(기본 사진으로 돌아간다).
     * 없는 이미지 id 는 404 — 아무것도 바뀌지 않는다.
     */
    @Transactional
    public SiteImage change(SiteImageSlot slot, UUID mediaId, String alt) {
        SiteImage image = images.findById(slot)
                .orElseThrow(() -> new ResourceNotFoundException("사진 칸 없음: " + slot + " (V20 이 돌지 않음)"));
        MediaAsset media = mediaId == null ? null : mediaAssets.findById(mediaId)
                .orElseThrow(() -> new ResourceNotFoundException("이미지 없음: mediaId=" + mediaId));

        image.change(media, alt);
        images.flush();
        log.info("사이트 사진 저장 slot={} hasImage={}", slot, media != null);
        // 손님 화면은 정적이다 — 다시 만들어야 바뀐다. 커밋된 뒤에 예약된다(FrontRebuildTrigger).
        events.publishEvent(new FrontRebuildTrigger.CatalogChanged("사이트 사진"));
        return load(image);
    }

    /**
     * 응답은 트랜잭션 밖(컨트롤러)에서 만들어지는데 open-in-view 를 꺼 두었으므로, 지연 로딩되는
     * 이미지를 여기서 읽어 둔다. 안 그러면 주소를 만들 때 터진다.
     */
    private static SiteImage load(SiteImage image) {
        if (image.getMedia() != null) {
            image.getMedia().getStorageKey();
        }
        return image;
    }
}
