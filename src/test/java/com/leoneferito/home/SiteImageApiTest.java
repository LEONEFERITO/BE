package com.leoneferito.home;

import static com.leoneferito.member.MemberTestSupport.csrf;
import static com.leoneferito.member.MemberTestSupport.login;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.leoneferito.TestMailConfiguration;
import com.leoneferito.TestcontainersConfiguration;
import com.leoneferito.auth.AuthService;
import com.leoneferito.media.ImageFormat;
import com.leoneferito.media.MediaAsset;
import com.leoneferito.media.MediaAssetRepository;
import com.leoneferito.member.AdminMemberService;
import com.leoneferito.member.MemberRole;
import com.leoneferito.member.MemberTestSupport;
import com.leoneferito.product.Product;
import com.leoneferito.product.ProductCategory;
import com.leoneferito.product.ProductImage;
import com.leoneferito.product.ProductImageKind;
import com.leoneferito.product.ProductLine;
import com.leoneferito.product.ProductRepository;
import jakarta.servlet.http.Cookie;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/** 사이트 사진 칸(V20) — 손님 읽기 · 관리자 바꾸기 · 비우기 · 권한. 메인 배너 목록(V25)도 여기서. */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestMailConfiguration.class})
class SiteImageApiTest {

    private static final String PASSWORD = "long enough secret 9";
    private static final String SLOT_URL = "/api/admin/site-images/OFFLINE_SHOP";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private AuthService authService;

    @Autowired
    private AdminMemberService adminMembers;

    @Autowired
    private MediaAssetRepository mediaAssets;

    @Autowired
    private ProductRepository products;

    private Cookie admin;

    @BeforeEach
    void setUp() throws Exception {
        MemberTestSupport.cleanDatabase(jdbc);
        authService.signup("ops@example.com", PASSWORD, "운영자", null);
        adminMembers.createOrPromoteByCommand("ops@example.com", "운영자", MemberRole.ADMIN);
        authService.signup("buyer@example.com", PASSWORD, "손님", null);
        admin = login(mockMvc, "ops@example.com", PASSWORD);
    }

    @AfterEach
    void cleanUp() {
        // 칸은 지우지 않는다(마이그레이션이 심은 행이다). 비워서 처음 상태로 돌려 둔다 — 다른 테스트가 기본 상태를 본다.
        jdbc.update("UPDATE site_image SET media_id = NULL, alt = ''");
        jdbc.update("DELETE FROM hero_banner");
        jdbc.update("DELETE FROM product WHERE slug IN ('hero-shirt', 'hero-draft')");
        MemberTestSupport.cleanDatabase(jdbc);
    }

    private String body(String mediaId, String alt) {
        return "{\"mediaId\":" + (mediaId == null ? "null" : "\"" + mediaId + "\"") + ",\"alt\":\"" + alt + "\"}";
    }

    @Test
    @DisplayName("V20 · V22 · V23 이 심은 칸(매장 1 + 라인 10 + 메인 2 + 룩북 4 + 브랜드 5)이 사진 없이 로그인 없이 읽힌다. V24 배너 칸은 V25 가 걷었다")
    void seededAndPublic() throws Exception {
        mockMvc.perform(get("/api/site-images"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(22))
                .andExpect(jsonPath("$[0].slot").value("OFFLINE_SHOP"))
                .andExpect(jsonPath("$[1].slot").value("LINE_LEONE_1"))
                .andExpect(jsonPath("$[10].slot").value("LINE_FERITO_5"))
                .andExpect(jsonPath("$[21].slot").value("BRAND_IMPRESSION"))
                .andExpect(jsonPath("$[0].imageUrl").doesNotExist())
                .andExpect(jsonPath("$[0].alt").value(""));
    }

    @Test
    @DisplayName("관리자가 사진과 설명을 바꾸면 손님에게 그 주소로 나가고, 비우면 기본으로 돌아간다")
    void changeAndClear() throws Exception {
        MediaAsset media = mediaAssets.save(new MediaAsset(UUID.randomUUID(), "shop.webp", ImageFormat.WEBP,
                100_000L, 1600, 1200, "test/" + UUID.randomUUID() + ".webp"));

        mockMvc.perform(csrf(mockMvc, put(SLOT_URL), admin)
                        .contentType(MediaType.APPLICATION_JSON).content(body(media.getId().toString(), "  매장 전경  ")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slot").value("OFFLINE_SHOP"))
                .andExpect(jsonPath("$.mediaId").value(media.getId().toString()))
                .andExpect(jsonPath("$.imageUrl").isNotEmpty())
                // 앞뒤 공백은 저장하지 않는다
                .andExpect(jsonPath("$.alt").value("매장 전경"));

        mockMvc.perform(get("/api/site-images"))
                .andExpect(jsonPath("$[0].imageUrl").isNotEmpty())
                .andExpect(jsonPath("$[0].alt").value("매장 전경"));

        // 없는 이미지 id 는 404 — 방금 저장한 사진이 그대로 남는다
        mockMvc.perform(csrf(mockMvc, put(SLOT_URL), admin)
                        .contentType(MediaType.APPLICATION_JSON).content(body(UUID.randomUUID().toString(), "x")))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/site-images"))
                .andExpect(jsonPath("$[0].mediaId").value(media.getId().toString()));

        // 설명이 CHECK(200자)를 넘으면 DB 까지 가지 않고 400
        mockMvc.perform(csrf(mockMvc, put(SLOT_URL), admin)
                        .contentType(MediaType.APPLICATION_JSON).content(body(media.getId().toString(), "가".repeat(201))))
                .andExpect(status().isBadRequest());

        // 없는 칸 이름은 400 — 칸은 관리자가 만드는 것이 아니다
        mockMvc.perform(csrf(mockMvc, put("/api/admin/site-images/NO_SUCH_SLOT"), admin)
                        .contentType(MediaType.APPLICATION_JSON).content(body(null, "")))
                .andExpect(status().isBadRequest());

        // 비우기 — 손님 화면은 코드의 기본 사진으로 돌아간다
        mockMvc.perform(csrf(mockMvc, put(SLOT_URL), admin)
                        .contentType(MediaType.APPLICATION_JSON).content(body(null, "")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imageUrl").doesNotExist());
        mockMvc.perform(get("/api/site-images"))
                .andExpect(jsonPath("$[0].imageUrl").doesNotExist());
    }

    @Test
    @DisplayName("라인 페이지 칸(V22)도 같은 방법으로 바꾼다")
    void lineSlotChanges() throws Exception {
        MediaAsset media = mediaAssets.save(new MediaAsset(UUID.randomUUID(), "line.webp", ImageFormat.WEBP,
                100_000L, 1200, 1600, "test/" + UUID.randomUUID() + ".webp"));
        mockMvc.perform(csrf(mockMvc, put("/api/admin/site-images/LINE_FERITO_3"), admin)
                        .contentType(MediaType.APPLICATION_JSON).content(body(media.getId().toString(), "페리토 셋째 칸")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slot").value("LINE_FERITO_3"))
                .andExpect(jsonPath("$.imageUrl").isNotEmpty());
        mockMvc.perform(get("/api/site-images"))
                .andExpect(jsonPath("$[?(@.slot == 'LINE_FERITO_3')].alt").value("페리토 셋째 칸"))
                // 다른 칸은 그대로 비어 있다 (필터 결과는 목록이라 "mediaId 가 null 인 그 칸" 으로 찾는다)
                .andExpect(jsonPath("$[?(@.slot == 'LINE_FERITO_2' && @.mediaId == null)].slot").value("LINE_FERITO_2"));
    }

    @Test
    @DisplayName("손님은 바꿀 수 없고, 관리자 목록도 볼 수 없다")
    void memberCannotChange() throws Exception {
        Cookie buyer = login(mockMvc, "buyer@example.com", PASSWORD);
        mockMvc.perform(csrf(mockMvc, put(SLOT_URL), buyer)
                        .contentType(MediaType.APPLICATION_JSON).content(body(null, "")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/admin/site-images").cookie(buyer))
                .andExpect(status().isForbidden());
    }

    // ── 메인 배너 목록 (V25) ─────────────────────────────────

    private static final String BANNERS = "/api/admin/hero-banners";

    private static String q(String v) {
        return v == null ? "null" : "\"" + v + "\"";
    }

    private String banner(String kind, String productId, String figureId, String ctaUrl, boolean visible) {
        return "{\"kind\":" + q(kind) + ",\"productId\":" + q(productId)
                + ",\"eyebrow\":\"  26FW  \",\"title\":\"가을 아우터\",\"subtitle\":\"새 시즌\""
                + ",\"ctaLabel\":\"보러 가기\",\"ctaUrl\":" + q(ctaUrl)
                + ",\"figure\":" + (figureId == null ? "null" : "{\"mediaId\":" + q(figureId) + ",\"alt\":\" 인물 \"}")
                + ",\"frameLeft\":null,\"frameRight\":{\"mediaId\":null,\"alt\":\"\"}"
                + ",\"visible\":" + visible + "}";
    }

    private MediaAsset image(String name) {
        return mediaAssets.save(new MediaAsset(UUID.randomUUID(), name + ".webp", ImageFormat.WEBP,
                100_000L, 1200, 1600, "test/" + UUID.randomUUID() + ".webp"));
    }

    private String create(String body) throws Exception {
        String res = mockMvc.perform(csrf(mockMvc, post(BANNERS), admin)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(res, "$.id");
    }

    /** 공개할 수 있는 상품 하나(이름 · 가격 · 제작 기간 · 대표 사진). publish 하지 않으면 초안이다. */
    private Product product(String slug, boolean publish) {
        MediaAsset media = image(slug);
        Product p = new Product(UUID.randomUUID(), slug, ProductCategory.SHIRT, ProductLine.LEONE);
        p.setName("배너 셔츠");
        p.setPriceKrw(290_000L);
        p.setLeadTimeDays((short) 14);
        // 공개하려면 상품정보제공고시가 다 차 있어야 한다(Product.missingForPublish)
        p.setFabric("면 100%");
        p.setCare("드라이클리닝");
        p.setNotice("브라운", "레오네페리토", "대한민국", "2026년 9월");
        p.addImage(new ProductImage(UUID.randomUUID(), media, ProductImageKind.MAIN, "배너 셔츠", 0));
        if (publish) {
            p.publish();
        }
        return products.saveAndFlush(p);
    }

    @Test
    @DisplayName("배너는 처음에 없고, 손님 목록은 로그인 없이 읽힌다(빈 목록 — 화면은 진열 순서 맨 앞 상품으로)")
    void bannersStartEmpty() throws Exception {
        mockMvc.perform(get("/api/hero-banners")).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        mockMvc.perform(get(BANNERS).cookie(admin)).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    @DisplayName("상품 정보 가져오기 — 공개 상품만, 손님에게는 주소(slug)로. 끄면 안 나가고, 상품이 지워지면 배너도 지워진다")
    void productBanner() throws Exception {
        Product shirt = product("hero-shirt", true);
        Product draft = product("hero-draft", false);

        String id = create(banner("PRODUCT", shirt.getId().toString(), null, "", true));
        mockMvc.perform(get(BANNERS).cookie(admin))
                .andExpect(jsonPath("$[0].kind").value("PRODUCT"))
                .andExpect(jsonPath("$[0].productId").value(shirt.getId().toString()))
                .andExpect(jsonPath("$[0].eyebrow").value("26FW"))
                .andExpect(jsonPath("$[0].shown").value(true));
        mockMvc.perform(get("/api/hero-banners"))
                .andExpect(jsonPath("$[0].productSlug").value("hero-shirt"))
                // 손님 쪽에는 내부 id 가 나가지 않는다
                .andExpect(jsonPath("$[0].productId").doesNotExist())
                .andExpect(jsonPath("$[0].figure").doesNotExist());

        // 상품 없이 → 400, 초안 상품 → 404
        mockMvc.perform(csrf(mockMvc, post(BANNERS), admin).contentType(MediaType.APPLICATION_JSON)
                        .content(banner("PRODUCT", null, null, "", true)))
                .andExpect(status().isBadRequest());
        mockMvc.perform(csrf(mockMvc, put(BANNERS + "/" + id), admin).contentType(MediaType.APPLICATION_JSON)
                        .content(banner("PRODUCT", draft.getId().toString(), null, "", true)))
                .andExpect(status().isNotFound());

        // 끄면 손님 목록에서 빠진다 — 관리자 목록에는 남고 shown=false
        mockMvc.perform(csrf(mockMvc, put(BANNERS + "/" + id), admin).contentType(MediaType.APPLICATION_JSON)
                        .content(banner("PRODUCT", shirt.getId().toString(), null, "", false)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.visible").value(false))
                .andExpect(jsonPath("$.shown").value(false));
        mockMvc.perform(get("/api/hero-banners")).andExpect(jsonPath("$.length()").value(0));

        // 상품이 지워지면 그 배너도 지워진다(ON DELETE CASCADE)
        jdbc.update("DELETE FROM product WHERE slug = 'hero-shirt'");
        mockMvc.perform(get(BANNERS).cookie(admin)).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    @DisplayName("직접 등록 — 인물 사진이 있어야 하고, 문구는 공백을 걷고, 버튼 주소는 사이트 안 주소나 https 만")
    void customBanner() throws Exception {
        MediaAsset figure = image("figure");
        String fig = figure.getId().toString();
        String id = create(banner("CUSTOM", null, fig, "/category/jacket/", true));
        mockMvc.perform(get("/api/hero-banners"))
                .andExpect(jsonPath("$[0].kind").value("CUSTOM"))
                .andExpect(jsonPath("$[0].eyebrow").value("26FW"))
                .andExpect(jsonPath("$[0].ctaUrl").value("/category/jacket/"))
                .andExpect(jsonPath("$[0].figure.url").isNotEmpty())
                .andExpect(jsonPath("$[0].figure.alt").value("인물"))
                // 비운 액자는 없다 — 화면이 인물 사진을 잘라 쓴다
                .andExpect(jsonPath("$[0].frameLeft").doesNotExist())
                .andExpect(jsonPath("$[0].frameRight").doesNotExist());

        mockMvc.perform(csrf(mockMvc, put(BANNERS + "/" + id), admin).contentType(MediaType.APPLICATION_JSON)
                        .content(banner("CUSTOM", null, fig, "https://www.instagram.com/leoneferito_/", true)))
                .andExpect(status().isOk());

        // 인물 사진 없이 → 400, 없는 이미지 → 404
        mockMvc.perform(csrf(mockMvc, post(BANNERS), admin).contentType(MediaType.APPLICATION_JSON)
                        .content(banner("CUSTOM", null, null, "", true)))
                .andExpect(status().isBadRequest());
        mockMvc.perform(csrf(mockMvc, post(BANNERS), admin).contentType(MediaType.APPLICATION_JSON)
                        .content(banner("CUSTOM", null, UUID.randomUUID().toString(), "", true)))
                .andExpect(status().isNotFound());
        // 밖으로 새는 주소 · 스크립트 주소는 400
        for (String bad : new String[] {"//evil.example", "javascript:alert(1)", "http://plain.example", "category"}) {
            mockMvc.perform(csrf(mockMvc, put(BANNERS + "/" + id), admin).contentType(MediaType.APPLICATION_JSON)
                            .content(banner("CUSTOM", null, fig, bad, true)))
                    .andExpect(status().isBadRequest());
        }
        // 종류가 없거나 모르는 값, 제목 61자 → 400
        mockMvc.perform(csrf(mockMvc, post(BANNERS), admin).contentType(MediaType.APPLICATION_JSON)
                        .content(banner(null, null, fig, "", true)))
                .andExpect(status().isBadRequest());
        mockMvc.perform(csrf(mockMvc, post(BANNERS), admin).contentType(MediaType.APPLICATION_JSON)
                        .content(banner("AUTO", null, fig, "", true)))
                .andExpect(status().isBadRequest());
        mockMvc.perform(csrf(mockMvc, post(BANNERS), admin).contentType(MediaType.APPLICATION_JSON)
                        .content(banner("CUSTOM", null, fig, "", true).replace("가을 아우터", "가".repeat(61))))
                .andExpect(status().isBadRequest());

        // 상품 배너로 바꾸면 사진은 걷힌다
        Product shirt = product("hero-shirt", true);
        mockMvc.perform(csrf(mockMvc, put(BANNERS + "/" + id), admin).contentType(MediaType.APPLICATION_JSON)
                        .content(banner("PRODUCT", shirt.getId().toString(), fig, "", true)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.figure").doesNotExist());
    }

    @Test
    @DisplayName("순서 바꾸기 · 지우기 · 최대 개수 — 옛 목록으로 정렬하면 409")
    void orderDeleteAndLimit() throws Exception {
        String fig = image("figure").getId().toString();
        String a = create(banner("CUSTOM", null, fig, "/a/", true));
        String b = create(banner("CUSTOM", null, fig, "/b/", true));
        String c = create(banner("CUSTOM", null, fig, "/c/", true));

        mockMvc.perform(csrf(mockMvc, put(BANNERS + "/order"), admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[" + q(c) + "," + q(a) + "," + q(b) + "]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(c))
                .andExpect(jsonPath("$[2].id").value(b));
        mockMvc.perform(get("/api/hero-banners")).andExpect(jsonPath("$[0].ctaUrl").value("/c/"));

        mockMvc.perform(csrf(mockMvc, put(BANNERS + "/order"), admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[" + q(a) + "," + q(b) + "]}"))
                .andExpect(status().isConflict());

        mockMvc.perform(csrf(mockMvc, delete(BANNERS + "/" + a), admin)).andExpect(status().isNoContent());
        mockMvc.perform(csrf(mockMvc, delete(BANNERS + "/" + a), admin)).andExpect(status().isNotFound());

        // 지금 둘 — 최대 개수까지 채우고 하나 더
        for (int i = 2; i < SiteImageService.MAX_BANNERS; i++) {
            create(banner("CUSTOM", null, fig, "", false));
        }
        mockMvc.perform(csrf(mockMvc, post(BANNERS), admin).contentType(MediaType.APPLICATION_JSON)
                        .content(banner("CUSTOM", null, fig, "", true)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BANNER_LIMIT"));
    }

    @Test
    @DisplayName("이미지 한 장 — 큰 사진이 있어야 하고, 휴대폰 사진은 고르면 함께 나간다. 액자 · 상품은 남지 않는다")
    void imageBanner() throws Exception {
        String wide = image("wide").getId().toString();
        String tall = image("tall").getId().toString();
        Product shirt = product("hero-shirt", true);
        String body = """
                {"kind":"IMAGE","productId":"%s","eyebrow":"","title":"가을 컬렉션","subtitle":"","ctaLabel":"보러 가기",
                 "ctaUrl":"/products/hero-shirt/","figure":{"mediaId":"%s","alt":" 가을 화보 "},
                 "frameLeft":{"mediaId":"%s","alt":"남으면 안 됨"},"frameRight":null,
                 "mobile":{"mediaId":"%s","alt":"세로 화보"},"visible":true}
                """.formatted(shirt.getId(), wide, wide, tall);
        String id = create(body);
        mockMvc.perform(get("/api/hero-banners"))
                .andExpect(jsonPath("$[0].kind").value("IMAGE"))
                .andExpect(jsonPath("$[0].figure.url").isNotEmpty())
                .andExpect(jsonPath("$[0].figure.alt").value("가을 화보"))
                .andExpect(jsonPath("$[0].mobile.alt").value("세로 화보"))
                .andExpect(jsonPath("$[0].ctaUrl").value("/products/hero-shirt/"))
                // 이미지 한 장에는 액자 · 상품이 없다 — 보내도 남지 않는다
                .andExpect(jsonPath("$[0].frameLeft").doesNotExist())
                .andExpect(jsonPath("$[0].productSlug").doesNotExist());

        // 큰 사진 없이 → 400
        mockMvc.perform(csrf(mockMvc, post(BANNERS), admin).contentType(MediaType.APPLICATION_JSON)
                        .content(body.replace("\"figure\":{\"mediaId\":\"" + wide + "\",\"alt\":\" 가을 화보 \"}",
                                "\"figure\":null")))
                .andExpect(status().isBadRequest());

        // 직접 등록(누끼)으로 바꾸면 휴대폰 사진은 걷힌다
        mockMvc.perform(csrf(mockMvc, put(BANNERS + "/" + id), admin).contentType(MediaType.APPLICATION_JSON)
                        .content(body.replace("\"kind\":\"IMAGE\"", "\"kind\":\"CUSTOM\"")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mobile").doesNotExist())
                .andExpect(jsonPath("$.frameLeft.alt").value("남으면 안 됨"));
    }

    private MediaAsset video(String name) {
        return mediaAssets.save(new MediaAsset(UUID.randomUUID(), name + ".mp4", ImageFormat.MP4,
                4_000_000L, null, null, "test/" + UUID.randomUUID() + ".mp4"));
    }

    @Test
    @DisplayName("영상 — 영상 칸에는 영상만, 첫 장면 칸에는 사진만. 다른 종류로 바꾸면 첫 장면 사진은 걷힌다")
    void videoBanner() throws Exception {
        String pc = video("pc").getId().toString();
        String phone = video("phone").getId().toString();
        String still = image("still").getId().toString();
        String stillPhone = image("still-phone").getId().toString();
        String body = """
                {"kind":"VIDEO","productId":null,"eyebrow":"26FW","title":"가을 필름","subtitle":"","ctaLabel":"",
                 "ctaUrl":"/products/","figure":{"mediaId":"%s","alt":" 가을 필름 — 네이비 수트 "},
                 "frameLeft":null,"frameRight":null,"mobile":{"mediaId":"%s","alt":""},
                 "poster":{"mediaId":"%s","alt":""},"mobilePoster":{"mediaId":"%s","alt":""},"visible":true}
                """.formatted(pc, phone, still, stillPhone);
        String id = create(body);
        mockMvc.perform(get("/api/hero-banners"))
                .andExpect(jsonPath("$[0].kind").value("VIDEO"))
                .andExpect(jsonPath("$[0].figure.url").value(org.hamcrest.Matchers.endsWith(".mp4")))
                .andExpect(jsonPath("$[0].figure.alt").value("가을 필름 — 네이비 수트"))
                .andExpect(jsonPath("$[0].mobile.url").value(org.hamcrest.Matchers.endsWith(".mp4")))
                .andExpect(jsonPath("$[0].poster.url").value(org.hamcrest.Matchers.endsWith(".webp")))
                .andExpect(jsonPath("$[0].mobilePoster.url").isNotEmpty());

        // 첫 장면 사진 없이도 된다(선택)
        mockMvc.perform(csrf(mockMvc, put(BANNERS + "/" + id), admin).contentType(MediaType.APPLICATION_JSON)
                        .content(body.replace("\"poster\":{\"mediaId\":\"" + still + "\",\"alt\":\"\"}", "\"poster\":null")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.poster").doesNotExist())
                .andExpect(jsonPath("$.shown").value(true));

        // 영상 칸에 사진 → 400, 첫 장면 칸에 영상 → 400
        mockMvc.perform(csrf(mockMvc, post(BANNERS), admin).contentType(MediaType.APPLICATION_JSON)
                        .content(body.replace("\"figure\":{\"mediaId\":\"" + pc, "\"figure\":{\"mediaId\":\"" + still)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_IMAGE"));
        mockMvc.perform(csrf(mockMvc, post(BANNERS), admin).contentType(MediaType.APPLICATION_JSON)
                        .content(body.replace(still, phone)))
                .andExpect(status().isBadRequest());
        // 이미지 한 장 · 직접 등록에 영상 → 400 (사진 자리에 영상이 걸리지 않게)
        mockMvc.perform(csrf(mockMvc, put(BANNERS + "/" + id), admin).contentType(MediaType.APPLICATION_JSON)
                        .content(body.replace("\"kind\":\"VIDEO\"", "\"kind\":\"IMAGE\"")))
                .andExpect(status().isBadRequest());
        // 영상 없이 → 400
        mockMvc.perform(csrf(mockMvc, post(BANNERS), admin).contentType(MediaType.APPLICATION_JSON)
                        .content(body.replace("\"figure\":{\"mediaId\":\"" + pc + "\",\"alt\":\" 가을 필름 — 네이비 수트 \"}",
                                "\"figure\":null")))
                .andExpect(status().isBadRequest());

        // 이미지 한 장으로 바꾸면(사진으로 갈아 끼워서) 첫 장면 사진은 남지 않는다
        String asImage = body.replace("\"kind\":\"VIDEO\"", "\"kind\":\"IMAGE\"")
                .replace(pc, still).replace(phone, stillPhone);
        mockMvc.perform(csrf(mockMvc, put(BANNERS + "/" + id), admin).contentType(MediaType.APPLICATION_JSON)
                        .content(asImage))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("IMAGE"))
                .andExpect(jsonPath("$.poster").doesNotExist())
                .andExpect(jsonPath("$.mobilePoster").doesNotExist());
    }

    @Test
    @DisplayName("손님은 배너를 만들 수도, 관리자 목록을 볼 수도 없다")
    void memberCannotChangeBanners() throws Exception {
        Cookie buyer = login(mockMvc, "buyer@example.com", PASSWORD);
        mockMvc.perform(csrf(mockMvc, post(BANNERS), buyer).contentType(MediaType.APPLICATION_JSON)
                        .content(banner("CUSTOM", null, null, "", true)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get(BANNERS).cookie(buyer)).andExpect(status().isForbidden());
    }
}
