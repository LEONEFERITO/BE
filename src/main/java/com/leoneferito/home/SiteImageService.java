package com.leoneferito.home;

import com.leoneferito.common.error.ResourceNotFoundException;
import com.leoneferito.media.MediaAsset;
import com.leoneferito.media.MediaAssetRepository;
import com.leoneferito.product.FrontRebuildTrigger;
import com.leoneferito.product.Product;
import com.leoneferito.product.ProductRepository;
import com.leoneferito.product.ProductStatus;
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
 *
 * <p>메인 배너 설정(V24 {@link HeroConfig})도 여기서 읽고 바꾼다 — 배너 사진이 이 사진 칸(HERO_*)에 있어서
 * 한 화면(관리자 "메인 배너")이 둘을 같이 다룬다.
 */
@Service
public class SiteImageService {

    private static final Logger log = LoggerFactory.getLogger(SiteImageService.class);

    /** hero_config 는 한 행뿐이다(V2 · V24). */
    private static final short HERO_ID = 1;

    private final SiteImageRepository images;
    private final MediaAssetRepository mediaAssets;
    private final HeroConfigRepository heroes;
    private final ProductRepository products;
    private final ApplicationEventPublisher events;

    public SiteImageService(SiteImageRepository images, MediaAssetRepository mediaAssets,
                            HeroConfigRepository heroes, ProductRepository products,
                            ApplicationEventPublisher events) {
        this.images = images;
        this.mediaAssets = mediaAssets;
        this.heroes = heroes;
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

    // ── 메인 배너 (V24) ──────────────────────────────────────

    /** 메인 배너 설정. 행은 V2 가 심었다. */
    @Transactional(readOnly = true)
    public HeroConfig hero() {
        return loadHero(heroes.findById(HERO_ID)
                .orElseThrow(() -> new ResourceNotFoundException("메인 배너 설정 없음 (V2 · V24 가 돌지 않음)")));
    }

    /**
     * 메인 배너 설정을 바꾼다. PRODUCT 는 <b>공개 상품</b>만 고를 수 있다 — 없거나 비공개면 404, 아무것도 바뀌지 않는다.
     * 저장하면 손님 화면을 다시 만든다.
     */
    @Transactional
    public HeroConfig changeHero(HeroConfig.Mode mode, UUID productId, String eyebrow, String title, String subtitle,
                                 String ctaLabel, String ctaUrl) {
        HeroConfig hero = heroes.findById(HERO_ID)
                .orElseThrow(() -> new ResourceNotFoundException("메인 배너 설정 없음 (V2 · V24 가 돌지 않음)"));
        Product product = null;
        if (mode == HeroConfig.Mode.PRODUCT) {
            product = products.findById(productId)
                    .filter(p -> p.getStatus() == ProductStatus.PUBLISHED)
                    .orElseThrow(() -> new ResourceNotFoundException("공개 상품 없음: id=" + productId));
        }
        hero.change(mode, product, eyebrow, title, subtitle, ctaLabel, ctaUrl);
        heroes.flush();
        log.info("메인 배너 저장 mode={}", mode);
        events.publishEvent(new FrontRebuildTrigger.CatalogChanged("메인 배너"));
        return loadHero(hero);
    }

    /** 응답을 트랜잭션 밖에서 만든다 — 지연 로딩되는 상품의 주소 · id 를 여기서 읽어 둔다. */
    private static HeroConfig loadHero(HeroConfig hero) {
        if (hero.getProduct() != null) {
            hero.getProduct().getSlug();
        }
        return hero;
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
