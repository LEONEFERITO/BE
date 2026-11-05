package com.leoneferito.media;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** JPEG 촬영 정보 지우기(2026-11-04) — 위치 · 기기 정보 조각만 빠지고, 사진은 그대로 열린다. */
class JpegMetadataTest {

    private static byte[] segment(int marker, byte[] body) {
        int len = body.length + 2;
        byte[] s = new byte[len + 2];
        s[0] = (byte) 0xFF;
        s[1] = (byte) marker;
        s[2] = (byte) (len >> 8);
        s[3] = (byte) len;
        System.arraycopy(body, 0, s, 4, body.length);
        return s;
    }

    /** 진짜 JPEG 의 SOI 바로 뒤에 EXIF(APP1) · ICC(APP2) 조각을 끼운다 */
    private static byte[] jpegWithExif() throws Exception {
        ByteArrayOutputStream img = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(8, 6, BufferedImage.TYPE_INT_RGB), "jpg", img);
        byte[] plain = img.toByteArray();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(plain, 0, 2);
        out.write(segment(0xE1, "Exif\0\0GPS 37.24 127.05 iPhone".getBytes(StandardCharsets.ISO_8859_1)));
        out.write(segment(0xFE, "COMMENT keep".getBytes(StandardCharsets.ISO_8859_1)));
        out.write(plain, 2, plain.length - 2);
        return out.toByteArray();
    }

    @Test
    @DisplayName("EXIF(APP1) 는 빠지고 다른 조각(주석 등)은 남으며, 사진은 그대로 열린다")
    void stripsExifKeepsImage() throws Exception {
        byte[] in = jpegWithExif();
        byte[] out = MediaService.stripJpegMetadata(in);

        String text = new String(out, StandardCharsets.ISO_8859_1);
        assertThat(text).doesNotContain("GPS").doesNotContain("iPhone");
        assertThat(text).contains("COMMENT keep");
        BufferedImage read = ImageIO.read(new ByteArrayInputStream(out));
        assertThat(read.getWidth()).isEqualTo(8);
        assertThat(read.getHeight()).isEqualTo(6);
    }

    @Test
    @DisplayName("JPEG 가 아니거나 구조가 이상하면 원본 그대로")
    void leavesOthersAlone() {
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
        assertThat(MediaService.stripJpegMetadata(png)).isSameAs(png);
        byte[] broken = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE1, 0x7F, 0x7F};
        assertThat(MediaService.stripJpegMetadata(broken)).isSameAs(broken);
    }
}
