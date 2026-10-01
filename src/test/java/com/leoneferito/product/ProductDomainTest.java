package com.leoneferito.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.leoneferito.TestcontainersConfiguration;
import com.leoneferito.media.ImageFormat;
import com.leoneferito.media.MediaAsset;
import com.leoneferito.media.MediaAssetRepository;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 상품 도메인(V3) 검증.
 *
 * <p>여기서 확인하는 것은 두 가지다:
 * <ol>
 *   <li><b>매핑이 스키마와 맞는가</b> — 컨텍스트가 뜨는 것 자체가 증거다.
 *       {@code ddl-auto: validate} 라서 엔티티와 테이블이 한 글자라도 어긋나면 기동이 실패한다.</li>
 *   <li><b>DB 가 실제로 막아주는가</b> — 제약은 "적어 놨다" 가 아니라 "위반이 거부된다" 로만 증명된다.
 *       애플리케이션 검증은 우회 경로(관리자 SQL·배치)가 생기면 뚫린다.</li>
 * </ol>
 *
 * <p>제약 검증은 JPA 가 아니라 {@link JdbcTemplate} 로 한다. JPA 를 거치면 Hibernate 가
 * 먼저 걸러내는 경우가 있어 <b>DB 가 막은 건지 애플리케이션이 막은 건지 구분되지 않는다.</b>
 * 원시 SQL 로 밀어넣어야 DB 제약을 실제로 시험한 게 된다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class ProductDomainTest {

    @Autowired
    private ProductRepository products;

    @Autowired
    private MediaAssetRepository mediaAssets;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private EntityManager em;

    private MediaAsset newMedia() {
        return mediaAssets.save(new MediaAsset(
                UUID.randomUUID(), "shot.webp", ImageFormat.WEBP, 120_000L, 1200, 1600,
                "media/" + UUID.randomUUID()));
    }

    private Product newProduct(String slug, ProductCategory category, ProductLine line) {
        return new Product(UUID.randomUUID(), slug, category, line);
    }

    /** 제약 위반 SQL 을 직접 밀어넣기 위한 최소 행. */
    private UUID insertRawProduct(String slug, String status) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO product (id, slug, category, line, status)
                VALUES (?, ?, 'SHIRT', 'FERITO', ?)
                """, id, slug, status);
        return id;
    }

    @Nested
    @DisplayName("저장과 조회")
    class Persistence {

        @Test
        @DisplayName("상품 · 사진 · 사이즈 · 실측이 한 덩어리로 저장되고 다시 읽힌다")
        void roundTrip() {
            Product p = newProduct("brown-shirt", ProductCategory.SHIRT, ProductLine.FERITO);
            p.setName("브라운 셔츠");
            p.setPriceKrw(290_000L);
            p.setLeadTimeDays((short) 14);
            p.addImage(new ProductImage(UUID.randomUUID(), newMedia(),
                    ProductImageKind.MAIN, "브라운 셔츠 측면 컷", 0));

            ProductSku sku = new ProductSku(UUID.randomUUID(), "100", 1);
            sku.addMeasurement(new ProductMeasurement(UUID.randomUUID(),
                    MeasurementPart.SHOULDER, new BigDecimal("46.5"), new BigDecimal("1.0")));
            p.addSku(sku);

            products.saveAndFlush(p);
            em.clear();

            Product found = products.findById(p.getId()).orElseThrow();
            assertThat(found.getLine()).isEqualTo(ProductLine.FERITO);
            assertThat(found.getCategory()).isEqualTo(ProductCategory.SHIRT);
            assertThat(found.mainImage()).isPresent();
            assertThat(found.getSkus()).hasSize(1);

            ProductMeasurement m = found.getSkus().get(0).getMeasurements().get(0);
            assertThat(m.getPart()).isEqualTo(MeasurementPart.SHOULDER);
            // NUMERIC(5,1) 이므로 소수 한 자리가 보존된다. 46.5 가 46 이나 46.50 이 되면 안 된다.
            assertThat(m.getValueCm()).isEqualByComparingTo("46.5");
        }

        @Test
        @DisplayName("사이즈는 문자열이지만 sort_order 로 95 → 100 순서가 지켜진다")
        void sizesKeepNumericOrder() {
            Product p = newProduct("ordering", ProductCategory.SHIRT, ProductLine.LEONE);
            // 일부러 거꾸로 넣는다. 문자열 정렬이면 '100' 이 '95' 앞에 온다.
            p.addSku(new ProductSku(UUID.randomUUID(), "100", 2));
            p.addSku(new ProductSku(UUID.randomUUID(), "95", 1));
            products.saveAndFlush(p);
            em.clear();

            List<String> sizes = products.findById(p.getId()).orElseThrow()
                    .getSkus().stream().map(ProductSku::getSize).toList();
            assertThat(sizes).containsExactly("95", "100");
        }

        /**
         * V1 의 공용 트리거가 product 에도 붙었는지 확인한다.
         *
         * <p><b>이 테스트만 트랜잭션 밖에서 돈다.</b> PostgreSQL 의 {@code now()} 는
         * 문장 시각이 아니라 <b>트랜잭션 시작 시각</b>이다. 한 트랜잭션 안에서 INSERT 와 UPDATE 를
         * 하면 두 시각이 같아서, 트리거가 정상 동작해도 값이 안 변한 것처럼 보인다.
         * (실제 서비스에서는 요청마다 트랜잭션이 달라서 문제가 되지 않는다)
         *
         * <p>그래서 자동 커밋으로 문장을 따로 날리고, 끝나고 직접 지운다.
         */
        @Test
        @DisplayName("UPDATE 하면 updated_at 이 오르고, 값이 그대로면 오르지 않는다")
        @Transactional(propagation = Propagation.NOT_SUPPORTED)
        void updatedAtTriggerFires() {
            UUID id = insertRawProduct("touch-me", "DRAFT");
            try {
                OffsetDateTime before = readUpdatedAt(id);

                jdbc.update("UPDATE product SET name = '이름이 생겼다' WHERE id = ?", id);
                OffsetDateTime afterChange = readUpdatedAt(id);
                assertThat(afterChange).isAfter(before);

                // 같은 값으로 다시 UPDATE — V1 트리거의 IS NOT DISTINCT FROM 가드가 막아야 한다.
                jdbc.update("UPDATE product SET name = '이름이 생겼다' WHERE id = ?", id);
                assertThat(readUpdatedAt(id)).isEqualTo(afterChange);
            } finally {
                jdbc.update("DELETE FROM product WHERE id = ?", id);
            }
        }

        private OffsetDateTime readUpdatedAt(UUID id) {
            return jdbc.queryForObject(
                    "SELECT updated_at FROM product WHERE id = ?", OffsetDateTime.class, id);
        }
    }

    @Nested
    @DisplayName("공개 조회")
    class PublicQueries {

        @Test
        @DisplayName("DRAFT 는 목록에 나오지 않는다")
        void draftIsHidden() {
            insertRawProduct("hidden-draft", "DRAFT");
            insertRawProduct("visible", "PUBLISHED");
            em.clear();

            assertThat(products.findPublished())
                    .extracting(Product::getSlug)
                    .contains("visible")
                    .doesNotContain("hidden-draft");
        }

        @Test
        @DisplayName("slug 로 찾을 때도 DRAFT 는 없는 것으로 취급한다")
        void draftIsNotReachableBySlug() {
            insertRawProduct("secret", "DRAFT");
            em.clear();

            assertThat(products.findPublishedBySlug("secret")).isEmpty();
        }

        @Test
        @DisplayName("카테고리로 거른다")
        void filtersByCategory() {
            insertRawProduct("a-shirt", "PUBLISHED"); // 원시 삽입은 전부 SHIRT
            em.clear();

            assertThat(products.findPublishedByCategory(ProductCategory.SHIRT))
                    .extracting(Product::getSlug).contains("a-shirt");
            assertThat(products.findPublishedByCategory(ProductCategory.SHOES))
                    .extracting(Product::getSlug).doesNotContain("a-shirt");
        }
    }

    @Nested
    @DisplayName("DB 제약")
    class Constraints {

        @Test
        @DisplayName("정의되지 않은 라인은 거부된다")
        void unknownLineRejected() {
            assertThatThrownBy(() -> jdbc.update("""
                    INSERT INTO product (id, slug, category, line, status)
                    VALUES (?, 'bad-line', 'SHIRT', 'ATHLETIC', 'DRAFT')
                    """, UUID.randomUUID()))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }

        @Test
        @DisplayName("정의되지 않은 카테고리는 거부된다")
        void unknownCategoryRejected() {
            assertThatThrownBy(() -> jdbc.update("""
                    INSERT INTO product (id, slug, category, line, status)
                    VALUES (?, 'bad-cat', 'KNITWEAR', 'LEONE', 'DRAFT')
                    """, UUID.randomUUID()))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }

        @Test
        @DisplayName("정가가 판매가보다 낮으면 거부된다 — 할인 표시가 거꾸로 된다")
        void listPriceBelowPriceRejected() {
            assertThatThrownBy(() -> jdbc.update("""
                    INSERT INTO product (id, slug, category, line, status, price_krw, list_price_krw)
                    VALUES (?, 'bad-price', 'SHIRT', 'LEONE', 'DRAFT', 300000, 200000)
                    """, UUID.randomUUID()))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }

        @Test
        @DisplayName("같은 slug 가 두 번 들어가지 않는다 — URL 이 겹친다")
        void duplicateSlugRejected() {
            insertRawProduct("same-slug", "DRAFT");
            assertThatThrownBy(() -> insertRawProduct("same-slug", "DRAFT"))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }

        @Test
        @DisplayName("대표 이미지는 상품당 한 장뿐이다")
        void onlyOneMainImage() {
            Product p = newProduct("two-mains", ProductCategory.SHIRT, ProductLine.LEONE);
            p.addImage(new ProductImage(UUID.randomUUID(), newMedia(),
                    ProductImageKind.MAIN, "첫 번째", 0));
            p.addImage(new ProductImage(UUID.randomUUID(), newMedia(),
                    ProductImageKind.MAIN, "두 번째", 1));

            assertThatThrownBy(() -> products.saveAndFlush(p))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }

        @Test
        @DisplayName("대표가 아닌 사진은 여러 장 가능하다")
        void manyDetailImagesAllowed() {
            Product p = newProduct("many-details", ProductCategory.SHIRT, ProductLine.LEONE);
            p.addImage(new ProductImage(UUID.randomUUID(), newMedia(),
                    ProductImageKind.DETAIL, "깃 디테일", 0));
            p.addImage(new ProductImage(UUID.randomUUID(), newMedia(),
                    ProductImageKind.DETAIL, "소매 디테일", 1));

            assertThatCode(() -> products.saveAndFlush(p)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("한 상품에 같은 사이즈가 두 번 들어가지 않는다")
        void duplicateSizeRejected() {
            Product p = newProduct("dup-size", ProductCategory.TROUSERS, ProductLine.FERITO);
            p.addSku(new ProductSku(UUID.randomUUID(), "100", 0));
            p.addSku(new ProductSku(UUID.randomUUID(), "100", 1));

            assertThatThrownBy(() -> products.saveAndFlush(p))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }

        @Test
        @DisplayName("한 사이즈에 같은 부위 실측이 두 번 들어가지 않는다")
        void duplicateMeasurementPartRejected() {
            Product p = newProduct("dup-part", ProductCategory.SHIRT, ProductLine.LEONE);
            ProductSku sku = new ProductSku(UUID.randomUUID(), "100", 0);
            sku.addMeasurement(new ProductMeasurement(UUID.randomUUID(),
                    MeasurementPart.CHEST, new BigDecimal("52.0"), null));
            sku.addMeasurement(new ProductMeasurement(UUID.randomUUID(),
                    MeasurementPart.CHEST, new BigDecimal("53.0"), null));
            p.addSku(sku);

            assertThatThrownBy(() -> products.saveAndFlush(p))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }

        @Test
        @DisplayName("상품을 지우면 사진 · 사이즈 · 실측이 함께 사라진다")
        void cascadeDelete() {
            Product p = newProduct("cascade", ProductCategory.SHIRT, ProductLine.LEONE);
            ProductSku sku = new ProductSku(UUID.randomUUID(), "100", 0);
            sku.addMeasurement(new ProductMeasurement(UUID.randomUUID(),
                    MeasurementPart.WAIST, new BigDecimal("40.0"), null));
            p.addSku(sku);
            products.saveAndFlush(p);

            products.delete(p);
            products.flush();
            em.clear();

            Integer left = jdbc.queryForObject(
                    "SELECT count(*) FROM product_measurement WHERE product_sku_id = ?",
                    Integer.class, sku.getId());
            assertThat(left).isZero();
        }
    }

    @Nested
    @DisplayName("공개 가능 여부")
    class Publishing {

        @Test
        @DisplayName("제작 기간이 없으면 공개할 수 없다 — 결제 전 고지 의무 대상이다")
        void cannotPublishWithoutLeadTime() {
            Product p = newProduct("no-lead-time", ProductCategory.SHIRT, ProductLine.LEONE);
            p.setName("이름 있음");
            p.setPriceKrw(290_000L);
            p.addImage(new ProductImage(UUID.randomUUID(), newMedia(),
                    ProductImageKind.MAIN, "대표", 0));

            assertThat(p.isPublishable()).isFalse();
            assertThatThrownBy(p::publish).isInstanceOf(IllegalStateException.class);
            assertThat(p.getStatus()).isEqualTo(ProductStatus.DRAFT);
        }

        @Test
        @DisplayName("대표 이미지가 없으면 공개할 수 없다")
        void cannotPublishWithoutMainImage() {
            Product p = newProduct("no-image", ProductCategory.SHIRT, ProductLine.LEONE);
            p.setName("이름 있음");
            p.setPriceKrw(290_000L);
            p.setLeadTimeDays((short) 14);

            assertThat(p.isPublishable()).isFalse();
        }

        @Test
        @DisplayName("이름 · 가격 · 제작 기간 · 대표 이미지가 모두 있으면 공개된다")
        void publishesWhenComplete() {
            Product p = newProduct("ready", ProductCategory.SHIRT, ProductLine.FERITO);
            p.setName("브라운 셔츠");
            p.setPriceKrw(290_000L);
            p.setLeadTimeDays((short) 14);
            p.addImage(new ProductImage(UUID.randomUUID(), newMedia(),
                    ProductImageKind.MAIN, "대표", 0));
            p.setFabric("면 100%");
            p.setCare("드라이클리닝");
            p.setNotice("브라운", "레오네페리토", "대한민국", "2026년 9월");

            p.publish();
            assertThat(p.getStatus()).isEqualTo(ProductStatus.PUBLISHED);
        }

        @Test
        @DisplayName("상품정보제공고시가 하나라도 비면 공개할 수 없다 — 판매 전 법적 의무다")
        void cannotPublishWithoutNotice() {
            Product p = newProduct("no-notice", ProductCategory.SHIRT, ProductLine.FERITO);
            p.setName("브라운 셔츠");
            p.setPriceKrw(290_000L);
            p.setLeadTimeDays((short) 14);
            p.addImage(new ProductImage(UUID.randomUUID(), newMedia(),
                    ProductImageKind.MAIN, "대표", 0));
            p.setFabric("면 100%");
            p.setCare("드라이클리닝");
            p.setNotice("브라운", "레오네페리토", "  ", "2026년 9월"); // 제조국이 공백

            assertThat(p.missingForPublish()).containsExactly("notice");
            assertThat(p.isPublishable()).isFalse();
        }
    }
}
