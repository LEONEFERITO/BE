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
class AdminApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ProductRepository products;

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
                  "skus": [
                    { "size": "95", "measurements": [
                        { "part": "SHOULDER", "valueCm": 45.0, "toleranceCm": 1.0 } ] },
                    { "size": "100" }
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
                                  "images": [ { "mediaId": "%s", "kind": "MAIN", "alt": "대표" } ]
                                }
                                """.formatted(slug, mediaId)))
                .andExpect(status().isNoContent());

        mockMvc.perform(asAdmin(post("/api/admin/products/" + id + "/publish")))
                .andExpect(status().isNoContent());
    }
}
