package com.leoneferito.home;

import static com.leoneferito.member.MemberTestSupport.csrf;
import static com.leoneferito.member.MemberTestSupport.login;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

/** 사이트 사진 칸(V20) — 손님 읽기 · 관리자 바꾸기 · 비우기 · 권한. 메인 배너 설정(V24)도 여기서. */
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
        jdbc.update("UPDATE hero_config SET mode = 'AUTO', product_id = NULL, eyebrow = '', title = '', subtitle = '',"
                + " cta_label = '', cta_url = ''");
        jdbc.update("DELETE FROM product WHERE slug IN ('hero-shirt', 'hero-draft')");
        MemberTestSupport.cleanDatabase(jdbc);
    }

    private String body(String mediaId, String alt) {
        return "{\"mediaId\":" + (mediaId == null ? "null" : "\"" + mediaId + "\"") + ",\"alt\":\"" + alt + "\"}";
    }

    @Test
    @DisplayName("V20 · V22 · V23 · V24 가 심은 칸(매장 1 + 라인 10 + 메인 2 + 룩북 4 + 브랜드 5 + 배너 3)이 사진 없이 로그인 없이 읽힌다")
    void seededAndPublic() throws Exception {
        mockMvc.perform(get("/api/site-images"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(25))
                .andExpect(jsonPath("$[22].slot").value("HERO_FIGURE"))
                .andExpect(jsonPath("$[24].slot").value("HERO_FRAME_RIGHT"))
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

    // ── 메인 배너 (V24) ──────────────────────────────────────

    private static final String HERO_URL = "/api/admin/hero";

    private String hero(String mode, String productId, String ctaUrl) {
        return "{\"mode\":" + (mode == null ? "null" : "\"" + mode + "\"")
                + ",\"productId\":" + (productId == null ? "null" : "\"" + productId + "\"")
                + ",\"eyebrow\":\"  26FW  \",\"title\":\"가을 아우터\",\"subtitle\":\"새 시즌\""
                + ",\"ctaLabel\":\"보러 가기\",\"ctaUrl\":" + (ctaUrl == null ? "null" : "\"" + ctaUrl + "\"") + "}";
    }

    /** 공개할 수 있는 상품 하나(이름 · 가격 · 제작 기간 · 대표 사진). publish 하지 않으면 초안이다. */
    private Product product(String slug, boolean publish) {
        MediaAsset media = mediaAssets.save(new MediaAsset(UUID.randomUUID(), slug + ".webp", ImageFormat.WEBP,
                100_000L, 1200, 1600, "test/" + UUID.randomUUID() + ".webp"));
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
    @DisplayName("배너 설정은 처음에 자동(AUTO)이고 로그인 없이 읽힌다")
    void heroDefaultsToAuto() throws Exception {
        mockMvc.perform(get("/api/hero"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("AUTO"))
                .andExpect(jsonPath("$.productSlug").doesNotExist())
                .andExpect(jsonPath("$.title").value(""));
    }

    @Test
    @DisplayName("상품 지정 — 공개 상품만 고를 수 있고, 손님에게는 주소(slug)로 나간다")
    void heroProductMode() throws Exception {
        Product shirt = product("hero-shirt", true);
        Product draft = product("hero-draft", false);

        mockMvc.perform(csrf(mockMvc, put(HERO_URL), admin)
                        .contentType(MediaType.APPLICATION_JSON).content(hero("PRODUCT", shirt.getId().toString(), "")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("PRODUCT"))
                .andExpect(jsonPath("$.productId").value(shirt.getId().toString()))
                .andExpect(jsonPath("$.productSlug").value("hero-shirt"));
        mockMvc.perform(get("/api/hero"))
                .andExpect(jsonPath("$.mode").value("PRODUCT"))
                .andExpect(jsonPath("$.productSlug").value("hero-shirt"))
                // 손님 쪽에는 내부 id 가 나가지 않는다
                .andExpect(jsonPath("$.productId").doesNotExist());

        // 상품 없이 상품 지정 → 400, 초안 상품 → 404. 둘 다 저장된 값이 그대로 남는다
        mockMvc.perform(csrf(mockMvc, put(HERO_URL), admin)
                        .contentType(MediaType.APPLICATION_JSON).content(hero("PRODUCT", null, "")))
                .andExpect(status().isBadRequest());
        mockMvc.perform(csrf(mockMvc, put(HERO_URL), admin)
                        .contentType(MediaType.APPLICATION_JSON).content(hero("PRODUCT", draft.getId().toString(), "")))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/hero")).andExpect(jsonPath("$.productSlug").value("hero-shirt"));

        // 상품이 지워지면 DB 가 비운다(ON DELETE SET NULL) — 손님 화면은 자동으로 돌아간다
        jdbc.update("DELETE FROM product WHERE slug = 'hero-shirt'");
        mockMvc.perform(get("/api/hero")).andExpect(jsonPath("$.productSlug").doesNotExist());
    }

    @Test
    @DisplayName("직접 올리기 — 문구는 앞뒤 공백을 걷고, 버튼 주소는 사이트 안 주소나 https 만 받는다")
    void heroCustomMode() throws Exception {
        mockMvc.perform(csrf(mockMvc, put(HERO_URL), admin)
                        .contentType(MediaType.APPLICATION_JSON).content(hero("CUSTOM", null, "/category/jacket/")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("CUSTOM"))
                .andExpect(jsonPath("$.productId").doesNotExist())
                .andExpect(jsonPath("$.eyebrow").value("26FW"))
                .andExpect(jsonPath("$.ctaUrl").value("/category/jacket/"));

        mockMvc.perform(csrf(mockMvc, put(HERO_URL), admin)
                        .contentType(MediaType.APPLICATION_JSON).content(hero("CUSTOM", null, "https://www.instagram.com/leoneferito_/")))
                .andExpect(status().isOk());

        // 밖으로 새는 주소 · 스크립트 주소는 400
        for (String bad : new String[] {"//evil.example", "javascript:alert(1)", "http://plain.example", "category"}) {
            mockMvc.perform(csrf(mockMvc, put(HERO_URL), admin)
                            .contentType(MediaType.APPLICATION_JSON).content(hero("CUSTOM", null, bad)))
                    .andExpect(status().isBadRequest());
        }
        // 모드가 없거나 모르는 값이면 400
        mockMvc.perform(csrf(mockMvc, put(HERO_URL), admin)
                        .contentType(MediaType.APPLICATION_JSON).content(hero(null, null, "")))
                .andExpect(status().isBadRequest());
        mockMvc.perform(csrf(mockMvc, put(HERO_URL), admin)
                        .contentType(MediaType.APPLICATION_JSON).content(hero("BANNER", null, "")))
                .andExpect(status().isBadRequest());
        // 제목이 CHECK(60자)를 넘으면 DB 까지 가지 않고 400
        mockMvc.perform(csrf(mockMvc, put(HERO_URL), admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"CUSTOM\",\"title\":\"" + "가".repeat(61) + "\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("손님은 배너 설정을 바꿀 수 없다")
    void memberCannotChangeHero() throws Exception {
        Cookie buyer = login(mockMvc, "buyer@example.com", PASSWORD);
        mockMvc.perform(csrf(mockMvc, put(HERO_URL), buyer)
                        .contentType(MediaType.APPLICATION_JSON).content(hero("AUTO", null, "")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/admin/hero").cookie(buyer))
                .andExpect(status().isForbidden());
    }
}
