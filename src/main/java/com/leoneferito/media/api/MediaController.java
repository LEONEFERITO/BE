package com.leoneferito.media.api;

import com.leoneferito.media.MediaAsset;
import com.leoneferito.media.MediaAssetRepository;
import com.leoneferito.media.MediaService;
import com.leoneferito.media.MediaStorage;
import com.leoneferito.media.MediaUrls;
import com.leoneferito.common.error.ResourceNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import java.time.Duration;

/**
 * 이미지 업로드와 서빙.
 *
 * <p>업로드는 {@code /api/admin/media} — 관리자만. 서빙은 {@code /media/**} — 누구나.
 * 경로가 다른 이유는 권한이 다르기 때문이고, 권한은 {@code SecurityConfig} 가 경로로 가른다.
 */
@RestController
public class MediaController {

    private final MediaService mediaService;
    private final MediaAssetRepository assets;
    private final MediaStorage storage;
    private final MediaUrls urls;

    public MediaController(MediaService mediaService, MediaAssetRepository assets,
                           MediaStorage storage, MediaUrls urls) {
        this.mediaService = mediaService;
        this.assets = assets;
        this.storage = storage;
        this.urls = urls;
    }

    /**
     * 업로드. 관리자 전용.
     *
     * <p>응답에 <b>바로 쓸 수 있는 url</b> 을 함께 준다. 관리자 화면이 업로드 직후
     * 미리보기를 그려야 하는데, id 만 주면 주소를 어떻게 만드는지 화면이 알아야 한다 —
     * 그러면 주소 규칙이 서버와 화면 두 곳에 생긴다.
     */
    @PostMapping("/api/admin/media")
    public UploadResponse upload(@RequestParam("file") MultipartFile file) throws IOException {
        MediaAsset asset = mediaService.upload(file);
        return new UploadResponse(
                asset.getId(),
                urls.urlFor(asset),
                asset.getOriginalFilename(),
                asset.getByteSize(),
                asset.getWidth(),
                asset.getHeight());
    }

    public record UploadResponse(
            UUID id, String url, String filename, long byteSize, Integer width, Integer height) {
    }

    /**
     * 서빙. 공개다.
     *
     * <p>{@code **} 로 받는 이유: 저장 키가 {@code 2026-09-29/uuid.webp} 처럼 슬래시를 포함한다.
     *
     * <h2>Content-Type 은 DB 에 기록된 값만 쓴다</h2>
     * 업로드 때 매직바이트로 판별해 저장한 값이다. 확장자에서 추측하면
     * 위장 파일이 HTML 로 해석되는 길이 다시 열린다.
     *
     * <h2>nosniff 를 반드시 붙인다</h2>
     * 이게 없으면 브라우저가 우리가 선언한 타입을 무시하고 내용을 보고 추측한다.
     * 이미지로 위장한 HTML 이 실행되는 마지막 통로가 여기다.
     */
    @GetMapping("/media/**")
    public ResponseEntity<Resource> serve(jakarta.servlet.http.HttpServletRequest request)
            throws IOException {

        String key = extractKey(request.getRequestURI());
        MediaAsset asset = assets.findByStorageKey(key)
                .orElseThrow(() -> new ResourceNotFoundException("미디어 없음: key=" + key));

        Path path = storage.read(key);

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(asset.getContentType()))
                .contentLength(Files.size(path))
                // 브라우저의 타입 추측을 막는다. 저장형 XSS 의 마지막 통로다.
                // HttpHeaders 에 상수가 없는 헤더라 문자열로 쓴다.
                .header("X-Content-Type-Options", "nosniff")
                // 파일명을 주지 않는다. inline 이면 브라우저가 그대로 그린다.
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
                /*
                 * 키가 UUID 라 내용이 바뀌면 주소도 바뀐다(불변 자산).
                 * 그래서 오래 캐시해도 안전하고, 캐시가 길수록 CDN 비용과 로딩이 좋아진다.
                 */
                .cacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable())
                .body(new FileSystemResource(path));
    }

    /**
     * 요청 경로에서 저장 키를 꺼낸다.
     *
     * <p>{@code /media/} 뒤 전부가 키다. 경로 순회는 여기서 막지 않고
     * {@link MediaStorage} 가 루트 밖으로 나가는 경로를 거부한다 —
     * 검사를 한 곳에만 두어야 새 호출자가 생겨도 뚫리지 않는다.
     */
    private String extractKey(String uri) {
        String prefix = "/media/";
        int at = uri.indexOf(prefix);
        if (at < 0) throw new ResourceNotFoundException("미디어 경로 아님: " + uri);
        return java.net.URLDecoder.decode(
                uri.substring(at + prefix.length()), java.nio.charset.StandardCharsets.UTF_8);
    }
}
