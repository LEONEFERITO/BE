package com.leoneferito.product;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.leoneferito.TestcontainersConfiguration;
import com.leoneferito.media.ImageFormat;
import com.leoneferito.media.MediaAsset;
import com.leoneferito.media.MediaAssetRepository;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * 공개 상품 API 검증.
 *
 * <p>여기서 특히 확인하는 것은 <b>나가면 안 되는 것이 안 나가는가</b> 다.
 * 응답이 맞게 나오는지는 개발 중에 눈으로도 보이지만, 내부 필드가 섞여 나가는 건
 * 아무도 안 본다. 그래서 {@code status}·내부 id 가 JSON 에 없다는 것을 명시적으로 건다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = "app.media.base-url=https://cdn.example.test/media/")
@Transactional
class ProductApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ProductRepository products;

    @Autowired
    private MediaAssetRepository mediaAssets;

    /** storage_key 는 유니크다. 테스트마다 다른 키를 준다. */
    private MediaAsset media(String storageKey) {
        return mediaAssets.save(new MediaAsset(
                UUID.randomUUID(), "shot.webp", ImageFormat.WEBP, 100_000L, 1200, 1600,
                storageKey));
    }

    @BeforeEach
    void seed() {
        Product published = new Product(UUID.randomUUID(), "brown-shirt",
                ProductCategory.SHIRT, ProductLine.FERITO);
        published.setName("브라운 셔츠");
        published.setPriceKrw(290_000L);
        published.setListPriceKrw(320_000L);
        published.setLeadTimeDays((short) 14);
        published.setFabric("면 100%");
        published.setModel((short) 183, (short) 84, "100");
        published.addImage(new ProductImage(UUID.randomUUID(), media("products/brown.webp"),
                ProductImageKind.MAIN, "브라운 셔츠 측면 컷", 0));

        ProductSku sku = new ProductSku(UUID.randomUUID(), "100", 1);
        sku.addMeasurement(new ProductMeasurement(UUID.randomUUID(),
                MeasurementPart.SHOULDER, new BigDecimal("46.5"), new BigDecimal("1.0")));
        sku.addMeasurement(new ProductMeasurement(UUID.randomUUID(),
                MeasurementPart.CHEST, new BigDecimal("52.0"), new BigDecimal("1.5")));
        published.addSku(sku);
        published.publish();
        products.saveAndFlush(published);

        Product draft = new Product(UUID.randomUUID(), "secret-jacket",
                ProductCategory.JACKET, ProductLine.LEONE);
        products.saveAndFlush(draft);
    }

    @Test
    @DisplayName("목록에 공개 상품만 나온다")
    void listShowsOnlyPublished() throws Exception {
        mockMvc.perform(get("/api/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.slug=='brown-shirt')]").exists())
                .andExpect(jsonPath("$[?(@.slug=='secret-jacket')]").doesNotExist());
    }

    @Test
    @DisplayName("응답에 내부 상태와 id 가 섞이지 않는다")
    void responseHidesInternals() throws Exception {
        String body = mockMvc.perform(get("/api/products/brown-shirt"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").doesNotExist())
                .andExpect(jsonPath("$.id").doesNotExist())
                .andExpect(jsonPath("$.displayOrder").doesNotExist())
                .andExpect(jsonPath("$.createdAt").doesNotExist())
                .andReturn().getResponse().getContentAsString();

        // 문자열 수준에서도 확인한다 — 중첩된 곳(이미지·SKU)에 id 가 섞여 나올 수 있다.
        org.assertj.core.api.Assertions.assertThat(body)
                .doesNotContain("PUBLISHED")
                .doesNotContain("\"id\"");
    }

    @Test
    @DisplayName("비공개 상품은 403 이 아니라 404 다 — slug 의 존재가 새면 안 된다")
    void draftIsNotFound() throws Exception {
        mockMvc.perform(get("/api/products/secret-jacket"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                // 에러 응답에도 slug 나 내부 사유가 들어가면 안 된다.
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("secret-jacket"))));
    }

    @Test
    @DisplayName("없는 상품도 같은 404 다")
    void unknownSlugIsNotFound() throws Exception {
        mockMvc.perform(get("/api/products/does-not-exist"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    @DisplayName("상세에 제작 기간 · 실측 · 모델 스펙이 들어간다")
    void detailCarriesPurchaseDecisionData() throws Exception {
        mockMvc.perform(get("/api/products/brown-shirt"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.leadTimeDays").value(14))
                .andExpect(jsonPath("$.model.heightCm").value(183))
                .andExpect(jsonPath("$.skus[0].size").value("100"))
                .andExpect(jsonPath("$.skus[0].orderable").value(true))
                // 부위 순서는 enum 선언 순서로 고정된다: SHOULDER 가 CHEST 보다 앞이다.
                .andExpect(jsonPath("$.skus[0].measurements[0].part").value("SHOULDER"))
                .andExpect(jsonPath("$.skus[0].measurements[1].part").value("CHEST"))
                .andExpect(jsonPath("$.skus[0].measurements[0].valueCm").value(46.5));
    }

    @Test
    @DisplayName("이미지 주소는 설정된 기준 주소 + 스토리지 키다")
    void imageUrlUsesConfiguredBase() throws Exception {
        mockMvc.perform(get("/api/products/brown-shirt"))
                .andExpect(status().isOk())
                // 설정값 끝의 / 가 중복되지 않아야 한다.
                .andExpect(jsonPath("$.images[0].url")
                        .value("https://cdn.example.test/media/products/brown.webp"));
    }

    @Test
    @DisplayName("카테고리와 라인으로 거른다")
    void filters() throws Exception {
        mockMvc.perform(get("/api/products").param("category", "SHIRT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.slug=='brown-shirt')]").exists());

        mockMvc.perform(get("/api/products").param("category", "SHOES"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.slug=='brown-shirt')]").doesNotExist());

        mockMvc.perform(get("/api/products").param("line", "LEONE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.slug=='brown-shirt')]").doesNotExist());
    }

    @Test
    @DisplayName("모르는 필터 값은 빈 목록이 아니라 400 이다")
    void unknownFilterIsRejected() throws Exception {
        /*
         * 조용히 무시하면 전체 목록이 나간다. 프론트가 오타를 내도 화면은 그럴듯하게
         * 채워져서 아무도 눈치채지 못한다. 거부해야 버그가 드러난다.
         */
        mockMvc.perform(get("/api/products").param("category", "KNITWEAR"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("아직 안 정해진 값은 필드가 빠지는 게 아니라 null 로 나간다")
    void unsetValuesStayNull() throws Exception {
        Product noPrice = new Product(UUID.randomUUID(), "priced-later",
                ProductCategory.SHIRT, ProductLine.LEONE);
        noPrice.setName("가격 미정 셔츠");
        noPrice.setPriceKrw(1L); // publish 조건을 채우기 위한 임시값
        noPrice.setLeadTimeDays((short) 10);
        noPrice.addImage(new ProductImage(UUID.randomUUID(), media("products/priced-later.webp"),
                ProductImageKind.MAIN, "대표", 0));
        noPrice.publish();
        noPrice.setPriceKrw(null); // 공개된 뒤 가격이 비워진 상태를 재현한다
        products.saveAndFlush(noPrice);

        /*
         * "필드가 없다" 와 "값이 없다" 는 프론트에서 다르게 다뤄야 한다.
         * NON_NULL 로 빼버리면 프론트는 둘을 구분할 수 없다.
         */
        mockMvc.perform(get("/api/products/priced-later"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.priceKrw").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"priceKrw\":null")));
    }
}
