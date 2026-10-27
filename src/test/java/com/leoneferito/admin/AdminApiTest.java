package com.leoneferito.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.leoneferito.TestcontainersConfiguration;
import com.leoneferito.product.ProductRepository;
import java.io.ByteArrayOutputStream;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import com.leoneferito.product.FrontRebuildTrigger;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

/**
 * 관리자 API 검증.
 *
 * <p>여기서 가장 중요한 것은 <b>열려 있으면 안 되는 것이 닫혀 있는가</b> 다.
 * 상품 등록 화면이 동작하는지는 손으로도 보이지만, 로그인한 일반 회원이
 * 상품을 고칠 수 있는지는 아무도 확인하지 않는다. 그게 여기 있는 이유다.
 *
 * <p>업로드 저장 경로를 임시 폴더로 돌린다. 테스트가 저장소 폴더에 쓰레기를 남기면
 * 다음 실행이 이전 실행의 흔적 위에서 돌게 된다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = "app.media.storage-dir=${java.io.tmpdir}/lf-media-test")
@Transactional
@RecordApplicationEvents
class AdminApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ProductRepository products;

    @Autowired
    private ApplicationEvents events;

    private long rebuilds() {
        return events.stream(FrontRebuildTrigger.CatalogChanged.class).count();
    }

    /*
     * 관리자/회원으로 요청한다. 인증 자체는 AuthApiTest 가 검증하므로 여기선 통과시켜 둔다.
     *
     * 제네릭인 이유: multipart 빌더는 MockHttpServletRequestBuilder 의 하위 타입이 아니라
     * 형제 타입이다. 상위 타입으로 받으면 multipart 요청에 쓸 수 없다.
     */
    private <T extends org.springframework.test.web.servlet.request.ConfigurableSmartRequestBuilder<T>>
            T asAdmin(T b) {
        return b.with(csrf()).with(user("ops").roles("ADMIN"));
    }

    private <T extends org.springframework.test.web.servlet.request.ConfigurableSmartRequestBuilder<T>>
            T asMember(T b) {
        return b.with(csrf()).with(user("guest").roles("MEMBER"));
    }

    /** 진짜 PNG 바이트. 매직바이트 판별을 실제로 통과해야 한다. */
    /** 아이폰 HEIC 앞머리 — ftyp 상자(heic, 호환 mif1 · heic) + 나머지. 형식 판별만 보는 테스트용이다. */
    /** ftyp 상자로 시작하는 ISO 미디어 앞머리 + 채움. 주 브랜드만 바꿔 MP4 · MOV 를 흉내 낸다. */
    static byte[] isoMedia(String major, int size) {
        byte[] head = {0, 0, 0, 0x20, 'f', 't', 'y', 'p', 0, 0, 0, 0, 0, 0, 2, 0,
                'i', 's', 'o', 'm', 'i', 's', 'o', '2', 'a', 'v', 'c', '1', 'm', 'p', '4', '1'};
        byte[] brand = major.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        System.arraycopy(brand, 0, head, 8, 4);
        byte[] all = new byte[size];
        System.arraycopy(head, 0, all, 0, head.length);
        for (int i = head.length; i < size; i++) {
            all[i] = (byte) i;
        }
        return all;
    }

    /** EBML 헤더(DocType webm 또는 matroska) + 채움 */
    static byte[] ebml(String docType) {
        byte[] doc = docType.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        var out = new java.io.ByteArrayOutputStream();
        out.writeBytes(new byte[] {0x1A, 0x45, (byte) 0xDF, (byte) 0xA3, (byte) 0x9F, 0x42, (byte) 0x86, (byte) 0x81, 0x01,
                0x42, (byte) 0xF7, (byte) 0x81, 0x01, 0x42, (byte) 0x82, (byte) (0x80 | doc.length)});
        out.writeBytes(doc);
        out.writeBytes(new byte[300]);
        return out.toByteArray();
    }

    static byte[] heic() {
        byte[] head = {0, 0, 0, 0x18, 'f', 't', 'y', 'p', 'h', 'e', 'i', 'c', 0, 0, 0, 0,
                'm', 'i', 'f', '1', 'h', 'e', 'i', 'c'};
        byte[] all = new byte[256];
        System.arraycopy(head, 0, all, 0, head.length);
        return all;
    }

    private byte[] realPng(int w, int h) throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    private String saveBody(String slug) {
        return """
                {
                  "slug": "%s",
                  "name": "브라운 셔츠",
                  "category": "SHIRT",
                  "line": "FERITO",
                  "priceKrw": 290000,
                  "leadTimeDays": 14,
                  "fabric": "면 100%%",
                  "care": "드라이클리닝",
                  "color": "브라운",
                  "manufacturer": "레오네페리토",
                  "countryOfOrigin": "대한민국",
                  "manufacturedOn": "2026년 9월",
                  "skus": [
                    { "size": "95", "measurements": [
                        { "part": "SHOULDER", "valueCm": 45.0, "toleranceCm": 1.0 } ] },
                    { "size": "100", "stock": 3 }
                  ]
                }
                """.formatted(slug);
    }

    @Nested
    @DisplayName("권한")
    class Authorization {

        @Test
        @DisplayName("로그인하지 않으면 상품을 등록할 수 없다")
        void anonymousCannotCreate() throws Exception {
            mockMvc.perform(post("/api/admin/products").with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(saveBody("anon-attempt")))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("일반 회원은 상품을 등록할 수 없다 — 로그인만으로는 부족하다")
        void memberCannotCreate() throws Exception {
            mockMvc.perform(asMember(post("/api/admin/products"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(saveBody("member-attempt")))
                    .andExpect(status().isForbidden());

            assertThat(products.existsBySlug("member-attempt")).isFalse();
        }

        @Test
        @DisplayName("일반 회원은 이미지도 올릴 수 없다")
        void memberCannotUpload() throws Exception {
            mockMvc.perform(asMember(multipart("/api/admin/media")
                            .file(new MockMultipartFile("file", "a.png", "image/png", realPng(10, 10)))))
                    .andExpect(status().isForbidden());
        }
    }

    @Nested
    @DisplayName("이미지 업로드")
    class Upload {

        @Test
        @DisplayName("GIF · BMP 는 상품 사진으로 받고, HEIC(아이폰)는 손님 화면이 못 그려서 받지 않는다")
        void moreFormats() throws Exception {
            var gif = new java.io.ByteArrayOutputStream();
            ImageIO.write(new BufferedImage(8, 6, BufferedImage.TYPE_INT_RGB), "gif", gif);
            mockMvc.perform(asAdmin(multipart("/api/admin/media")
                            .file(new MockMultipartFile("file", "a.gif", "image/gif", gif.toByteArray()))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.width").value(8));
            var bmp = new java.io.ByteArrayOutputStream();
            ImageIO.write(new BufferedImage(5, 4, BufferedImage.TYPE_INT_RGB), "bmp", bmp);
            mockMvc.perform(asAdmin(multipart("/api/admin/media")
                            .file(new MockMultipartFile("file", "a.bmp", "image/bmp", bmp.toByteArray()))))
                    .andExpect(status().isOk());
            mockMvc.perform(asAdmin(multipart("/api/admin/media")
                            .file(new MockMultipartFile("file", "IMG_0001.HEIC", "image/heic", heic()))))
                    .andExpect(status().isBadRequest());
            // "BM" 으로 시작하는 텍스트는 BMP 가 아니다
            mockMvc.perform(asAdmin(multipart("/api/admin/media")
                            .file(new MockMultipartFile("file", "x.bmp", "image/bmp",
                                    "BM<html><script>alert(1)</script>".getBytes(java.nio.charset.StandardCharsets.UTF_8)))))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("올리면 바로 쓸 수 있는 주소가 함께 온다")
        void uploadReturnsUsableUrl() throws Exception {
            MvcResult result = mockMvc.perform(asAdmin(multipart("/api/admin/media")
                            .file(new MockMultipartFile("file", "shot.png", "image/png", realPng(120, 160)))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").exists())
                    .andExpect(jsonPath("$.url").exists())
                    .andExpect(jsonPath("$.width").value(120))
                    .andExpect(jsonPath("$.height").value(160))
                    .andReturn();

            String url = com.jayway.jsonpath.JsonPath.read(
                    result.getResponse().getContentAsString(), "$.url");

            // 받은 주소로 실제로 이미지가 내려와야 한다. 주소만 주고 안 열리면 소용없다.
            mockMvc.perform(get(url))
                    .andExpect(status().isOk())
                    .andExpect(content().contentType(MediaType.IMAGE_PNG))
                    // 브라우저의 타입 추측을 막는 헤더. 저장형 XSS 의 마지막 통로다.
                    .andExpect(header().string("X-Content-Type-Options", "nosniff"));
        }

        @Test
        @DisplayName("이미지로 위장한 HTML 은 거부된다")
        void disguisedHtmlRejected() throws Exception {
            /*
             * 확장자도 png, 선언 타입도 image/png 다. 둘 다 사용자 입력이라 믿으면 안 된다.
             * 이게 통과하면 같은 오리진에서 그 URL 을 여는 순간 스크립트가 실행된다.
             */
            byte[] html = "<html><script>alert(1)</script></html>".getBytes();

            mockMvc.perform(asAdmin(multipart("/api/admin/media")
                            .file(new MockMultipartFile("file", "evil.png", "image/png", html))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_IMAGE"));
        }

        @Test
        @DisplayName("빈 파일은 거부된다")
        void emptyFileRejected() throws Exception {
            mockMvc.perform(asAdmin(multipart("/api/admin/media")
                            .file(new MockMultipartFile("file", "a.png", "image/png", new byte[0]))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_IMAGE"));
        }

        @Test
        @DisplayName("영상은 영상 주소로만 — MP4 · WebM 은 받고, MOV · mkv · 위장 파일은 거부, 이미지 주소로는 안 된다")
        void videoUpload() throws Exception {
            mockMvc.perform(asAdmin(multipart("/api/admin/media/video")
                            .file(new MockMultipartFile("file", "season.mp4", "video/mp4", isoMedia("isom", 4096)))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.url").value(org.hamcrest.Matchers.endsWith(".mp4")))
                    .andExpect(jsonPath("$.width").doesNotExist());
            mockMvc.perform(asAdmin(multipart("/api/admin/media/video")
                            .file(new MockMultipartFile("file", "phone.mp4", "video/mp4", isoMedia("mp42", 512)))))
                    .andExpect(status().isOk());
            mockMvc.perform(asAdmin(multipart("/api/admin/media/video")
                            .file(new MockMultipartFile("file", "loop.webm", "video/webm", ebml("webm")))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.url").value(org.hamcrest.Matchers.endsWith(".webm")));

            // 아이폰 MOV · mkv · 사진 · 위장 HTML — 영상 주소에서 거부
            for (MockMultipartFile bad : new MockMultipartFile[] {
                    new MockMultipartFile("file", "IMG_0001.MOV", "video/quicktime", isoMedia("qt  ", 512)),
                    new MockMultipartFile("file", "a.mkv", "video/x-matroska", ebml("matroska")),
                    new MockMultipartFile("file", "a.png", "image/png", realPng(10, 10)),
                    new MockMultipartFile("file", "evil.mp4", "video/mp4", "<html><script>alert(1)</script>".getBytes()),
                    new MockMultipartFile("file", "empty.mp4", "video/mp4", new byte[0])}) {
                mockMvc.perform(asAdmin(multipart("/api/admin/media/video").file(bad)))
                        .andExpect(status().isBadRequest())
                        .andExpect(jsonPath("$.code").value("INVALID_IMAGE"));
            }
            // 영상은 이미지 주소로 올릴 수 없다 — 상품 사진 자리에 영상이 걸리지 않게
            mockMvc.perform(asAdmin(multipart("/api/admin/media")
                            .file(new MockMultipartFile("file", "season.mp4", "video/mp4", isoMedia("isom", 512)))))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("일반 회원은 영상을 올릴 수 없다")
        void memberCannotUploadVideo() throws Exception {
            mockMvc.perform(asMember(multipart("/api/admin/media/video")
                            .file(new MockMultipartFile("file", "a.mp4", "video/mp4", isoMedia("isom", 512)))))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("영상은 나눠 받을 수 있다(Range) — 사파리 · 아이폰은 이게 안 되면 재생하지 않는다")
        void videoServedInRanges() throws Exception {
            byte[] bytes = isoMedia("isom", 4096);
            String url = com.jayway.jsonpath.JsonPath.read(mockMvc.perform(asAdmin(multipart("/api/admin/media/video")
                                    .file(new MockMultipartFile("file", "season.mp4", "video/mp4", bytes))))
                            .andExpect(status().isOk())
                            .andReturn().getResponse().getContentAsString(), "$.url");

            mockMvc.perform(get(url))
                    .andExpect(status().isOk())
                    .andExpect(content().contentType("video/mp4"))
                    .andExpect(header().string("Accept-Ranges", "bytes"))
                    .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                    .andExpect(content().bytes(bytes));

            MvcResult part = mockMvc.perform(get(url).header("Range", "bytes=100-199"))
                    .andExpect(status().isPartialContent())
                    .andExpect(header().string("Content-Range", "bytes 100-199/4096"))
                    .andExpect(header().string("Content-Length", "100"))
                    .andReturn();
            org.assertj.core.api.Assertions.assertThat(part.getResponse().getContentAsByteArray())
                    .isEqualTo(java.util.Arrays.copyOfRange(bytes, 100, 200));
        }

        @Test
        @DisplayName("없는 이미지 주소는 404 다")
        void unknownMediaKeyIsNotFound() throws Exception {
            mockMvc.perform(get("/media/2026-01-01/does-not-exist.png"))
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("상품 등록과 공개")
    class Lifecycle {

        @Test
        @DisplayName("사이즈별 재고 — 적은 사이즈만 센다(비우면 세지 않음), 음수는 400")
        void skuStock() throws Exception {
            String id = createProduct("stock-shirt");
            mockMvc.perform(asAdmin(get("/api/admin/products/" + id)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.skus[0].size").value("95"))
                    .andExpect(jsonPath("$.skus[0].stock").doesNotExist())
                    .andExpect(jsonPath("$.skus[1].stock").value(3));
            mockMvc.perform(asAdmin(post("/api/admin/products"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(saveBody("minus-stock").replace("\"stock\": 3", "\"stock\": -1")))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("등록하면 초안이다 — 저장이 곧 공개가 아니다")
        void createdAsDraft() throws Exception {
            mockMvc.perform(asAdmin(post("/api/admin/products"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(saveBody("new-shirt")))
                    .andExpect(status().isCreated())
                    .andExpect(header().exists("Location"));

            // 공개 목록에는 아직 없어야 한다.
            mockMvc.perform(get("/api/products/new-shirt"))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("초안을 고치면 손님 화면을 다시 만들지 않고, 공개·비공개하면 다시 만든다")
        void rebuildOnlyWhenCustomersSeeIt() throws Exception {
            String id = createProduct("rebuild-check");
            mockMvc.perform(asAdmin(put("/api/admin/products/" + id))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(saveBody("rebuild-check")))
                    .andExpect(status().isNoContent());
            org.assertj.core.api.Assertions.assertThat(rebuilds()).isZero();

            attachImageAndPublish(id, "rebuild-check", uploadImage());
            // 이미지 붙이는 저장(초안) 0 + 공개 1
            org.assertj.core.api.Assertions.assertThat(rebuilds()).isEqualTo(1);

            mockMvc.perform(asAdmin(post("/api/admin/products/" + id + "/unpublish")))
                    .andExpect(status().isNoContent());
            org.assertj.core.api.Assertions.assertThat(rebuilds()).isEqualTo(2);
        }

        @Test
        @DisplayName("상품정보제공고시가 비면 공개가 거부되고, 목록이 그 이유를 알려준다")
        void cannotPublishWithoutNotice() throws Exception {
            String mediaId = uploadImage();
            String id = createProduct("no-notice");
            mockMvc.perform(asAdmin(put("/api/admin/products/" + id))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "slug": "no-notice",
                                      "name": "고시 없는 셔츠",
                                      "category": "SHIRT",
                                      "line": "LEONE",
                                      "priceKrw": 100000,
                                      "leadTimeDays": 10,
                                      "images": [ { "mediaId": "%s", "kind": "MAIN", "alt": "대표" } ]
                                    }
                                    """.formatted(mediaId)))
                    .andExpect(status().isNoContent());

            mockMvc.perform(asAdmin(post("/api/admin/products/" + id + "/publish")))
                    .andExpect(status().isConflict());
            mockMvc.perform(asAdmin(get("/api/admin/products/" + id)))
                    .andExpect(jsonPath("$.missingForPublish[0]").value("notice"));
        }

        @Test
        @DisplayName("대표 이미지가 없으면 공개가 거부된다")
        void cannotPublishWithoutMainImage() throws Exception {
            String id = createProduct("no-image-yet");

            mockMvc.perform(asAdmin(post("/api/admin/products/" + id + "/publish")))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("NOT_READY"));
        }

        @Test
        @DisplayName("이미지를 붙이고 나면 공개되고 손님에게 보인다")
        void publishAfterImage() throws Exception {
            String id = createProduct("ready-shirt");
            String mediaId = uploadImage();

            mockMvc.perform(asAdmin(put("/api/admin/products/" + id))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "slug": "ready-shirt",
                                      "name": "브라운 셔츠",
                                      "category": "SHIRT",
                                      "line": "FERITO",
                                      "priceKrw": 290000,
                                      "leadTimeDays": 14,
                                      "fabric": "면 100%%",
                                      "care": "드라이클리닝",
                                      "color": "브라운",
                                      "manufacturer": "레오네페리토",
                                      "countryOfOrigin": "대한민국",
                                      "manufacturedOn": "2026년 9월",
                                      "images": [
                                        { "mediaId": "%s", "kind": "MAIN", "alt": "측면 컷" }
                                      ],
                                      "skus": [ { "size": "100" } ]
                                    }
                                    """.formatted(mediaId)))
                    .andExpect(status().isNoContent());

            mockMvc.perform(asAdmin(post("/api/admin/products/" + id + "/publish")))
                    .andExpect(status().isNoContent());

            mockMvc.perform(get("/api/products/ready-shirt"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.images[0].alt").value("측면 컷"))
                    .andExpect(jsonPath("$.leadTimeDays").value(14));
        }

        @Test
        @DisplayName("비공개로 내리면 손님에게서 사라진다")
        void unpublishHidesIt() throws Exception {
            String id = createProduct("temporary");
            String mediaId = uploadImage();
            attachImageAndPublish(id, "temporary", mediaId);

            mockMvc.perform(asAdmin(post("/api/admin/products/" + id + "/unpublish")))
                    .andExpect(status().isNoContent());

            mockMvc.perform(get("/api/products/temporary"))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("같은 slug 는 두 번 쓸 수 없다")
        void duplicateSlugRejected() throws Exception {
            createProduct("taken");

            mockMvc.perform(asAdmin(post("/api/admin/products"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(saveBody("taken")))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("DUPLICATE_SLUG"));
        }

        @Test
        @DisplayName("slug 는 수정할 수 없다 — 걸린 링크가 전부 죽는다")
        void slugIsImmutable() throws Exception {
            String id = createProduct("original-slug");

            mockMvc.perform(asAdmin(put("/api/admin/products/" + id))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(saveBody("changed-slug")))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("SLUG_IMMUTABLE"));
        }

        @Test
        @DisplayName("없는 이미지 id 를 붙이면 500 이 아니라 404 다")
        void unknownMediaIdIsNotFound() throws Exception {
            String id = createProduct("bad-media");

            mockMvc.perform(asAdmin(put("/api/admin/products/" + id))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "slug": "bad-media",
                                      "category": "SHIRT",
                                      "line": "LEONE",
                                      "images": [
                                        { "mediaId": "00000000-0000-0000-0000-000000000000",
                                          "kind": "MAIN", "alt": "없음" }
                                      ]
                                    }
                                    """))
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("상세 사이즈 차트")
    class SizeChartUpload {

        @Test
        @DisplayName("차트를 붙이면 상세 응답에 주소와 설명이 나온다")
        void chartAppearsInDetail() throws Exception {
            String id = createProduct("with-chart");
            String mediaId = uploadImage();
            String chartId = uploadImage();

            mockMvc.perform(asAdmin(put("/api/admin/products/" + id))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "slug": "with-chart",
                                      "name": "차트 있는 상품",
                                      "category": "SHIRT",
                                      "line": "LEONE",
                                      "priceKrw": 100000,
                                      "leadTimeDays": 10,
                                      "fabric": "면 100%%",
                                      "care": "드라이클리닝",
                                      "color": "브라운",
                                      "manufacturer": "레오네페리토",
                                      "countryOfOrigin": "대한민국",
                                      "manufacturedOn": "2026년 9월",
                                      "sizeChartMediaId": "%s",
                                      "sizeChartAlt": "95~110 사이즈의 어깨·가슴·소매·총장 실측표",
                                      "images": [ { "mediaId": "%s", "kind": "MAIN", "alt": "대표" } ]
                                    }
                                    """.formatted(chartId, mediaId)))
                    .andExpect(status().isNoContent());

            mockMvc.perform(asAdmin(post("/api/admin/products/" + id + "/publish")))
                    .andExpect(status().isNoContent());

            mockMvc.perform(get("/api/products/with-chart"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.sizeChart.url").exists())
                    .andExpect(jsonPath("$.sizeChart.alt")
                            .value("95~110 사이즈의 어깨·가슴·소매·총장 실측표"))
                    // 자리를 미리 잡으려면 크기가 있어야 한다. 없으면 구매 버튼이 밀린다.
                    .andExpect(jsonPath("$.sizeChart.width").value(80))
                    .andExpect(jsonPath("$.sizeChart.height").value(100));
        }

        @Test
        @DisplayName("차트만 있고 설명이 없으면 거부된다 — 스크린리더에겐 그게 전부다")
        void chartWithoutAltRejected() throws Exception {
            String id = createProduct("chart-no-alt");
            String chartId = uploadImage();

            mockMvc.perform(asAdmin(put("/api/admin/products/" + id))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "slug": "chart-no-alt",
                                      "category": "SHIRT",
                                      "line": "LEONE",
                                      "sizeChartMediaId": "%s"
                                    }
                                    """.formatted(chartId)))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("차트가 없으면 상세에서 null 이다 — 화면이 그 자리를 비운다")
        void noChartIsNull() throws Exception {
            String id = createProduct("no-chart");
            String mediaId = uploadImage();
            attachImageAndPublish(id, "no-chart", mediaId);

            mockMvc.perform(get("/api/products/no-chart"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.sizeChart").value(
                            org.hamcrest.Matchers.nullValue()));
        }
    }

    @Nested
    @DisplayName("인스타그램 링크")
    class InstagramLink {

        private String bodyWith(String slug, String mediaId, String instagramJson) {
            return """
                    {
                      "slug": "%s",
                      "name": "상품",
                      "category": "SHIRT",
                      "line": "LEONE",
                      "priceKrw": 100000,
                      "leadTimeDays": 10,
                      "fabric": "면 100%%",
                      "care": "드라이클리닝",
                      "color": "브라운",
                      "manufacturer": "레오네페리토",
                      "countryOfOrigin": "대한민국",
                      "manufacturedOn": "2026년 9월",
                      "instagramUrl": %s,
                      "images": [ { "mediaId": "%s", "kind": "MAIN", "alt": "대표" } ]
                    }
                    """.formatted(slug, instagramJson, mediaId);
        }

        @Test
        @DisplayName("넣은 주소가 상세 응답에 나온다")
        void linkAppearsInDetail() throws Exception {
            String id = createProduct("with-insta");
            String mediaId = uploadImage();

            mockMvc.perform(asAdmin(put("/api/admin/products/" + id))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(bodyWith("with-insta", mediaId,
                                    "\"https://www.instagram.com/p/Cx1abc_Z-9/\"")))
                    .andExpect(status().isNoContent());
            mockMvc.perform(asAdmin(post("/api/admin/products/" + id + "/publish")))
                    .andExpect(status().isNoContent());

            mockMvc.perform(get("/api/products/with-insta"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.instagramUrl").value("https://www.instagram.com/p/Cx1abc_Z-9/"));
        }

        @Test
        @DisplayName("없으면 null 이다 — 화면이 버튼을 숨긴다")
        void absentIsNull() throws Exception {
            String id = createProduct("no-insta");
            attachImageAndPublish(id, "no-insta", uploadImage());

            mockMvc.perform(get("/api/products/no-insta"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.instagramUrl").value(
                            org.hamcrest.Matchers.nullValue()));
        }

        @Test
        @DisplayName("인스타그램이 아닌 주소는 거부된다 — 손님이 누르는 링크다")
        void otherHostsRejected() throws Exception {
            String id = createProduct("bad-insta");
            String mediaId = uploadImage();

            for (String bad : java.util.List.of(
                    "\"javascript:alert(1)\"",
                    // 주소 중간에 instagram.com 을 끼워 넣은 위장
                    "\"https://evil.example.com/instagram.com/p/1\"",
                    "\"https://instagram.com.evil.example/p/1\"",
                    // http 는 받지 않는다
                    "\"http://www.instagram.com/p/1\"")) {
                mockMvc.perform(asAdmin(put("/api/admin/products/" + id))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(bodyWith("bad-insta", mediaId, bad)))
                        .andExpect(status().isBadRequest());
            }
        }
    }

    @Nested
    @DisplayName("관리자 조회")
    class AdminRead {

        @Test
        @DisplayName("목록에는 초안도 나온다 — 공개 목록과 반대다")
        void listIncludesDrafts() throws Exception {
            createProduct("draft-in-list");

            var row = rowOf("draft-in-list");
            assertThat(row.get("status")).isEqualTo("DRAFT");
        }

        @Test
        @DisplayName("목록이 공개에 모자란 항목을 알려준다")
        void listShowsWhatIsMissing() throws Exception {
            createProduct("missing-image"); // 이름·가격·제작 기간은 있고 이미지만 없다

            var row = rowOf("missing-image");
            assertThat(row.get("missingForPublish")).isEqualTo(List.of("mainImage"));
        }

        @Test
        @DisplayName("수정 화면은 받은 그대로 다시 저장할 수 있다")
        void editRoundTrips() throws Exception {
            String id = createProduct("round-trip");
            String mediaId = uploadImage();
            attachImageAndPublish(id, "round-trip", mediaId);

            String edit = mockMvc.perform(asAdmin(get("/api/admin/products/" + id)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("PUBLISHED"))
                    .andExpect(jsonPath("$.images[0].mediaId").value(mediaId))
                    .andExpect(jsonPath("$.images[0].url").exists())
                    .andReturn().getResponse().getContentAsString();

            /*
             * 화면이 하는 일 그대로다: 받은 JSON 을 손대지 않고 PUT.
             * 여기서 400 이 나면 응답과 요청의 모양이 어긋난 것이고,
             * 화면은 저장할 때마다 값을 잃는다.
             */
            mockMvc.perform(asAdmin(put("/api/admin/products/" + id))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(edit))
                    .andExpect(status().isNoContent());

            // 저장해도 공개 상태와 사진은 그대로다.
            mockMvc.perform(get("/api/products/round-trip"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.images[0].alt").value("대표"));
        }

        @Test
        @DisplayName("일반 회원은 관리자 목록을 볼 수 없다 — 초안이 새면 안 된다")
        void memberCannotList() throws Exception {
            mockMvc.perform(asMember(get("/api/admin/products")))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("없는 상품은 404 다")
        void unknownIdIsNotFound() throws Exception {
            mockMvc.perform(asAdmin(get("/api/admin/products/00000000-0000-0000-0000-000000000000")))
                    .andExpect(status().isNotFound());
        }

        private Map<String, Object> rowOf(String slug) throws Exception {
            String json = mockMvc.perform(asAdmin(get("/api/admin/products")))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            List<Map<String, Object>> rows =
                    com.jayway.jsonpath.JsonPath.read(json, "$[?(@.slug == '" + slug + "')]");
            assertThat(rows).hasSize(1);
            return rows.get(0);
        }
    }

    @Nested
    @DisplayName("입력 검증")
    class Validation {

        @Test
        @DisplayName("주소(slug)에 한글이나 공백은 쓸 수 없다")
        void slugMustBeUrlSafe() throws Exception {
            mockMvc.perform(asAdmin(post("/api/admin/products"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(saveBody("브라운 셔츠")))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }

        @Test
        @DisplayName("관리자에게는 어느 칸이 틀렸는지 알려준다 — 공개 API 와 반대다")
        void adminGetsFieldErrors() throws Exception {
            /*
             * 공개 API 는 필드명을 숨긴다(스키마 노출). 관리자는 신뢰 경계 안이고
             * 입력을 고쳐야 하는 당사자라 어느 칸인지 알아야 한다.
             */
            mockMvc.perform(asAdmin(post("/api/admin/products"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    { "slug": "no-category", "line": "LEONE" }
                                    """))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors").exists());
        }

        @Test
        @DisplayName("이름이 상한을 넘으면 거부된다 — 화면이 아니라 서버가 진짜 기준이다")
        void nameLengthEnforcedOnServer() throws Exception {
            String tooLong = "가".repeat(41); // NAME_MAX = 40

            mockMvc.perform(asAdmin(post("/api/admin/products"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "slug": "long-name",
                                      "name": "%s",
                                      "category": "SHIRT",
                                      "line": "LEONE"
                                    }
                                    """.formatted(tooLong)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }

        @Test
        @DisplayName("제작 기간이 터무니없으면 거부된다 — 결제 화면에 그대로 표시된다")
        void leadTimeBounded() throws Exception {
            mockMvc.perform(asAdmin(post("/api/admin/products"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "slug": "silly-lead-time",
                                      "category": "SHIRT",
                                      "line": "LEONE",
                                      "leadTimeDays": 9999
                                    }
                                    """))
                    .andExpect(status().isBadRequest());
        }
    }

    @Nested
    @DisplayName("상세 이미지 (STORY)")
    class StoryImages {

        private String body(String slug, String imagesJson) {
            return """
                    {
                      "slug": "%s", "name": "상품", "category": "SHIRT", "line": "LEONE",
                      "priceKrw": 100000, "leadTimeDays": 10, "fabric": "면 100%%", "care": "드라이클리닝",
                      "color": "브라운", "manufacturer": "레오네페리토", "countryOfOrigin": "대한민국",
                      "manufacturedOn": "2026년 9월", "skus": [ { "size": "95" } ],
                      "images": %s
                    }
                    """.formatted(slug, imagesJson);
        }

        @Test
        @DisplayName("여러 장을 순서대로 올리고, 손님 상세에 그 순서 · 크기와 함께 나간다")
        void storyInOrder() throws Exception {
            String id = createProduct("with-story");
            String main = uploadImage();
            String first = uploadImage();
            String second = uploadImage();
            mockMvc.perform(asAdmin(put("/api/admin/products/" + id))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body("with-story", """
                                    [ { "mediaId": "%s", "kind": "MAIN", "alt": "대표", "sortOrder": 0 },
                                      { "mediaId": "%s", "kind": "STORY", "alt": "상세 1", "sortOrder": 100 },
                                      { "mediaId": "%s", "kind": "STORY", "alt": "상세 2", "sortOrder": 101 } ]
                                    """.formatted(main, first, second))))
                    .andExpect(status().isNoContent());
            mockMvc.perform(asAdmin(post("/api/admin/products/" + id + "/publish"))).andExpect(status().isNoContent());

            mockMvc.perform(get("/api/products/with-story"))
                    .andExpect(jsonPath("$.images[?(@.kind == 'STORY')].alt")
                            .value(org.hamcrest.Matchers.contains("상세 1", "상세 2")))
                    .andExpect(jsonPath("$.images[1].width").value(80))
                    .andExpect(jsonPath("$.images[1].height").value(100));
        }

        @Test
        @DisplayName("메인 사진은 종류마다 한 장 — 대표 두 장은 거부된다")
        void oneMainOnly() throws Exception {
            String id = createProduct("two-mains");
            String a = uploadImage();
            String b = uploadImage();
            mockMvc.perform(asAdmin(put("/api/admin/products/" + id))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body("two-mains", """
                                    [ { "mediaId": "%s", "kind": "MAIN", "alt": "a" },
                                      { "mediaId": "%s", "kind": "MAIN", "alt": "b" } ]
                                    """.formatted(a, b))))
                    .andExpect(status().isBadRequest());
        }
    }

    // ── 도우미 ────────────────────────────────────────────────

    private String createProduct(String slug) throws Exception {
        MvcResult r = mockMvc.perform(asAdmin(post("/api/admin/products"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(saveBody(slug)))
                .andExpect(status().isCreated())
                .andReturn();
        return com.jayway.jsonpath.JsonPath.read(r.getResponse().getContentAsString(), "$.id");
    }

    private String uploadImage() throws Exception {
        MvcResult r = mockMvc.perform(asAdmin(multipart("/api/admin/media")
                        .file(new MockMultipartFile("file", "a.png", "image/png", realPng(80, 100)))))
                .andExpect(status().isOk())
                .andReturn();
        return com.jayway.jsonpath.JsonPath.read(r.getResponse().getContentAsString(), "$.id");
    }

    @Nested
    @DisplayName("세부 분류 (V21)")
    class StyleField {

        private String body(String slug, String category, String styleJson, String mediaId) {
            return """
                    {
                      "slug": "%s",
                      "name": "상품",
                      "category": "%s",
                      "line": "LEONE",
                      "style": %s,
                      "priceKrw": 100000,
                      "leadTimeDays": 10,
                      "fabric": "울 100%%",
                      "care": "드라이클리닝",
                      "color": "블랙",
                      "manufacturer": "레오네페리토",
                      "countryOfOrigin": "대한민국",
                      "manufacturedOn": "2026년 10월",
                      "images": %s
                    }
                    """.formatted(slug, category, styleJson,
                    mediaId == null ? "[]" : "[ { \"mediaId\": \"" + mediaId + "\", \"kind\": \"MAIN\", \"alt\": \"대표\" } ]");
        }

        @Test
        @DisplayName("트라우저 핏이 저장되고 관리자 · 손님 응답에 나온다")
        void trousersFitRoundTrips() throws Exception {
            String id = com.jayway.jsonpath.JsonPath.read(mockMvc.perform(asAdmin(post("/api/admin/products"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body("flare-pants", "TROUSERS", "\"FLARE\"", uploadImage())))
                    .andExpect(status().isCreated())
                    .andReturn().getResponse().getContentAsString(), "$.id");

            mockMvc.perform(asAdmin(get("/api/admin/products/" + id)))
                    .andExpect(jsonPath("$.style").value("FLARE"));
            mockMvc.perform(asAdmin(post("/api/admin/products/" + id + "/publish")))
                    .andExpect(status().isNoContent());

            mockMvc.perform(get("/api/products/flare-pants"))
                    .andExpect(jsonPath("$.style").value("FLARE"));
            mockMvc.perform(get("/api/products").param("category", "TROUSERS"))
                    .andExpect(jsonPath("$[?(@.slug == 'flare-pants')].style").value("FLARE"));
        }

        @Test
        @DisplayName("셔츠에 트라우저 핏 · 트라우저에 신발 종류는 거부된다 (400)")
        void wrongPairRejected() throws Exception {
            mockMvc.perform(asAdmin(post("/api/admin/products"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body("shirt-flare", "SHIRT", "\"FLARE\"", null)))
                    .andExpect(status().isBadRequest());
            mockMvc.perform(asAdmin(post("/api/admin/products"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body("pants-oxford", "TROUSERS", "\"OXFORD\"", null)))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("세부 분류가 없는 상품은 null — 기존 상품 그대로")
        void absentIsNull() throws Exception {
            String id = createProduct("plain-shirt");
            mockMvc.perform(asAdmin(get("/api/admin/products/" + id)))
                    .andExpect(jsonPath("$.style").value(org.hamcrest.Matchers.nullValue()));
        }

        @Test
        @DisplayName("수정은 저장된 분류로 본다 — 셔츠 상품에 트라우저로 실어 보낸 핏은 400")
        void updateUsesStoredCategory() throws Exception {
            String id = createProduct("stored-shirt");
            mockMvc.perform(asAdmin(put("/api/admin/products/" + id))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body("stored-shirt", "TROUSERS", "\"REGULAR\"", null)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("STYLE_MISMATCH"));
        }
    }

    @Nested
    @DisplayName("상품 일괄 등록 — 엑셀 CSV (2026-10-16)")
    class BulkImport {

        private org.springframework.test.web.servlet.ResultActions upload(byte[] bytes) throws Exception {
            return mockMvc.perform(asAdmin(multipart("/api/admin/products/import")
                    .file(new MockMultipartFile("file", "products.csv", "text/csv", bytes))));
        }

        @Test
        @DisplayName("양식을 그대로 올리면 예시 두 줄이 초안으로 생긴다 — 한글 분류 · 라인, 쉼표 든 가격, 사이즈 목록, 비운 주소는 자동")
        void templateImports() throws Exception {
            byte[] template = mockMvc.perform(asAdmin(get("/api/admin/products/import/template")))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("leoneferito-products.csv")))
                    .andReturn().getResponse().getContentAsByteArray();
            org.assertj.core.api.Assertions.assertThat(template[0] & 0xff).isEqualTo(0xEF); // 엑셀이 한글을 깨지 않게 BOM

            String res = upload(template).andExpect(status().isOk())
                    .andExpect(jsonPath("$.created.length()").value(2))
                    .andExpect(jsonPath("$.created[0].row").value(2))
                    .andExpect(jsonPath("$.created[1].slug").value("straight-trousers-navy"))
                    .andReturn().getResponse().getContentAsString();
            String shirtId = com.jayway.jsonpath.JsonPath.read(res, "$.created[0].id");
            String shirtSlug = com.jayway.jsonpath.JsonPath.read(res, "$.created[0].slug");
            org.assertj.core.api.Assertions.assertThat(shirtSlug).startsWith("p-");

            mockMvc.perform(asAdmin(get("/api/admin/products/" + shirtId)))
                    .andExpect(jsonPath("$.name").value("클래식 옥스퍼드 셔츠 · 화이트"))
                    .andExpect(jsonPath("$.category").value("SHIRT"))
                    .andExpect(jsonPath("$.line").value("FERITO"))
                    .andExpect(jsonPath("$.status").value("DRAFT"))
                    .andExpect(jsonPath("$.priceKrw").value(100000))
                    .andExpect(jsonPath("$.leadTimeDays").value(14))
                    .andExpect(jsonPath("$.skus.length()").value(4))
                    .andExpect(jsonPath("$.manufacturer").value("레오네페리토"));
        }

        @Test
        @DisplayName("한 줄이라도 틀리면 아무것도 저장하지 않고 줄 · 칸 · 이유를 전부 돌려준다")
        void allOrNothing() throws Exception {
            String csv = "상품명*,분류*,라인*,판매가,세부분류,주소\r\n"
                    + "멀쩡한 셔츠,셔츠,페리토,90000,,ok-shirt\r\n"
                    + "틀린 바지,바지,레오네,구만원,옥스퍼드,\r\n"
                    + ",모자,페리토,,,ok-shirt\r\n";
            upload(csv.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("IMPORT_INVALID"))
                    .andExpect(jsonPath("$.fieldErrors[?(@.field == '3행 · 판매가')]").exists())
                    .andExpect(jsonPath("$.fieldErrors[?(@.field == '4행 · 상품명')]").exists())
                    .andExpect(jsonPath("$.fieldErrors[?(@.field == '4행 · 분류')]").exists())
                    .andExpect(jsonPath("$.fieldErrors[?(@.field == '4행 · 주소')]").exists());
            org.assertj.core.api.Assertions.assertThat(products.existsBySlug("ok-shirt")).isFalse();
        }

        @Test
        @DisplayName("한국어 엑셀의 기본 CSV(CP949)도 읽는다. 따옴표 안 쉼표 · 줄바꿈도 그대로")
        void cp949AndQuotes() throws Exception {
            String csv = "상품명*,분류*,라인*,상세설명,주소\r\n"
                    + "\"린넨 셔츠, 베이지\",셔츠,페리토,\"첫 줄\n둘째 줄 \"\"인용\"\"\",linen-beige\r\n";
            upload(csv.getBytes(java.nio.charset.Charset.forName("x-windows-949"))).andExpect(status().isOk());
            String id = products.findAll().stream().filter(p -> p.getSlug().equals("linen-beige")).findFirst().orElseThrow().getId().toString();
            mockMvc.perform(asAdmin(get("/api/admin/products/" + id)))
                    .andExpect(jsonPath("$.name").value("린넨 셔츠, 베이지"))
                    .andExpect(jsonPath("$.description").value("첫 줄\n둘째 줄 \"인용\""));
        }

        @Test
        @DisplayName("필수 칸이 없는 파일은 머리줄에서 막고, 회원은 올릴 수 없다")
        void headerAndGuard() throws Exception {
            upload("이름,가격\r\n셔츠,1000\r\n".getBytes(java.nio.charset.StandardCharsets.UTF_8))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors[0].field").value("1행 · 판매용상품명"));
            mockMvc.perform(asMember(multipart("/api/admin/products/import")
                            .file(new MockMultipartFile("file", "p.csv", "text/csv", new byte[] {1}))))
                    .andExpect(status().isForbidden());
        }
        /** 고객 양식(2026-10-27)과 같은 머리줄의 엑셀 파일을 만든다 — 줄마다 칸 값 */
        private byte[] xlsx(String[] head, String[]... rows) throws Exception {
            try (org.apache.poi.xssf.usermodel.XSSFWorkbook wb = new org.apache.poi.xssf.usermodel.XSSFWorkbook();
                 java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
                org.apache.poi.ss.usermodel.Sheet sheet = wb.createSheet("Sheet1");
                String[][] all = new String[rows.length + 1][];
                all[0] = head;
                System.arraycopy(rows, 0, all, 1, rows.length);
                for (int r = 0; r < all.length; r++) {
                    org.apache.poi.ss.usermodel.Row row = sheet.createRow(r);
                    for (int c = 0; c < all[r].length; c++) {
                        String v = all[r][c];
                        if (v != null && v.matches("^[0-9]+$") && v.length() < 10) {
                            row.createCell(c).setCellValue(Double.parseDouble(v)); // 엑셀의 숫자 칸
                        } else {
                            row.createCell(c).setCellValue(v == null ? "" : v);
                        }
                    }
                }
                wb.write(out);
                return out.toByteArray();
            }
        }

        private static final String[] CLIENT_HEAD = {"판매용상품명", "관리용상품명", "라인", "대분류", "소분류", "사이즈", "색상",
                "원단공급사", "원단컬렉션", "원단품번", "공임처", "제조사", "판매가", "원가", "제작기간", "원산지", "한줄설명", "상세설명",
                "소재", "세탁", "제조연월", "주소"};

        @Test
        @DisplayName("고객 최종 엑셀 양식(.xlsx, 22칸) 그대로 — 대분류 · 소분류 · 원산지 · 숫자 칸을 읽고, 관리자 전용 정보는 관리자 응답에만 나간다")
        void clientXlsx() throws Exception {
            byte[] file = xlsx(CLIENT_HEAD,
                    new String[] {"스트레이트 트라우저", "ST-BK-2610", "레오네", "트라우저", "스트레이트", "30/32/34", "블랙 / 네이비",
                            "비탈레 바르베리스", "21SS 캐노니코", "VB-1234", "성수 공방", "레오네페리토", "158000", "61000", "21",
                            "대한민국", "곧게 떨어지는 선", "", "울 100%", "드라이클리닝", "2026년 10월", "xlsx-straight"});
            String res = mockMvc.perform(asAdmin(multipart("/api/admin/products/import")
                            .file(new MockMultipartFile("file", "brand.xlsx",
                                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", file))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.created.length()").value(1))
                    .andReturn().getResponse().getContentAsString();
            String id = com.jayway.jsonpath.JsonPath.read(res, "$.created[0].id");

            mockMvc.perform(asAdmin(get("/api/admin/products/" + id)))
                    .andExpect(jsonPath("$.name").value("스트레이트 트라우저"))
                    .andExpect(jsonPath("$.category").value("TROUSERS"))
                    .andExpect(jsonPath("$.style").value("STRAIGHT"))
                    .andExpect(jsonPath("$.priceKrw").value(158000))
                    .andExpect(jsonPath("$.leadTimeDays").value(21))
                    .andExpect(jsonPath("$.color").value("블랙 / 네이비"))
                    .andExpect(jsonPath("$.countryOfOrigin").value("대한민국"))
                    .andExpect(jsonPath("$.skus.length()").value(3))
                    .andExpect(jsonPath("$.internal.adminName").value("ST-BK-2610"))
                    .andExpect(jsonPath("$.internal.fabricSupplier").value("비탈레 바르베리스"))
                    .andExpect(jsonPath("$.internal.fabricCollection").value("21SS 캐노니코"))
                    .andExpect(jsonPath("$.internal.fabricCode").value("VB-1234"))
                    .andExpect(jsonPath("$.internal.workshop").value("성수 공방"))
                    .andExpect(jsonPath("$.internal.costKrw").value(61000));

            // 관리자 화면이 internal 없이 저장해도(예전 화면) 지워지지 않는다
            mockMvc.perform(asAdmin(put("/api/admin/products/" + id))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(saveBody("xlsx-straight")))
                    .andExpect(status().isNoContent());
            mockMvc.perform(asAdmin(get("/api/admin/products/" + id)))
                    .andExpect(jsonPath("$.internal.costKrw").value(61000));
        }

        @Test
        @DisplayName("엑셀에서도 틀린 줄은 아무것도 저장하지 않고, 오류는 파일의 칸 이름(판매용상품명 · 대분류)으로 알려 준다")
        void clientXlsxErrors() throws Exception {
            byte[] file = xlsx(CLIENT_HEAD,
                    new String[] {"", "", "페리토", "모자", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", ""});
            mockMvc.perform(asAdmin(multipart("/api/admin/products/import")
                            .file(new MockMultipartFile("file", "brand.xlsx", "application/octet-stream", file))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors[?(@.field == '2행 · 판매용상품명')]").exists())
                    .andExpect(jsonPath("$.fieldErrors[?(@.field == '2행 · 대분류')]").exists());
            mockMvc.perform(asAdmin(multipart("/api/admin/products/import")
                            .file(new MockMultipartFile("file", "broken.xlsx", "application/octet-stream",
                                    new byte[] {'P', 'K', 3, 4, 0, 0}))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("IMPORT_INVALID"));
        }
    }

    @Nested
    @DisplayName("상품 복제 (2026-10-16)")
    class Duplicate {

        @Test
        @DisplayName("복제하면 사진 · 사이즈 · 고시까지 같은 초안이 생기고, slug 는 -copy, 또 하면 -copy-2")
        void copiesEverythingAsDraft() throws Exception {
            String id = createProduct("navy-shirt");
            attachImageAndPublish(id, "navy-shirt", uploadImage());

            String copy = com.jayway.jsonpath.JsonPath.read(mockMvc.perform(asAdmin(post("/api/admin/products/" + id + "/duplicate")))
                    .andExpect(status().isCreated())
                    .andExpect(header().string("Location", org.hamcrest.Matchers.startsWith("/api/admin/products/")))
                    .andReturn().getResponse().getContentAsString(), "$.id");
            org.assertj.core.api.Assertions.assertThat(copy).isNotEqualTo(id);

            mockMvc.perform(asAdmin(get("/api/admin/products/" + copy)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.slug").value("navy-shirt-copy"))
                    .andExpect(jsonPath("$.name").value("상품 (복제)"))
                    .andExpect(jsonPath("$.status").value("DRAFT"))
                    .andExpect(jsonPath("$.priceKrw").value(100000))
                    .andExpect(jsonPath("$.manufacturer").value("레오네페리토"))
                    .andExpect(jsonPath("$.images.length()").value(1))
                    .andExpect(jsonPath("$.skus.length()").value(
                            ((java.util.List<?>) com.jayway.jsonpath.JsonPath.read(mockMvc.perform(asAdmin(get("/api/admin/products/" + id)))
                                    .andReturn().getResponse().getContentAsString(), "$.skus")).size()));
            // 원본은 그대로 공개 중, 복제본은 공개 목록에 없다
            mockMvc.perform(get("/api/products/navy-shirt")).andExpect(status().isOk());
            mockMvc.perform(get("/api/products/navy-shirt-copy")).andExpect(status().isNotFound());

            mockMvc.perform(asAdmin(post("/api/admin/products/" + id + "/duplicate"))).andExpect(status().isCreated());
            org.assertj.core.api.Assertions.assertThat(products.existsBySlug("navy-shirt-copy-2")).isTrue();
        }

        @Test
        @DisplayName("없는 상품은 404, 회원은 403")
        void guards() throws Exception {
            mockMvc.perform(asAdmin(post("/api/admin/products/" + java.util.UUID.randomUUID() + "/duplicate")))
                    .andExpect(status().isNotFound());
            mockMvc.perform(asMember(post("/api/admin/products/" + java.util.UUID.randomUUID() + "/duplicate")))
                    .andExpect(status().isForbidden());
        }
    }

    private void attachImageAndPublish(String id, String slug, String mediaId) throws Exception {
        mockMvc.perform(asAdmin(put("/api/admin/products/" + id))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "slug": "%s",
                                  "name": "상품",
                                  "category": "SHIRT",
                                  "line": "LEONE",
                                  "priceKrw": 100000,
                                  "leadTimeDays": 10,
                                  "fabric": "면 100%%",
                                  "care": "드라이클리닝",
                                  "color": "브라운",
                                  "manufacturer": "레오네페리토",
                                  "countryOfOrigin": "대한민국",
                                  "manufacturedOn": "2026년 9월",
                                  "images": [ { "mediaId": "%s", "kind": "MAIN", "alt": "대표" } ]
                                }
                                """.formatted(slug, mediaId)))
                .andExpect(status().isNoContent());

        mockMvc.perform(asAdmin(post("/api/admin/products/" + id + "/publish")))
                .andExpect(status().isNoContent());
    }
}
