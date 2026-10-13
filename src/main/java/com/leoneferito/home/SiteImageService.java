package com.leoneferito.home;

import com.leoneferito.common.error.ResourceNotFoundException;
import com.leoneferito.common.error.StaleListException;
import com.leoneferito.media.MediaAsset;
import com.leoneferito.media.MediaAssetRepository;
import com.leoneferito.product.FrontRebuildTrigger;
import com.leoneferito.product.Product;
import com.leoneferito.product.ProductRepository;
import com.leoneferito.product.ProductStatus;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
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
 *
 * <p>메인 배너 목록(V25 {@link HeroBanner})도 여기서 다룬다 — 사진 칸과 같은 규칙(빌드 때 받고, 저장하면 다시 만든다)이다.
 */
@Service
public class SiteImageService {

    private static final Logger log = LoggerFactory.getLogger(SiteImageService.class);

    /** 배너는 이만큼까지 — 넘기는 배너가 이보다 많으면 손님이 끝까지 보지 않는다. */
    public static final int MAX_BANNERS = 10;

    private static final HeroBanner.Photo EMPTY_PHOTO = new HeroBanner.Photo(null, "");

    private final SiteImageRepository images;
    private final MediaAssetRepository mediaAssets;
    private final HeroBannerRepository banners;
    private final ProductRepository products;
    private final ApplicationEventPublisher events;

    public SiteImageService(SiteImageRepository images, MediaAssetRepository mediaAssets,
                            HeroBannerRepository banners, ProductRepository products,
                            ApplicationEventPublisher events) {
        this.images = images;
        this.mediaAssets = mediaAssets;
        this.banners = banners;
        this.products = products;
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

    // ── 메인 배너 목록 (V25) ─────────────────────────────────

    /** 관리자 목록 — 전부, 놓은 순서대로. */
    @Transactional(readOnly = true)
    public List<HeroBanner> banners() {
        List<HeroBanner> all = banners.findAllOrdered();
        all.forEach(SiteImageService::loadBanner);
        return all;
    }

    /** 손님 화면에 나가는 배너 — 켜 두었고 상품이 공개 중이거나 인물 사진이 있는 것, 순서대로. */
    @Transactional(readOnly = true)
    public List<HeroBanner> shownBanners() {
        return banners().stream().filter(HeroBanner::isShown).toList();
    }

    /** 배너를 하나 더한다 — 목록 맨 뒤. {@value #MAX_BANNERS}개까지. */
    @Transactional
    public HeroBanner createBanner(BannerChange change) {
        if (banners.count() >= MAX_BANNERS) {
            throw new BannerLimitException();
        }
        HeroBanner banner = new HeroBanner(banners.maxSortOrder() + 10);
        apply(banner, change);
        banners.save(banner);
        banners.flush();
        log.info("메인 배너 추가 id={} kind={}", banner.getId(), banner.getKind());
        events.publishEvent(new FrontRebuildTrigger.CatalogChanged("메인 배너"));
        return loadBanner(banner);
    }

    @Transactional
    public HeroBanner updateBanner(UUID id, BannerChange change) {
        HeroBanner banner = findBanner(id);
        apply(banner, change);
        banners.flush();
        log.info("메인 배너 저장 id={} kind={}", id, banner.getKind());
        events.publishEvent(new FrontRebuildTrigger.CatalogChanged("메인 배너"));
        return loadBanner(banner);
    }

    @Transactional
    public void deleteBanner(UUID id) {
        banners.delete(findBanner(id));
        log.info("메인 배너 삭제 id={}", id);
        events.publishEvent(new FrontRebuildTrigger.CatalogChanged("메인 배너"));
    }

    /**
     * 순서 바꾸기. 화면이 보낸 id 목록이 <b>지금 있는 배너 전부와 정확히 같아야</b> 한다 — 다른 관리자가
     * 그 사이 더하거나 지웠는데 옛 목록으로 덮으면 순서가 뒤섞인다. 다르면 409(다시 불러오기).
     */
    @Transactional
    public List<HeroBanner> reorderBanners(List<UUID> ids) {
        List<HeroBanner> all = banners.findAllOrdered();
        if (ids.size() != all.size() || !new HashSet<>(ids).equals(
                all.stream().map(HeroBanner::getId).collect(Collectors.toSet()))) {
            throw new StaleListException();
        }
        Map<UUID, HeroBanner> byId = all.stream().collect(Collectors.toMap(HeroBanner::getId, Function.identity()));
        for (int i = 0; i < ids.size(); i++) {
            byId.get(ids.get(i)).moveTo((i + 1) * 10);
        }
        banners.flush();
        events.publishEvent(new FrontRebuildTrigger.CatalogChanged("메인 배너 순서"));
        return banners();
    }

    /**
     * 화면이 보낸 값을 배너에 넣는다. 상품 배너는 <b>공개 상품</b>만 — 없거나 비공개면 404.
     * 사진 id 가 없는 이미지를 가리켜도 404. 어느 쪽이든 아무것도 바뀌지 않는다.
     */
    private void apply(HeroBanner banner, BannerChange c) {
        Product product = null;
        if (c.kind() == HeroBanner.Kind.PRODUCT) {
            product = products.findById(c.productId())
                    .filter(p -> p.getStatus() == ProductStatus.PUBLISHED)
                    .orElseThrow(() -> new ResourceNotFoundException("공개 상품 없음: id=" + c.productId()));
        }
        boolean custom = c.kind() == HeroBanner.Kind.CUSTOM;
        boolean image = c.kind() == HeroBanner.Kind.IMAGE;
        banner.change(c.kind(), product, c.text(),
                custom || image ? photo(c.figure()) : EMPTY_PHOTO,
                custom ? photo(c.frameLeft()) : EMPTY_PHOTO,
                custom ? photo(c.frameRight()) : EMPTY_PHOTO,
                image ? photo(c.mobile()) : EMPTY_PHOTO,
                c.visible());
    }

    private HeroBanner.Photo photo(PhotoRef ref) {
        if (ref == null || ref.mediaId() == null) {
            return EMPTY_PHOTO;
        }
        MediaAsset media = mediaAssets.findById(ref.mediaId())
                .orElseThrow(() -> new ResourceNotFoundException("이미지 없음: mediaId=" + ref.mediaId()));
        return new HeroBanner.Photo(media, ref.alt());
    }

    private HeroBanner findBanner(UUID id) {
        return banners.findById(id).orElseThrow(() -> new ResourceNotFoundException("배너 없음 id=" + id));
    }

    /** 응답을 트랜잭션 밖에서 만든다 — 지연 로딩되는 상품 · 사진을 여기서 읽어 둔다. */
    private static HeroBanner loadBanner(HeroBanner b) {
        if (b.getProduct() != null) {
            b.getProduct().getSlug();
            b.getProduct().getStatus();
        }
        for (MediaAsset m : new MediaAsset[] {b.getFigure(), b.getFrameLeft(), b.getFrameRight(), b.getMobile()}) {
            if (m != null) {
                m.getStorageKey();
            }
        }
        return b;
    }

    /**
     * 배너 한 개의 저장 값. 상품은 상품 배너일 때만, 주 사진(figure)은 직접 올리기 · 이미지 한 장일 때만,
     * 액자는 직접 올리기일 때만, 휴대폰 사진(mobile)은 이미지 한 장일 때만 쓴다.
     */
    public record BannerChange(HeroBanner.Kind kind, UUID productId, HeroBanner.Text text,
                               PhotoRef figure, PhotoRef frameLeft, PhotoRef frameRight, PhotoRef mobile,
                               boolean visible) {
    }

    /** 사진 한 장 — 올린 이미지 id 와 설명. id 가 없으면 빈 칸. */
    public record PhotoRef(UUID mediaId, String alt) {
    }

    /** 배너가 이미 {@value #MAX_BANNERS}개다 (409). */
    public static class BannerLimitException extends RuntimeException {
        public BannerLimitException() {
            super("배너는 " + MAX_BANNERS + "개까지 등록할 수 있습니다. 안 쓰는 배너를 지운 뒤 다시 등록해 주세요.");
        }
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
