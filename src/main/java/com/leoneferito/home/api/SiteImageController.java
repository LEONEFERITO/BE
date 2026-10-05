package com.leoneferito.home.api;

import com.leoneferito.home.SiteImage;
import com.leoneferito.home.SiteImageService;
import com.leoneferito.home.SiteImageSlot;
import com.leoneferito.media.MediaUrls;
import jakarta.validation.Valid;
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
