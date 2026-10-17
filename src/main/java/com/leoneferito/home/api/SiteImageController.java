package com.leoneferito.home.api;

import com.leoneferito.home.HeroBanner;
import com.leoneferito.home.SiteImage;
import com.leoneferito.home.SiteImageService;
import com.leoneferito.home.SiteImageSlot;
import com.leoneferito.media.MediaAsset;
import com.leoneferito.media.MediaUrls;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 사이트 사진 칸 (V20).
 *
 * <ul>
 *   <li>{@code GET /api/site-images} — 손님 화면이 빌드 때 받는다. 로그인 없이 읽힌다(SecurityConfig).</li>
 *   <li>{@code GET · PUT /api/admin/site-images…} — 관리자. {@code /api/admin/**} 규칙으로 ADMIN 만.</li>
 *   <li>{@code GET /api/hero-banners} — 손님 화면에 나가는 메인 배너(V25). {@code /api/admin/hero-banners/**} — 목록 관리.</li>
 * </ul>
 *
 * <p>없는 칸 이름({@code PUT …/NOPE})은 경로 변수 변환에서 걸려 400 이다 — 칸은 enum 이다.
 */
@RestController
public class SiteImageController {

    /** site_image.alt 의 CHECK 와 같은 값. */
    static final int MAX_ALT = 200;

    private final SiteImageService images;
    private final MediaUrls urls;

    public SiteImageController(SiteImageService images, MediaUrls urls) {
        this.images = images;
        this.urls = urls;
    }

    @GetMapping("/api/site-images")
    public List<View> publicView() {
        return images.all().stream().map(i -> View.of(i, urls)).toList();
    }

    @GetMapping("/api/admin/site-images")
    public List<View> adminView() {
        return images.all().stream().map(i -> View.of(i, urls)).toList();
    }

    @PutMapping("/api/admin/site-images/{slot}")
    public View change(@PathVariable SiteImageSlot slot, @Valid @RequestBody Input in) {
        return View.of(images.change(slot, in.mediaId(), in.alt()), urls);
    }

    // ── 메인 배너 목록 (V25) ─────────────────────────────────

    /** 손님 화면이 빌드 때 받는다 — 실제로 나가는 배너만, 순서대로. 상품은 주소(slug)로만. */
    @GetMapping("/api/hero-banners")
    public List<BannerView> shownBanners() {
        return images.shownBanners().stream().map(b -> BannerView.of(b, urls)).toList();
    }

    @GetMapping("/api/admin/hero-banners")
    public List<AdminBannerView> adminBanners() {
        return images.banners().stream().map(b -> AdminBannerView.of(b, urls)).toList();
    }

    @PostMapping("/api/admin/hero-banners")
    public ResponseEntity<AdminBannerView> createBanner(@Valid @RequestBody BannerInput in) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(AdminBannerView.of(images.createBanner(in.toChange()), urls));
    }

    @PutMapping("/api/admin/hero-banners/{id}")
    public AdminBannerView updateBanner(@PathVariable UUID id, @Valid @RequestBody BannerInput in) {
        return AdminBannerView.of(images.updateBanner(id, in.toChange()), urls);
    }

    @DeleteMapping("/api/admin/hero-banners/{id}")
    public ResponseEntity<Void> deleteBanner(@PathVariable UUID id) {
        images.deleteBanner(id);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/api/admin/hero-banners/order")
    public List<AdminBannerView> reorderBanners(@Valid @RequestBody Reorder in) {
        return images.reorderBanners(in.ids()).stream().map(b -> AdminBannerView.of(b, urls)).toList();
    }

    /**
     * 배너 한 개. 길이는 hero_banner 의 CHECK 와 같다. 버튼 주소는 사이트 안 주소(/…) 또는 https 주소만 —
     * "//다른곳" 은 사이트 안 주소처럼 보이지만 밖으로 나가서 막는다. javascript: 같은 주소도 막힌다.
     * 상품 배너인데 상품이 없거나, 직접 올리기인데 인물 사진이 없거나, 이미지 한 장인데 큰 사진이 없으면 400 — DB 까지 가지 않는다.
     * 이미지 한 장은 큰 사진을 figure 로, 휴대폰용 세로 사진(선택)을 mobile 로 받는다.
     * 영상(V29)은 PC 영상을 figure 로, 휴대폰 영상(선택)을 mobile 로, 첫 장면 사진(선택)을 poster · mobilePoster 로 받는다.
     * {@code ctaEnabled}(V35) 를 안 보내면 켠 것으로 본다 — 예전 관리자 화면이 보내는 요청이 그대로 통한다.
     */
    public record BannerInput(
            @NotNull HeroBanner.Kind kind,
            UUID productId,
            @Size(max = 40) String eyebrow,
            @Size(max = 60) String title,
            @Size(max = 80) String subtitle,
            @Size(max = 20) String ctaLabel,
            @Size(max = 300) @Pattern(regexp = "^$|^/(?!/)\\S*$|^https://\\S+$") String ctaUrl,
            Boolean ctaEnabled,
            @Valid PhotoInput figure,
            @Valid PhotoInput frameLeft,
            @Valid PhotoInput frameRight,
            @Valid PhotoInput mobile,
            @Valid PhotoInput poster,
            @Valid PhotoInput mobilePoster,
            @NotNull Boolean visible) {

        @AssertTrue(message = "상품 정보 가져오기에는 상품이 필요합니다")
        public boolean isProductGivenWhenNeeded() {
            return kind != HeroBanner.Kind.PRODUCT || productId != null;
        }

        @AssertTrue(message = "직접 등록에는 가운데 인물 사진이, 이미지 한 장에는 큰 사진이, 영상에는 영상이 필요합니다")
        public boolean isFigureGivenWhenNeeded() {
            return kind == HeroBanner.Kind.PRODUCT || (figure != null && figure.mediaId() != null);
        }

        SiteImageService.BannerChange toChange() {
            return new SiteImageService.BannerChange(kind, productId,
                    new HeroBanner.Text(eyebrow, title, subtitle, ctaLabel, ctaUrl, ctaEnabled == null || ctaEnabled),
                    ref(figure), ref(frameLeft), ref(frameRight), ref(mobile), ref(poster), ref(mobilePoster),
                    visible);
        }

        private static SiteImageService.PhotoRef ref(PhotoInput p) {
            return p == null ? null : new SiteImageService.PhotoRef(p.mediaId(), p.alt());
        }
    }

    public record PhotoInput(UUID mediaId, @Size(max = MAX_ALT) String alt) {
    }

    public record Reorder(@NotNull @Size(max = SiteImageService.MAX_BANNERS) List<@NotNull UUID> ids) {
    }

    /** 사진 한 장 — 손님용은 주소와 설명만. */
    public record PhotoView(String url, String alt) {
        static PhotoView of(MediaAsset m, String alt, MediaUrls urls) {
            return m == null ? null : new PhotoView(urls.urlFor(m), alt);
        }
    }

    /** 영상 배너는 figure · mobile 이 영상 주소, poster · mobilePoster 가 첫 장면 사진이다. {@code ctaEnabled} 가 false 면 버튼 없음. */
    public record BannerView(HeroBanner.Kind kind, String productSlug, String eyebrow, String title, String subtitle,
                             String ctaLabel, String ctaUrl, boolean ctaEnabled, PhotoView figure, PhotoView frameLeft,
                             PhotoView frameRight, PhotoView mobile, PhotoView poster, PhotoView mobilePoster) {
        static BannerView of(HeroBanner b, MediaUrls urls) {
            return new BannerView(b.getKind(), b.getProduct() == null ? null : b.getProduct().getSlug(),
                    b.getEyebrow(), b.getTitle(), b.getSubtitle(), b.getCtaLabel(), b.getCtaUrl(), b.isCtaEnabled(),
                    PhotoView.of(b.getFigure(), b.getFigureAlt(), urls),
                    PhotoView.of(b.getFrameLeft(), b.getFrameLeftAlt(), urls),
                    PhotoView.of(b.getFrameRight(), b.getFrameRightAlt(), urls),
                    PhotoView.of(b.getMobile(), b.getMobileAlt(), urls),
                    PhotoView.of(b.getPoster(), "", urls),
                    PhotoView.of(b.getMobilePoster(), "", urls));
        }
    }

    /** 관리자용 사진 — 다시 저장할 수 있게 이미지 id 도 준다. */
    public record AdminPhotoView(UUID mediaId, String url, String alt) {
        static AdminPhotoView of(MediaAsset m, String alt, MediaUrls urls) {
            return m == null ? null : new AdminPhotoView(m.getId(), urls.urlFor(m), alt);
        }
    }

    /** {@code shown} — 손님 화면에 실제로 나가는가(켜 두었고 상품 공개 중 · 인물 사진 있음). */
    public record AdminBannerView(UUID id, HeroBanner.Kind kind, UUID productId, String productSlug,
                                  String eyebrow, String title, String subtitle, String ctaLabel, String ctaUrl,
                                  boolean ctaEnabled,
                                  AdminPhotoView figure, AdminPhotoView frameLeft, AdminPhotoView frameRight,
                                  AdminPhotoView mobile, AdminPhotoView poster, AdminPhotoView mobilePoster,
                                  boolean visible, boolean shown, Instant updatedAt) {
        static AdminBannerView of(HeroBanner b, MediaUrls urls) {
            return new AdminBannerView(b.getId(), b.getKind(),
                    b.getProduct() == null ? null : b.getProduct().getId(),
                    b.getProduct() == null ? null : b.getProduct().getSlug(),
                    b.getEyebrow(), b.getTitle(), b.getSubtitle(), b.getCtaLabel(), b.getCtaUrl(), b.isCtaEnabled(),
                    AdminPhotoView.of(b.getFigure(), b.getFigureAlt(), urls),
                    AdminPhotoView.of(b.getFrameLeft(), b.getFrameLeftAlt(), urls),
                    AdminPhotoView.of(b.getFrameRight(), b.getFrameRightAlt(), urls),
                    AdminPhotoView.of(b.getMobile(), b.getMobileAlt(), urls),
                    AdminPhotoView.of(b.getPoster(), "", urls),
                    AdminPhotoView.of(b.getMobilePoster(), "", urls),
                    b.isVisible(), b.isShown(), b.getUpdatedAt());
        }
    }

    /** {@code mediaId} 가 없으면 사진을 비운다. {@code alt} 는 없어도 된다(빈 문자열로 저장). */
    public record Input(UUID mediaId, @Size(max = MAX_ALT) String alt) {
    }

    public record View(SiteImageSlot slot, UUID mediaId, String imageUrl, String alt) {
        static View of(SiteImage i, MediaUrls urls) {
            return new View(i.getSlot(),
                    i.getMedia() == null ? null : i.getMedia().getId(),
                    i.getMedia() == null ? null : urls.urlFor(i.getMedia()),
                    i.getAlt());
        }
    }
}
