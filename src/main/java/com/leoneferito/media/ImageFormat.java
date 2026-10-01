package com.leoneferito.media;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;

/**
 * 업로드된 바이트가 **실제로** 어떤 이미지인지 판별한다.
 *
 * 클라이언트가 보낸 Content-Type 과 파일 확장자는 둘 다 사용자 입력이다. 믿으면 안 된다.
 * {@code evil.html} 을 {@code photo.png} 라는 이름에 {@code image/png} 라고 선언해서 올린 뒤,
 * 같은 오리진에서 그 URL 을 열면 브라우저가 HTML 로 해석한다 (저장형 XSS).
 *
 * 그래서 파일 앞머리 바이트(매직넘버)로 형식을 직접 확인하고, 내보낼 때도
 * **여기서 판별한 값** 만 Content-Type 으로 쓴다.
 *
 * SVG 는 목록에 없다. SVG 는 XML 이고 그 안에 스크립트를 담을 수 있다.
 * 매직넘버로도 구분이 어렵고(그냥 텍스트다) 이미지를 표시하는 용도로는 필요하지 않다.
 *
 * <p>HEIC(아이폰 기본 사진)는 받되 <b>상품 사진에는 쓰지 않는다</b> — 크롬 · 엣지가 HEIC 를 화면에 그리지 못한다.
 * 교환·반품 사진처럼 "증거로 받아 두는" 곳에서만 받는다 ({@link #EVIDENCE}).
 */
public enum ImageFormat {
    PNG("image/png", ".png"),
    JPEG("image/jpeg", ".jpg"),
    WEBP("image/webp", ".webp"),
    GIF("image/gif", ".gif"),
    AVIF("image/avif", ".avif"),
    BMP("image/bmp", ".bmp"),
    HEIC("image/heic", ".heic");

    /** 화면에 그대로 그릴 수 있는 형식 — 상품 사진 · 차트 등 손님에게 보이는 이미지. */
    public static final Set<ImageFormat> WEB = EnumSet.of(PNG, JPEG, WEBP, GIF, AVIF, BMP);

    /** 증거로 받아 두는 사진(교환·반품) — 아이폰 HEIC 까지. 화면이 못 그리면 원본 받기로 보인다. */
    public static final Set<ImageFormat> EVIDENCE = EnumSet.of(PNG, JPEG, WEBP, GIF, AVIF, BMP, HEIC);

    private final String contentType;
    private final String extension;

    ImageFormat(String contentType, String extension) {
        this.contentType = contentType;
        this.extension = extension;
    }

    public String contentType() {
        return contentType;
    }

    public String extension() {
        return extension;
    }

    /** 판별에 필요한 최소 바이트 수. HEIC/AVIF 는 ftyp 상자의 호환 브랜드까지 본다. */
    public static final int HEADER_BYTES = 64;

    private static final byte[] PNG_SIG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
    private static final byte[] JPEG_SIG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};

    /**
     * 앞머리 바이트로 형식을 판별한다. 아는 형식이 아니면 {@code null}.
     *
     * @param head 파일의 처음 {@value #HEADER_BYTES} 바이트 (그보다 짧아도 안전하게 동작한다)
     */
    public static ImageFormat sniff(byte[] head) {
        if (head == null) {
            return null;
        }
        if (startsWith(head, PNG_SIG)) {
            return PNG;
        }
        if (startsWith(head, JPEG_SIG)) {
            return JPEG;
        }
        // WebP 는 RIFF 컨테이너다: "RIFF" + 길이 4바이트 + "WEBP"
        if (head.length >= 12
                && startsWith(head, new byte[] {'R', 'I', 'F', 'F'})
                && Arrays.equals(head, 8, 12, new byte[] {'W', 'E', 'B', 'P'}, 0, 4)) {
            return WEBP;
        }
        if (startsWith(head, "GIF87a".getBytes(StandardCharsets.US_ASCII))
                || startsWith(head, "GIF89a".getBytes(StandardCharsets.US_ASCII))) {
            return GIF;
        }
        // BMP: "BM" + 파일 크기(4) + 예약(4) + 픽셀 시작 위치(4) + 헤더 크기(4) — 헤더 크기까지 맞아야 BMP 로 본다.
        // "BM" 두 글자만 보면 그 글자로 시작하는 아무 텍스트나 통과한다.
        if (head.length >= 18 && head[0] == 'B' && head[1] == 'M') {
            int dib = (head[14] & 0xff) | (head[15] & 0xff) << 8 | (head[16] & 0xff) << 16 | (head[17] & 0xff) << 24;
            if (dib == 12 || dib == 40 || dib == 52 || dib == 56 || dib == 64 || dib == 108 || dib == 124) {
                return BMP;
            }
        }
        return isoMedia(head);
    }

    /**
     * HEIC · AVIF 는 ISO 미디어 상자 구조다: 길이(4) + "ftyp" + 주 브랜드(4) + 버전(4) + 호환 브랜드(4 × n).
     * 주 브랜드가 mif1 처럼 둘 다 쓰는 값이면 호환 브랜드 목록에서 avif 를 찾아 가른다.
     */
    private static ImageFormat isoMedia(byte[] head) {
        if (head.length < 16 || !Arrays.equals(head, 4, 8, new byte[] {'f', 't', 'y', 'p'}, 0, 4)) {
            return null;
        }
        int boxSize = (head[0] & 0xff) << 24 | (head[1] & 0xff) << 16 | (head[2] & 0xff) << 8 | (head[3] & 0xff);
        int end = Math.min(Math.max(boxSize, 16), head.length);
        String major = new String(head, 8, 4, StandardCharsets.US_ASCII);
        boolean avif = major.equals("avif") || major.equals("avis");
        boolean heic = Set.of("heic", "heix", "hevc", "hevx", "heim", "heis").contains(major);
        if (!avif && !heic && (major.equals("mif1") || major.equals("msf1"))) {
            for (int i = 16; i + 4 <= end; i += 4) {
                String brand = new String(head, i, 4, StandardCharsets.US_ASCII);
                if (brand.equals("avif") || brand.equals("avis")) {
                    avif = true;
                } else if (brand.startsWith("hei") || brand.startsWith("hev")) {
                    heic = true;
                }
            }
            if (!avif) {
                heic = true; // mif1 인데 avif 가 아니면 HEIF 이미지다
            }
        }
        return avif ? AVIF : heic ? HEIC : null;
    }

    private static boolean startsWith(byte[] data, byte[] prefix) {
        if (data.length < prefix.length) {
            return false;
        }
        return Arrays.equals(data, 0, prefix.length, prefix, 0, prefix.length);
    }
}
