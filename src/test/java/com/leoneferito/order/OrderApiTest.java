package com.leoneferito.order;

import static com.leoneferito.member.MemberTestSupport.csrf;
import static com.leoneferito.member.MemberTestSupport.login;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import com.leoneferito.product.ProductSku;
import com.sun.net.httpserver.HttpServer;
import jakarta.servlet.http.Cookie;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 장바구니 · 주문 · 결제 · 취소 · 관리자 처리.
 *
 * <p>진짜 토스 대신 이 JVM 안에 가짜 토스 서버를 띄운다. 무엇이 몇 번, 얼마로 불렸는지 센다 —
 * 이 테스트가 지키려는 건 "금액을 바꿔 보내도 결제되지 않는다", "두 번 눌러도 한 번만 승인된다",
 * "환불이 먼저 성공해야 취소된다" 같은 돈의 속성이다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestMailConfiguration.class})
class OrderApiTest {

    // ── 가짜 토스 ────────────────────────────────────────────────

    record TossCall(String path, String body, String idempotencyKey, String authorization) {
    }

    static final List<TossCall> TOSS_CALLS = new CopyOnWriteArrayList<>();
    static final HttpServer TOSS;

    static {
        try {
            TOSS = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        TOSS.createContext("/v1/payments", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String path = exchange.getRequestURI().getPath();
            TOSS_CALLS.add(new TossCall(path, body,
                    exchange.getRequestHeaders().getFirst("Idempotency-Key"),
                    exchange.getRequestHeaders().getFirst("Authorization")));
            String json;
            int status = 200;
            if (path.endsWith("/confirm")) {
                String paymentKey = JsonPath.read(body, "$.paymentKey");
                if (paymentKey.startsWith("slow")) {
                    // 동시 승인 테스트용 — 응답을 늦춰 두 요청이 겹치게 만든다.
                    try {
                        Thread.sleep(400);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                if (paymentKey.startsWith("reject")) {
                    status = 400;
                    json = "{\"code\":\"REJECT_CARD_COMPANY\",\"message\":\"카드사에서 거절했습니다.\"}";
                } else {
                    Number amount = JsonPath.read(body, "$.amount");
                    json = """
                            {"paymentKey":"%s","orderId":"%s","status":"DONE","method":"카드",
                             "totalAmount":%d,"balanceAmount":%d,"approvedAt":"2026-10-01T12:00:00+09:00",
                             "somethingNew":"무시돼야 한다"}
                            """.formatted(paymentKey, JsonPath.read(body, "$.orderId"),
                            amount.longValue(), amount.longValue());
                }
            } else {
                json = "{\"paymentKey\":\"x\",\"status\":\"CANCELED\",\"totalAmount\":0}";
            }
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        TOSS.start();
    }

    @DynamicPropertySource
    static void toss(DynamicPropertyRegistry registry) {
        registry.add("app.payment.toss.secret-key", () -> "test_sk_fake_for_tests");
        registry.add("app.payment.toss.api-base", () -> "http://127.0.0.1:" + TOSS.getAddress().getPort());
        registry.add("app.order.shipping-fee-krw", () -> "3000");
        registry.add("app.order.free-shipping-threshold-krw", () -> "500000");
    }

    // ── 준비 ────────────────────────────────────────────────────

    private static final String PASSWORD = "long enough secret 9";
    private static final String BUYER = "buyer@example.com";
    private static final String OTHER = "other@example.com";
    private static final String ADMIN = "ops@example.com";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private AuthService authService;

    @Autowired
    private AdminMemberService adminMembers;

    @Autowired
    private ProductRepository products;

    @Autowired
    private MediaAssetRepository mediaAssets;

    private UUID buyerId;
    private Cookie buyer;

    @BeforeEach
    void setUp() throws Exception {
        cleanUp();
        TOSS_CALLS.clear();
        buyerId = authService.signup(BUYER, PASSWORD, "김구매", "010-1111-2222");
        authService.signup(OTHER, PASSWORD, "남의사람", null);
        authService.signup(ADMIN, PASSWORD, "운영자", null);
        adminMembers.createOrPromoteByCommand(ADMIN, "운영자", MemberRole.ADMIN);
        product("ot-shirt", 290_000L, "95", "100");
        product("ot-trousers", 180_000L, "30");
        buyer = login(mockMvc, BUYER, PASSWORD);
    }

    @AfterEach
    void cleanUp() {
        MemberTestSupport.cleanDatabase(jdbc);
        // 다른 테스트 클래스(상품 목록 개수를 세는)와 섞이지 않게 이 테스트의 상품을 지운다.
        products.deleteAll(products.findAll().stream().filter(p -> p.getSlug().startsWith("ot-")).toList());
    }

    private void product(String slug, long price, String... sizes) {
        MediaAsset media = mediaAssets.save(new MediaAsset(UUID.randomUUID(), "shot.webp", ImageFormat.WEBP,
                100_000L, 1200, 1600, "test/" + UUID.randomUUID() + ".webp"));
        Product p = new Product(UUID.randomUUID(), slug, ProductCategory.SHIRT, ProductLine.FERITO);
        p.setName(slug.equals("ot-shirt") ? "브라운 셔츠" : "네이비 트라우저");
        p.setPriceKrw(price);
        p.setLeadTimeDays((short) 14);
        p.setFabric("면 100%");
        p.setCare("드라이클리닝");
        p.setNotice("브라운", "레오네페리토", "대한민국", "2026년 9월");
        p.addImage(new ProductImage(UUID.randomUUID(), media, ProductImageKind.MAIN, "대표", 0));
        for (int i = 0; i < sizes.length; i++) {
            p.addSku(new ProductSku(UUID.randomUUID(), sizes[i], i));
        }
        p.publish();
        products.saveAndFlush(p);
    }

    private ResultActions send(Cookie who, org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder b,
                               String body) throws Exception {
        return mockMvc.perform(csrf(mockMvc, b, who).contentType(MediaType.APPLICATION_JSON)
                .content(body == null ? "{}" : body));
    }

    private String addToCart(String slug, String size, int qty) throws Exception {
        String res = send(buyer, post("/api/cart/items"),
                "{\"slug\":\"%s\",\"size\":\"%s\",\"quantity\":%d}".formatted(slug, size, qty))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<String> ids = JsonPath.read(res, "$.items[*].id");
        return ids.getLast();
    }

    private String orderBody(String... cartItemIds) {
        String ids = String.join(",", java.util.Arrays.stream(cartItemIds).map(i -> "\"" + i + "\"").toList());
        return """
                {"cartItemIds":[%s],"recipientName":"김구매","recipientPhone":"010-1111-2222",
                 "zipCode":"16677","address1":"수원시 영통구 매영로 1","address2":"101호","agree":true}
                """.formatted(ids);
    }

    /** 주문서를 만들고 {주문번호, 금액} 을 돌려준다. */
    private Object[] createOrder(String... cartItemIds) throws Exception {
        String res = send(buyer, post("/api/orders"), orderBody(cartItemIds))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return new Object[] {JsonPath.read(res, "$.orderNumber"), ((Number) JsonPath.read(res, "$.amount")).longValue()};
    }

    private ResultActions confirm(String orderNumber, String paymentKey, long amount) throws Exception {
        return send(buyer, post("/api/orders/" + orderNumber + "/confirm"),
                "{\"paymentKey\":\"%s\",\"amount\":%d}".formatted(paymentKey, amount));
    }

    private String paidOrder() throws Exception {
        Object[] o = createOrder(addToCart("ot-shirt", "100", 1));
        confirm((String) o[0], "pk_" + UUID.randomUUID(), (long) o[1]).andExpect(status().isOk());
        return (String) o[0];
    }

    private long tossCalls(String suffix) {
        return TOSS_CALLS.stream().filter(c -> c.path().endsWith(suffix)).count();
    }

    // ── 장바구니 ────────────────────────────────────────────────

    @Nested
    @DisplayName("장바구니")
    class Cart {

        @Test
        @DisplayName("로그인해야 쓸 수 있다 — 비회원 주문은 없다")
        void requiresLogin() throws Exception {
            mockMvc.perform(get("/api/cart")).andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("같은 상품·사이즈를 다시 담으면 수량이 늘고, 합계와 배송비는 서버가 계산한다")
        void addsUpAndTotals() throws Exception {
            addToCart("ot-shirt", "100", 1);
            addToCart("ot-shirt", "100", 1);

            mockMvc.perform(get("/api/cart").cookie(buyer))
                    .andExpect(jsonPath("$.items.length()").value(1))
                    .andExpect(jsonPath("$.items[0].quantity").value(2))
                    .andExpect(jsonPath("$.itemsAmountKrw").value(580_000))
                    // 50만원 이상 무료 배송
                    .andExpect(jsonPath("$.shippingFeeKrw").value(0))
                    .andExpect(jsonPath("$.totalAmountKrw").value(580_000));
        }

        @Test
        @DisplayName("없는 사이즈 · 10벌 초과는 담기지 않는다")
        void rules() throws Exception {
            send(buyer, post("/api/cart/items"), "{\"slug\":\"ot-shirt\",\"size\":\"999\",\"quantity\":1}")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("CART_INVALID"));
            String id = addToCart("ot-shirt", "95", 9);
            send(buyer, post("/api/cart/items"), "{\"slug\":\"ot-shirt\",\"size\":\"95\",\"quantity\":2}")
                    .andExpect(status().isBadRequest());
            send(buyer, patch("/api/cart/items/" + id), "{\"quantity\":10}").andExpect(status().isOk());
        }

        @Test
        @DisplayName("다른 사람의 장바구니 줄은 고칠 수 없다")
        void notOthers() throws Exception {
            String id = addToCart("ot-shirt", "95", 1);
            Cookie other = login(mockMvc, OTHER, PASSWORD);
            send(other, patch("/api/cart/items/" + id), "{\"quantity\":5}").andExpect(status().isBadRequest());
            mockMvc.perform(get("/api/cart").cookie(buyer)).andExpect(jsonPath("$.items[0].quantity").value(1));
        }

        @Test
        @DisplayName("담은 뒤 상품이 내려가면 줄은 남되 '주문할 수 없음' 이 되고 합계에서 빠진다")
        void unpublishedStaysButUnavailable() throws Exception {
            addToCart("ot-shirt", "100", 1);
            Product p = products.findAll().stream().filter(x -> x.getSlug().equals("ot-shirt")).findFirst().orElseThrow();
            p.unpublish();
            products.saveAndFlush(p);

            mockMvc.perform(get("/api/cart").cookie(buyer))
                    .andExpect(jsonPath("$.items[0].available").value(false))
                    .andExpect(jsonPath("$.items[0].unavailableReason").value("판매가 끝난 상품입니다."))
                    .andExpect(jsonPath("$.itemsAmountKrw").value(0));
        }
    }

    // ── 주문 · 결제 ─────────────────────────────────────────────

    @Nested
    @DisplayName("주문 · 결제")
    class Payment {

        @Test
        @DisplayName("주문서 금액은 서버가 계산한다 — 상품 합계 + 배송비")
        void serverComputesAmount() throws Exception {
            Object[] o = createOrder(addToCart("ot-trousers", "30", 2));
            assertThat((long) o[1]).isEqualTo(360_000L + 3_000L);
            assertThat((String) o[0]).matches("^LF\\d{8}-[A-Z2-9]{8}$");
        }

        @Test
        @DisplayName("주문서 화면의 금액(quote)과 실제 결제 금액이 같다 — 고른 줄만으로 계산한다")
        void quoteMatchesOrder() throws Exception {
            String trousers = addToCart("ot-trousers", "30", 1);
            addToCart("ot-shirt", "100", 2); // 장바구니에는 있지만 이번 주문에는 안 고른다
            String quote = send(buyer, post("/api/orders/quote"), "{\"cartItemIds\":[\"" + trousers + "\"]}")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.itemsAmountKrw").value(180_000))
                    .andExpect(jsonPath("$.shippingFeeKrw").value(3_000))
                    .andExpect(jsonPath("$.longestLeadTimeDays").value(14))
                    .andReturn().getResponse().getContentAsString();
            Object[] o = createOrder(trousers);
            assertThat(((Number) JsonPath.read(quote, "$.totalAmountKrw")).longValue()).isEqualTo((long) o[1]);
        }

        @Test
        @DisplayName("결제 전 확인(agree) 없이는 주문서를 만들 수 없다")
        void agreeRequired() throws Exception {
            String id = addToCart("ot-shirt", "100", 1);
            send(buyer, post("/api/orders"), orderBody(id).replace("\"agree\":true", "\"agree\":false"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("화면이 금액을 바꿔 보내면 토스를 부르지도 않고 거절한다")
        void tamperedAmount() throws Exception {
            Object[] o = createOrder(addToCart("ot-shirt", "100", 1));
            confirm((String) o[0], "pk_tamper", 1000L)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("AMOUNT_MISMATCH"));
            assertThat(tossCalls("/confirm")).isZero();
        }

        @Test
        @DisplayName("승인되면 결제 완료가 되고, 결제한 줄은 장바구니에서 빠지고, 토스에는 저장된 금액이 간다")
        void confirmPays() throws Exception {
            String shirt = addToCart("ot-shirt", "100", 1);
            addToCart("ot-trousers", "30", 1); // 이건 주문하지 않는다
            Object[] o = createOrder(shirt);

            confirm((String) o[0], "pk_ok", (long) o[1])
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("PAID"))
                    .andExpect(jsonPath("$.paymentMethod").value("카드"))
                    .andExpect(jsonPath("$.cancellable").value(true));

            TossCall call = TOSS_CALLS.getFirst();
            assertThat(call.body()).contains("\"amount\":" + o[1]).contains("\"orderId\":\"" + o[0] + "\"");
            assertThat(call.idempotencyKey()).isEqualTo("confirm-" + o[0]);
            // Basic base64("test_sk_fake_for_tests:")
            assertThat(call.authorization()).isEqualTo("Basic " + java.util.Base64.getEncoder()
                    .encodeToString("test_sk_fake_for_tests:".getBytes(StandardCharsets.UTF_8)));

            mockMvc.perform(get("/api/cart").cookie(buyer))
                    .andExpect(jsonPath("$.items.length()").value(1))
                    .andExpect(jsonPath("$.items[0].slug").value("ot-trousers"));
        }

        @Test
        @DisplayName("성공 화면을 새로고침해 승인이 두 번 와도 토스는 한 번만 부른다")
        void confirmIsIdempotent() throws Exception {
            Object[] o = createOrder(addToCart("ot-shirt", "100", 1));
            confirm((String) o[0], "pk_twice", (long) o[1]).andExpect(status().isOk());
            confirm((String) o[0], "pk_twice", (long) o[1]).andExpect(status().isOk());
            assertThat(tossCalls("/confirm")).isEqualTo(1);
        }

        @Test
        @DisplayName("승인 요청이 동시에 두 번 와도(더블클릭 · 새로고침) 토스 승인은 한 번이고 둘 다 결제 완료를 받는다")
        void concurrentConfirm() throws Exception {
            Object[] o = createOrder(addToCart("ot-shirt", "100", 1));
            java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
            java.util.concurrent.Callable<Integer> call = () -> confirm((String) o[0], "slow_pk", (long) o[1])
                    .andReturn().getResponse().getStatus();
            var a = pool.submit(call);
            var b = pool.submit(call);
            assertThat(a.get()).isEqualTo(200);
            assertThat(b.get()).isEqualTo(200);
            pool.shutdown();
            assertThat(tossCalls("/confirm")).isEqualTo(1);
        }

        @Test
        @DisplayName("토스가 거절하면 그 메시지를 보여주고, 주문서와 장바구니는 그대로라 다시 시도할 수 있다")
        void rejected() throws Exception {
            Object[] o = createOrder(addToCart("ot-shirt", "100", 1));
            confirm((String) o[0], "reject_card", (long) o[1])
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("PAYMENT_FAILED"))
                    .andExpect(jsonPath("$.message").value("카드사에서 거절했습니다."));

            assertThat(jdbc.queryForObject("SELECT status FROM orders WHERE order_number = ?",
                    String.class, o[0])).isEqualTo("PENDING_PAYMENT");
            mockMvc.perform(get("/api/cart").cookie(buyer)).andExpect(jsonPath("$.items.length()").value(1));
        }

        @Test
        @DisplayName("남의 주문은 볼 수도 승인할 수도 없다 — 없는 주문과 같은 404")
        void notOthers() throws Exception {
            String orderNumber = paidOrder();
            Cookie other = login(mockMvc, OTHER, PASSWORD);
            mockMvc.perform(get("/api/orders/" + orderNumber).cookie(other)).andExpect(status().isNotFound());
            send(other, post("/api/orders/" + orderNumber + "/cancel"), null).andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("주문 뒤 상품 가격을 바꿔도 주문서의 가격은 그대로다")
        void priceIsSnapshot() throws Exception {
            String orderNumber = paidOrder();
            Product p = products.findAll().stream().filter(x -> x.getSlug().equals("ot-shirt")).findFirst().orElseThrow();
            p.setPriceKrw(1L);
            p.setName("바뀐 이름");
            products.saveAndFlush(p);

            mockMvc.perform(get("/api/orders/" + orderNumber).cookie(buyer))
                    .andExpect(jsonPath("$.items[0].unitPriceKrw").value(290_000))
                    .andExpect(jsonPath("$.items[0].name").value("브라운 셔츠"))
                    .andExpect(jsonPath("$.items[0].leadTimeDays").value(14));
        }

        @Test
        @DisplayName("결제 전에 떠난 주문서는 내 주문 목록에 나오지 않는다")
        void pendingHidden() throws Exception {
            createOrder(addToCart("ot-shirt", "100", 1));
            String paid = paidOrder();
            mockMvc.perform(get("/api/orders").cookie(buyer))
                    .andExpect(jsonPath("$.length()").value(1))
                    .andExpect(jsonPath("$[0].orderNumber").value(paid));
        }

        @Test
        @DisplayName("진행 중인 주문이 있으면 탈퇴할 수 없다 — 환불받을 곳이 사라진다")
        void noWithdrawWithOrders() throws Exception {
            paidOrder();
            send(buyer, post("/api/me/withdraw"), "{\"password\":\"%s\"}".formatted(PASSWORD))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("ORDERS_IN_PROGRESS"));
        }
    }

    // ── 취소 · 관리자 ───────────────────────────────────────────

    @Nested
    @DisplayName("취소 · 관리자 처리")
    class Fulfilment {

        @Test
        @DisplayName("손님은 제작 전에 취소할 수 있고, 토스 환불(멱등키 포함)이 먼저 간다")
        void customerCancel() throws Exception {
            String orderNumber = paidOrder();
            send(buyer, post("/api/orders/" + orderNumber + "/cancel"), null)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("CANCELLED"))
                    .andExpect(jsonPath("$.refundedAmountKrw").value(290_000 + 3_000));
            TossCall cancel = TOSS_CALLS.stream().filter(c -> c.path().endsWith("/cancel")).findFirst().orElseThrow();
            assertThat(cancel.path()).contains("/v1/payments/pk_");
            assertThat(cancel.idempotencyKey()).isEqualTo("cancel-" + orderNumber);
        }

        @Test
        @DisplayName("관리자: 제작 시작 → 발송(송장) → 배송 완료. 제작이 시작되면 손님은 직접 취소하지 못한다")
        void adminFlow() throws Exception {
            String orderNumber = paidOrder();
            Cookie admin = login(mockMvc, ADMIN, PASSWORD);

            send(admin, post("/api/admin/orders/" + orderNumber + "/start-production"), null)
                    .andExpect(status().isNoContent());
            send(buyer, post("/api/orders/" + orderNumber + "/cancel"), null)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("ORDER_STATE"));

            send(admin, post("/api/admin/orders/" + orderNumber + "/ship"), "{\"courier\":\"CJ대한통운\"}")
                    .andExpect(status().isBadRequest());
            send(admin, post("/api/admin/orders/" + orderNumber + "/ship"),
                    "{\"courier\":\"CJ대한통운\",\"trackingNumber\":\"123456789012\"}")
                    .andExpect(status().isNoContent());
            send(admin, post("/api/admin/orders/" + orderNumber + "/deliver"), null)
                    .andExpect(status().isNoContent());

            mockMvc.perform(get("/api/orders/" + orderNumber).cookie(buyer))
                    .andExpect(jsonPath("$.status").value("DELIVERED"))
                    .andExpect(jsonPath("$.trackingNumber").value("123456789012"))
                    .andExpect(jsonPath("$.events.length()").value(5)); // 작성·결제·제작·발송·완료

            mockMvc.perform(get("/api/admin/orders/" + orderNumber).cookie(admin))
                    .andExpect(jsonPath("$.paymentKey").exists())
                    .andExpect(jsonPath("$.events[2].byAdmin").value(true));
        }

        @Test
        @DisplayName("관리자는 제작 중에도 취소·환불할 수 있지만, 발송 뒤에는 반품으로 가야 한다")
        void adminCancelRules() throws Exception {
            Cookie admin = login(mockMvc, ADMIN, PASSWORD);
            String inProduction = paidOrder();
            send(admin, post("/api/admin/orders/" + inProduction + "/start-production"), null);
            send(admin, post("/api/admin/orders/" + inProduction + "/cancel"), "{\"reason\":\"원단 수급 불가\"}")
                    .andExpect(status().isNoContent());
            assertThat(tossCalls("/cancel")).isEqualTo(1);

            // 장바구니가 비었으니 새로 담는다
            String shipped = paidOrder();
            send(admin, post("/api/admin/orders/" + shipped + "/ship"),
                    "{\"courier\":\"CJ대한통운\",\"trackingNumber\":\"1\"}").andExpect(status().isNoContent());
            send(admin, post("/api/admin/orders/" + shipped + "/cancel"), "{\"reason\":\"x\"}")
                    .andExpect(status().isConflict());
            assertThat(tossCalls("/cancel")).isEqualTo(1);
        }

        @Test
        @DisplayName("관리자 주문 목록은 일반 회원에게 닫혀 있고, 검색은 받는 분 이름·연락처로 된다")
        void adminList() throws Exception {
            String orderNumber = paidOrder();
            mockMvc.perform(get("/api/admin/orders").cookie(buyer)).andExpect(status().isForbidden());

            Cookie admin = login(mockMvc, ADMIN, PASSWORD);
            mockMvc.perform(get("/api/admin/orders").param("q", "11112222").cookie(admin))
                    .andExpect(jsonPath("$.totalElements").value(1))
                    .andExpect(jsonPath("$.items[0].orderNumber").value(orderNumber));
            mockMvc.perform(get("/api/admin/orders").param("q", "김구매").cookie(admin))
                    .andExpect(jsonPath("$.totalElements").value(1));
        }
    }
}
