package com.leoneferito.media;

import java.util.Arrays;

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
 */
public enum ImageFormat {
    PNG("image/png", ".png"),
    JPEG("image/jpeg", ".jpg"),
    WEBP("image/webp", ".webp");

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

    /** 판별에 필요한 최소 바이트 수. WEBP 가 12바이트로 가장 길다. */
    public static final int HEADER_BYTES = 12;

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
        return null;
    }

    private static boolean startsWith(byte[] data, byte[] prefix) {
        if (data.length < prefix.length) {
            return false;
        }
        return Arrays.equals(data, 0, prefix.length, prefix, 0, prefix.length);
    }
}
