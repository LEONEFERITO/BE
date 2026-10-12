package com.leoneferito.home.api;

import com.leoneferito.home.HeroConfig;
import com.leoneferito.home.SiteImage;
import com.leoneferito.home.SiteImageService;
import com.leoneferito.home.SiteImageSlot;
import com.leoneferito.media.MediaUrls;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 사이트 사진 칸 (V20).
 *
 * <ul>
 *   <li>{@code GET /api/site-images} — 손님 화면이 빌드 때 받는다. 로그인 없이 읽힌다(SecurityConfig).</li>
 *   <li>{@code GET · PUT /api/admin/site-images…} — 관리자. {@code /api/admin/**} 규칙으로 ADMIN 만.</li>
 *   <li>{@code GET /api/hero} · {@code GET · PUT /api/admin/hero} — 메인 배너 설정(V24). 배너 사진은 위 사진 칸(HERO_*)이다.</li>
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

    // ── 메인 배너 (V24) ──────────────────────────────────────

    /** 손님 화면이 빌드 때 받는다. 상품은 주소(slug)로만 — 손님 쪽은 id 를 쓰지 않는다. */
    @GetMapping("/api/hero")
    public HeroView hero() {
        HeroConfig h = images.hero();
        return new HeroView(h.getMode(), h.getProduct() == null ? null : h.getProduct().getSlug(),
                h.getEyebrow(), h.getTitle(), h.getSubtitle(), h.getCtaLabel(), h.getCtaUrl());
    }

    @GetMapping("/api/admin/hero")
    public AdminHeroView adminHero() {
        return AdminHeroView.of(images.hero());
    }

    @PutMapping("/api/admin/hero")
    public AdminHeroView changeHero(@Valid @RequestBody HeroInput in) {
        return AdminHeroView.of(images.changeHero(in.mode(), in.productId(), in.eyebrow(), in.title(), in.subtitle(),
                in.ctaLabel(), in.ctaUrl()));
    }

    /**
     * 배너 설정. 길이는 hero_config 의 CHECK 와 같다. 버튼 주소는 사이트 안 주소(/…) 또는 https 주소만 —
     * "//다른곳" 은 사이트 안 주소처럼 보이지만 밖으로 나가서 막는다. javascript: 같은 주소도 막힌다.
     * PRODUCT 인데 상품이 없으면 400 — DB 까지 가지 않는다.
     */
    public record HeroInput(
            @NotNull HeroConfig.Mode mode,
            UUID productId,
            @Size(max = 40) String eyebrow,
            @Size(max = 60) String title,
            @Size(max = 80) String subtitle,
            @Size(max = 20) String ctaLabel,
            @Size(max = 300) @Pattern(regexp = "^$|^/(?!/)\\S*$|^https://\\S+$") String ctaUrl) {

        @AssertTrue(message = "상품 지정 모드에는 상품이 필요합니다")
        public boolean isProductGivenWhenNeeded() {
            return mode != HeroConfig.Mode.PRODUCT || productId != null;
        }
    }

    public record HeroView(HeroConfig.Mode mode, String productSlug, String eyebrow, String title, String subtitle,
                           String ctaLabel, String ctaUrl) {
    }

    public record AdminHeroView(HeroConfig.Mode mode, UUID productId, String productSlug, String eyebrow, String title,
                                String subtitle, String ctaLabel, String ctaUrl) {
        static AdminHeroView of(HeroConfig h) {
            return new AdminHeroView(h.getMode(),
                    h.getProduct() == null ? null : h.getProduct().getId(),
                    h.getProduct() == null ? null : h.getProduct().getSlug(),
                    h.getEyebrow(), h.getTitle(), h.getSubtitle(), h.getCtaLabel(), h.getCtaUrl());
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
