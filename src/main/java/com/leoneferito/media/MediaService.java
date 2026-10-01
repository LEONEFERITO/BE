package com.leoneferito.media;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * 이미지 업로드.
 *
 * <p>여기가 <b>신뢰 경계</b>다. 여기 들어오는 바이트는 전부 남이 준 것이다.
 *
 * <h2>선언된 타입을 믿지 않는다</h2>
 * 브라우저가 보낸 {@code Content-Type} 과 파일 확장자는 둘 다 사용자 입력이다.
 * {@code evil.html} 을 {@code photo.png} 라는 이름에 {@code image/png} 라고 선언해
 * 올린 뒤 같은 오리진에서 그 URL 을 열면 브라우저가 HTML 로 해석한다(저장형 XSS).
 *
 * <p>그래서 <b>앞머리 바이트로 직접 판별</b>하고({@link ImageFormat#sniff}),
 * 내보낼 때도 그때 판별한 값만 쓴다. 판별되지 않으면 거부한다 —
 * "모르면 일단 통과" 가 아니라 "모르면 거부" 다.
 */
@Service
public class MediaService {

    private static final Logger log = LoggerFactory.getLogger(MediaService.class);

    /**
     * 한 장의 최대 크기.
     *
     * <p>서블릿 단(spring.servlet.multipart.max-file-size)에서도 같은 값으로 막는다.
     * 그쪽은 <b>디스크에 다 받기 전에</b> 끊고, 여기는 최종 확인이다.
     * 한 겹만 두면 설정이 바뀌는 날 다른 한쪽이 조용히 열린다.
     */
    public static final long MAX_BYTES = 10L * 1024 * 1024;

    /**
     * 픽셀 수 상한.
     *
     * <p>용량만 막으면 부족하다. 압축이 잘 되는 이미지는 몇 백 KB 로도
     * 수억 픽셀이 될 수 있고(압축 폭탄), 그걸 디코딩하는 순간 메모리가 터진다.
     * 1억 픽셀 ≈ 10000×10000. 상품 사진이 이보다 클 이유가 없다.
     */
    private static final long MAX_PIXELS = 100_000_000L;

    private final MediaAssetRepository assets;
    private final MediaStorage storage;

    public MediaService(MediaAssetRepository assets, MediaStorage storage) {
        this.assets = assets;
        this.storage = storage;
    }

    /** 화면에 그릴 이미지(상품 사진 등) — {@link ImageFormat#WEB}. HEIC 는 받지 않는다. */
    @Transactional
    public MediaAsset upload(MultipartFile file) throws IOException {
        return upload(file, ImageFormat.WEB);
    }

    /** 받을 형식을 부르는 쪽이 정한다 (교환·반품 사진은 {@link ImageFormat#EVIDENCE} — 아이폰 HEIC 포함). */
    @Transactional
    public MediaAsset upload(MultipartFile file, java.util.Set<ImageFormat> allowed) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new InvalidImageException("이미지 파일을 선택해 주세요.");
        }
        if (file.getSize() > MAX_BYTES) {
            throw new InvalidImageException(
                    "이미지가 너무 큽니다. " + (MAX_BYTES / 1024 / 1024) + "MB 이하로 올려 주세요.");
        }

        /*
         * 전체를 메모리에 올린다. 10MB 상한이 이미 걸려 있어 안전하고,
         * 스트림을 두 번(판별 + 저장) 읽어야 하므로 되감기가 필요하다.
         */
        byte[] bytes = file.getBytes();

        ImageFormat format = ImageFormat.sniff(bytes);
        if (format == null || !allowed.contains(format)) {
            // 무엇이 잘못됐는지 구체적으로 알려주지 않는다. 우회 시도에 힌트가 된다.
            log.info("받지 않는 이미지 형식 업로드 거부 format={} size={}", format, bytes.length);
            throw new InvalidImageException(allowed.contains(ImageFormat.HEIC)
                    ? "JPG · PNG · WebP · GIF · AVIF · BMP · HEIC(아이폰) 사진만 올릴 수 있습니다."
                    : format == ImageFormat.HEIC
                            ? "HEIC(아이폰) 사진은 손님 화면에 보이지 않습니다. JPG 로 바꿔 올려 주세요."
                            : "JPG · PNG · WebP · GIF · AVIF · BMP 이미지만 올릴 수 있습니다.");
        }

        Dimensions size = measure(bytes);

        String key = storage.store(new ByteArrayInputStream(bytes), format);

        MediaAsset asset = assets.save(new MediaAsset(
                UUID.randomUUID(),
                // 원본 이름은 표시용으로만 남는다. 경로에는 절대 쓰이지 않는다.
                safeDisplayName(file.getOriginalFilename()),
                format,
                bytes.length,
                size.width(),
                size.height(),
                key));

        log.info("이미지 업로드 mediaId={} format={} bytes={}", asset.getId(), format, bytes.length);
        return asset;
    }

    private record Dimensions(Integer width, Integer height) {
    }

    /**
     * 가로·세로를 잰다.
     *
     * <p>크기를 재는 김에 <b>압축 폭탄</b>을 걸러낸다. ImageIO 는 헤더만 읽어
     * 크기를 알 수 있으므로, 전체를 디코딩하기 전에 픽셀 수를 확인할 수 있다.
     */
    private Dimensions measure(byte[] bytes) throws IOException {
        try (InputStream in = new ByteArrayInputStream(bytes);
             javax.imageio.stream.ImageInputStream stream = ImageIO.createImageInputStream(in)) {

            var readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) {
                /*
                 * 매직바이트는 맞는데 읽을 수 있는 리더가 없다 — WebP 가 대표적이다
                 * (JDK 기본 ImageIO 에 WebP 리더가 없다). 크기를 모르는 것뿐이고
                 * 형식 자체는 이미 확인했으므로 통과시킨다. 크기는 null 로 둔다.
                 */
                return new Dimensions(null, null);
            }

            var reader = readers.next();
            try {
                reader.setInput(stream);
                int w = reader.getWidth(0);
                int h = reader.getHeight(0);
                if ((long) w * h > MAX_PIXELS) {
                    throw new InvalidImageException("이미지 해상도가 너무 큽니다.");
                }
                return new Dimensions(w, h);
            } finally {
                reader.dispose();
            }
        } catch (javax.imageio.IIOException e) {
            // 헤더가 깨진 파일. 형식 판별은 통과했지만 실제로는 읽을 수 없다.
            throw new InvalidImageException("이미지를 읽을 수 없습니다. 다른 파일로 시도해 주세요.");
        }
    }

    /**
     * 표시용 파일명.
     *
     * <p>경로 구분자와 제어문자를 걷어낸다. 경로로 쓰지는 않지만, 이 값은 관리자
     * 화면에 그대로 그려진다 — 거기서 줄바꿈이나 경로처럼 보이는 문자가 섞이면
     * 목록이 깨지고, 나중에 누군가 이 값을 경로에 쓰는 실수를 할 여지도 줄인다.
     */
    private String safeDisplayName(String raw) {
        if (raw == null || raw.isBlank()) return "image";
        String cleaned = raw.replaceAll("[\\p{Cntrl}/\\\\]", "").trim();
        if (cleaned.isBlank()) return "image";
        return cleaned.length() > 120 ? cleaned.substring(0, 120) : cleaned;
    }
}
