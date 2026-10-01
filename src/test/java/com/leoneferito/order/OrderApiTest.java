package com.leoneferito.order;

import static com.leoneferito.member.MemberTestSupport.csrf;
import static com.leoneferito.member.MemberTestSupport.login;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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
    /** 결제 조회(GET) 에 돌려줄 답 — 웹훅 테스트가 "토스가 실제로 알고 있는 결제" 를 심는다. */
    static final java.util.Map<String, String> TOSS_PAYMENTS = new java.util.concurrent.ConcurrentHashMap<>();
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
            if ("GET".equals(exchange.getRequestMethod())) {
                String found = TOSS_PAYMENTS.get(path.substring(path.lastIndexOf('/') + 1));
                status = found == null ? 404 : 200;
                json = found == null ? "{\"code\":\"NOT_FOUND_PAYMENT\",\"message\":\"없음\"}" : found;
            } else if (path.endsWith("/confirm")) {
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
        TOSS_PAYMENTS.clear();
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

    // ── 교환 · 반품 ─────────────────────────────────────────────

    /** 결제 → 발송 → 배송 완료까지 간 주문. */
    private String deliveredOrder(Cookie admin) throws Exception {
        String no = paidOrder();
        send(admin, post("/api/admin/orders/" + no + "/ship"), "{\"courier\":\"CJ대한통운\",\"trackingNumber\":\"111\"}")
                .andExpect(status().isNoContent());
        send(admin, post("/api/admin/orders/" + no + "/deliver"), null).andExpect(status().isNoContent());
        return no;
    }

    private String firstItemId(String orderNumber) throws Exception {
        String res = mockMvc.perform(get("/api/orders/" + orderNumber).cookie(buyer))
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(res, "$.items[0].id");
    }

    private ResultActions requestReturn(String orderNumber, String type, String reason, String itemId, int qty,
                                        String size) throws Exception {
        return send(buyer, post("/api/orders/" + orderNumber + "/returns"), """
                {"type":"%s","reason":"%s","detail":"어깨가 낍니다","items":[{"orderItemId":"%s","quantity":%d%s}]}
                """.formatted(type, reason, itemId, qty, size == null ? "" : ",\"exchangeSize\":\"" + size + "\""));
    }

    private String returnId(ResultActions created) throws Exception {
        return JsonPath.read(created.andReturn().getResponse().getContentAsString(), "$.id");
    }

    @Nested
    @DisplayName("교환 · 반품")
    class Returns {

        private Cookie admin;

        @BeforeEach
        void admin() throws Exception {
            admin = login(mockMvc, ADMIN, PASSWORD);
        }

        @Test
        @DisplayName("배송이 끝나기 전에는 신청할 수 없고, 주문 상세도 '신청 불가' 다")
        void onlyAfterDelivery() throws Exception {
            String no = paidOrder();
            mockMvc.perform(get("/api/orders/" + no).cookie(buyer))
                    .andExpect(jsonPath("$.returnable").value(false))
                    .andExpect(jsonPath("$.returns.length()").value(0));
            requestReturn(no, "RETURN", "SIZE", firstItemId(no), 1, null)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("ORDER_STATE"));
        }

        @Test
        @DisplayName("신청하면 주문 상세에 붙고, 진행 중에는 또 신청할 수 없다")
        void requestShowsOnOrder() throws Exception {
            String no = deliveredOrder(admin);
            mockMvc.perform(get("/api/orders/" + no).cookie(buyer))
                    .andExpect(jsonPath("$.returnable").value(true))
                    .andExpect(jsonPath("$.changeOfMindDeadline").isNotEmpty());

            requestReturn(no, "EXCHANGE", "SIZE", firstItemId(no), 1, "95")
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.status").value("REQUESTED"))
                    .andExpect(jsonPath("$.items[0].size").value("100"))
                    .andExpect(jsonPath("$.items[0].exchangeSize").value("95"))
                    .andExpect(jsonPath("$.withdrawable").value(true));

            mockMvc.perform(get("/api/orders/" + no).cookie(buyer))
                    .andExpect(jsonPath("$.returnable").value(false))
                    .andExpect(jsonPath("$.returns[0].type").value("EXCHANGE"));
            requestReturn(no, "RETURN", "SIZE", firstItemId(no), 1, null).andExpect(status().isConflict());
        }

        @Test
        @DisplayName("수량 초과 · 없는 교환 사이즈 · 남의 주문은 받지 않는다")
        void rules() throws Exception {
            String no = deliveredOrder(admin);
            String item = firstItemId(no);
            requestReturn(no, "RETURN", "SIZE", item, 2, null)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("RETURN_INVALID"));
            requestReturn(no, "EXCHANGE", "SIZE", item, 1, "999")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("RETURN_INVALID"));
            requestReturn(no, "EXCHANGE", "SIZE", item, 1, null).andExpect(status().isBadRequest());

            Cookie other = login(mockMvc, OTHER, PASSWORD);
            send(other, post("/api/orders/" + no + "/returns"), """
                    {"type":"RETURN","reason":"SIZE","items":[{"orderItemId":"%s","quantity":1}]}
                    """.formatted(item)).andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("단순 변심은 배송 완료 7일, 불량은 3개월 — 기간이 지나면 사유에 따라 갈린다")
        void deadlines() throws Exception {
            String no = deliveredOrder(admin);
            jdbc.update("UPDATE orders SET delivered_at = now() - interval '10 days' WHERE order_number = ?", no);
            requestReturn(no, "RETURN", "CHANGE_OF_MIND", firstItemId(no), 1, null)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("RETURN_INVALID"));
            requestReturn(no, "RETURN", "DEFECT", firstItemId(no), 1, null).andExpect(status().isCreated());
        }

        @Test
        @DisplayName("반품: 승인 → 회수 → 환불. 토스 부분 취소가 그 금액으로 한 번 나가고 주문 환불액에 쌓인다")
        void returnFlow() throws Exception {
            String no = deliveredOrder(admin);
            String id = returnId(requestReturn(no, "RETURN", "DEFECT", firstItemId(no), 1, null));

            // 회수 전에 환불하면 토스를 부르지 않고 거절한다
            send(admin, post("/api/admin/returns/" + id + "/refund"), "{\"refundAmountKrw\":100000}")
                    .andExpect(status().isConflict());
            assertThat(tossCalls("/cancel")).isZero();

            send(admin, post("/api/admin/returns/" + id + "/approve"), "{\"note\":\"기사님이 이틀 안에 찾아갑니다\"}")
                    .andExpect(status().isNoContent());
            send(admin, post("/api/admin/returns/" + id + "/collected"), "{}").andExpect(status().isNoContent());

            // 결제 금액(293,000)을 넘는 환불은 토스에 가기 전에 막는다
            send(admin, post("/api/admin/returns/" + id + "/refund"), "{\"refundAmountKrw\":293001}")
                    .andExpect(status().isBadRequest());
            assertThat(tossCalls("/cancel")).isZero();

            send(admin, post("/api/admin/returns/" + id + "/refund"), "{\"refundAmountKrw\":290000}")
                    .andExpect(status().isNoContent());
            assertThat(tossCalls("/cancel")).isEqualTo(1);
            TossCall cancel = TOSS_CALLS.stream().filter(c -> c.path().endsWith("/cancel")).findFirst().orElseThrow();
            assertThat(((Number) JsonPath.read(cancel.body(), "$.cancelAmount")).longValue()).isEqualTo(290_000L);
            assertThat(cancel.idempotencyKey()).isEqualTo("return-" + id);
            // 한 벌짜리 주문을 다 돌려받았으니 더 신청할 것이 없다
            mockMvc.perform(get("/api/orders/" + no).cookie(buyer)).andExpect(jsonPath("$.returnable").value(false));

            mockMvc.perform(get("/api/orders/" + no).cookie(buyer))
                    .andExpect(jsonPath("$.refundedAmountKrw").value(290_000))
                    .andExpect(jsonPath("$.returns[0].status").value("COMPLETED"))
                    .andExpect(jsonPath("$.returns[0].adminNote").value("기사님이 이틀 안에 찾아갑니다"))
                    .andExpect(jsonPath("$.returns[0].refundAmountKrw").value(290_000));

            // 다시 눌러도 두 번 환불되지 않는다
            send(admin, post("/api/admin/returns/" + id + "/refund"), "{\"refundAmountKrw\":3000}")
                    .andExpect(status().isConflict());
            assertThat(tossCalls("/cancel")).isEqualTo(1);
        }

        @Test
        @DisplayName("교환: 회수 뒤 송장을 넣으면 완료. 교환 신청에는 환불이 없다")
        void exchangeFlow() throws Exception {
            String no = deliveredOrder(admin);
            String id = returnId(requestReturn(no, "EXCHANGE", "SIZE", firstItemId(no), 1, "95"));
            send(admin, post("/api/admin/returns/" + id + "/approve"), "{}").andExpect(status().isNoContent());
            send(admin, post("/api/admin/returns/" + id + "/collected"), "{}").andExpect(status().isNoContent());
            send(admin, post("/api/admin/returns/" + id + "/refund"), "{\"refundAmountKrw\":1000}")
                    .andExpect(status().isConflict());
            send(admin, post("/api/admin/returns/" + id + "/reship"), "{\"courier\":\"CJ대한통운\",\"trackingNumber\":\"222\"}")
                    .andExpect(status().isNoContent());

            mockMvc.perform(get("/api/admin/returns/" + id).cookie(admin))
                    .andExpect(jsonPath("$.request.status").value("COMPLETED"))
                    .andExpect(jsonPath("$.request.reshipTrackingNumber").value("222"))
                    .andExpect(jsonPath("$.events.length()").value(4));
            assertThat(tossCalls("/cancel")).isZero();
        }

        @Test
        @DisplayName("승인 전에는 손님이 철회하고 다시 신청할 수 있다. 승인 뒤에는 철회할 수 없다")
        void withdraw() throws Exception {
            String no = deliveredOrder(admin);
            String first = returnId(requestReturn(no, "RETURN", "SIZE", firstItemId(no), 1, null));
            send(buyer, post("/api/returns/" + first + "/withdraw"), null)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("WITHDRAWN"));

            String second = returnId(requestReturn(no, "RETURN", "SIZE", firstItemId(no), 1, null)
                    .andExpect(status().isCreated()));
            send(admin, post("/api/admin/returns/" + second + "/approve"), "{}").andExpect(status().isNoContent());
            send(buyer, post("/api/returns/" + second + "/withdraw"), null).andExpect(status().isConflict());

            Cookie other = login(mockMvc, OTHER, PASSWORD);
            send(other, post("/api/returns/" + second + "/withdraw"), null).andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("거절하면 사유가 손님에게 보이고, 진행 중인 교환·반품이 있으면 탈퇴할 수 없다")
        void rejectAndWithdrawal() throws Exception {
            String no = deliveredOrder(admin);
            String id = returnId(requestReturn(no, "RETURN", "CHANGE_OF_MIND", firstItemId(no), 1, null));

            send(buyer, post("/api/me/withdraw"), "{\"password\":\"%s\"}".formatted(PASSWORD))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("ORDERS_IN_PROGRESS"));

            send(admin, post("/api/admin/returns/" + id + "/reject"), "{\"reason\":\"착용 흔적이 있습니다\"}")
                    .andExpect(status().isNoContent());
            mockMvc.perform(get("/api/orders/" + no).cookie(buyer))
                    .andExpect(jsonPath("$.returns[0].status").value("REJECTED"))
                    .andExpect(jsonPath("$.returns[0].rejectReason").value("착용 흔적이 있습니다"))
                    .andExpect(jsonPath("$.returnable").value(true));
        }

        private String csrfToken;

        /**
         * 실제 CSRF 토큰(쿠키 + 헤더). 스프링 시큐리티의 csrf() 테스트 도구를 쓰지 않는다 — 그건 필터의 저장소를
         * 바꿔치기해서, 같은 테스트 안의 다른 요청(진짜 쿠키로 로그인)까지 403 으로 만든다.
         */
        private Cookie csrfCookie() throws Exception {
            var res = mockMvc.perform(get("/api/auth/csrf")).andReturn().getResponse();
            csrfToken = JsonPath.read(res.getContentAsString(), "$.token");
            Cookie c = res.getCookie("XSRF-TOKEN");
            return c == null ? new Cookie("XSRF-TOKEN", csrfToken) : c;
        }

        private String uploadPhoto(Cookie who, byte[] bytes) throws Exception {
            String res = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                            .multipart("/api/returns/photos")
                            .file(new org.springframework.mock.web.MockMultipartFile("file", "defect.png", "image/png", bytes))
                            .cookie(who, csrfCookie())
                            .header("X-XSRF-TOKEN", csrfToken))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.url").isNotEmpty())
                    .andReturn().getResponse().getContentAsString();
            return JsonPath.read(res, "$.id");
        }

        private byte[] png() throws Exception {
            var img = new java.awt.image.BufferedImage(40, 30, java.awt.image.BufferedImage.TYPE_INT_RGB);
            var out = new java.io.ByteArrayOutputStream();
            javax.imageio.ImageIO.write(img, "png", out);
            return out.toByteArray();
        }

        private ResultActions requestWithPhotos(String orderNumber, String itemId, String... photoIds) throws Exception {
            String ids = String.join(",", java.util.Arrays.stream(photoIds).map(i -> "\"" + i + "\"").toList());
            return send(buyer, post("/api/orders/" + orderNumber + "/returns"), """
                    {"type":"RETURN","reason":"DEFECT","detail":"단추가 떨어져 왔습니다",
                     "items":[{"orderItemId":"%s","quantity":1}],"photoIds":[%s]}
                    """.formatted(itemId, ids));
        }

        @Test
        @DisplayName("사진: 먼저 올리고 신청에 붙인다. 남의 사진 · 이미 붙은 사진 · 이미지가 아닌 파일은 받지 않는다")
        void photos() throws Exception {
            String no = deliveredOrder(admin);
            String item = firstItemId(no);

            // 이미지가 아니면 올라가지 않는다
            mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                            .multipart("/api/returns/photos")
                            .file(new org.springframework.mock.web.MockMultipartFile("file", "x.png", "image/png",
                                    "<html>not an image</html>".getBytes(StandardCharsets.UTF_8)))
                            .cookie(buyer, csrfCookie())
                            .header("X-XSRF-TOKEN", csrfToken))
                    .andExpect(status().isBadRequest());

            // 남이 올린 사진은 붙일 수 없다
            Cookie other = login(mockMvc, OTHER, PASSWORD);
            String othersPhoto = uploadPhoto(other, png());
            requestWithPhotos(no, item, othersPhoto)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("RETURN_INVALID"));

            String mine = uploadPhoto(buyer, png());
            String id = returnId(requestWithPhotos(no, item, mine)
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.photoUrls.length()").value(1)));
            mockMvc.perform(get("/api/orders/" + no).cookie(buyer))
                    .andExpect(jsonPath("$.returns[0].photoUrls.length()").value(1));
            mockMvc.perform(get("/api/admin/returns/" + id).cookie(admin))
                    .andExpect(jsonPath("$.request.photoUrls.length()").value(1));

            // 철회 뒤 같은 사진을 다른 신청에 다시 붙일 수는 없다 — 새로 올린다
            send(buyer, post("/api/returns/" + id + "/withdraw"), null).andExpect(status().isOk());
            requestWithPhotos(no, item, mine).andExpect(status().isBadRequest());
            requestWithPhotos(no, item, uploadPhoto(buyer, png())).andExpect(status().isCreated());
        }

        @Test
        @DisplayName("관리자 목록: '처리할 것' 은 끝나지 않은 신청만, 손님은 관리자 API 를 못 쓴다")
        void adminList() throws Exception {
            String no = deliveredOrder(admin);
            requestReturn(no, "RETURN", "SIZE", firstItemId(no), 1, null).andExpect(status().isCreated());
            mockMvc.perform(get("/api/admin/returns").param("open", "true").cookie(admin))
                    .andExpect(jsonPath("$.totalElements").value(1))
                    .andExpect(jsonPath("$.items[0].orderNumber").value(no))
                    .andExpect(jsonPath("$.items[0].recipientName").value("김구매"));
            mockMvc.perform(get("/api/admin/returns").param("status", "COMPLETED").cookie(admin))
                    .andExpect(jsonPath("$.totalElements").value(0));
            mockMvc.perform(get("/api/admin/returns").cookie(buyer)).andExpect(status().isForbidden());
        }
    }

    // ── 토스 웹훅 ───────────────────────────────────────────────

    @Nested
    @DisplayName("토스 웹훅")
    class Webhook {

        private ResultActions webhook(String body) throws Exception {
            // 토스 서버가 보낸다 — 쿠키도 CSRF 토큰도 없다
            return mockMvc.perform(post("/api/payments/toss/webhook")
                    .contentType(MediaType.APPLICATION_JSON).content(body));
        }

        private void tossKnows(String paymentKey, String orderId, long amount) {
            TOSS_PAYMENTS.put(paymentKey, """
                    {"paymentKey":"%s","orderId":"%s","status":"DONE","method":"카드","totalAmount":%d,
                     "balanceAmount":%d,"approvedAt":"2026-10-01T12:00:00+09:00"}
                    """.formatted(paymentKey, orderId, amount, amount));
        }

        @Test
        @DisplayName("승인 응답을 놓친 주문을 토스에 다시 물어 결제 완료로 맞춘다")
        void recoversLostConfirm() throws Exception {
            Object[] o = createOrder(addToCart("ot-shirt", "100", 1));
            tossKnows("pk_lost", (String) o[0], (long) o[1]);

            webhook("{\"eventType\":\"PAYMENT_STATUS_CHANGED\",\"data\":{\"paymentKey\":\"pk_lost\",\"status\":\"DONE\"}}")
                    .andExpect(status().isOk());

            mockMvc.perform(get("/api/orders/" + o[0]).cookie(buyer))
                    .andExpect(jsonPath("$.status").value("PAID"));
            assertThat(tossCalls("/confirm")).isZero();
        }

        @Test
        @DisplayName("본문을 믿지 않는다 — 토스가 아는 금액이 다르면 결제로 바꾸지 않는다")
        void doesNotTrustBody() throws Exception {
            Object[] o = createOrder(addToCart("ot-shirt", "100", 1));
            tossKnows("pk_cheap", (String) o[0], 100);

            webhook("{\"eventType\":\"PAYMENT_STATUS_CHANGED\",\"data\":{\"paymentKey\":\"pk_cheap\",\"totalAmount\":%d}}"
                    .formatted((long) o[1])).andExpect(status().isOk());

            assertThat(jdbc.queryForObject("SELECT status FROM orders WHERE order_number = ?", String.class, o[0]))
                    .isEqualTo("PENDING_PAYMENT");
        }

        @Test
        @DisplayName("다른 이벤트 · 이상한 결제 키는 토스를 부르지 않고 넘긴다")
        void ignoresOthers() throws Exception {
            webhook("{\"eventType\":\"DEPOSIT_CALLBACK\",\"data\":{\"paymentKey\":\"pk_x\"}}").andExpect(status().isOk());
            webhook("{\"eventType\":\"PAYMENT_STATUS_CHANGED\",\"data\":{\"paymentKey\":\"../../v1/x\"}}")
                    .andExpect(status().isOk());
            assertThat(TOSS_CALLS).isEmpty();
        }
    }

    // ── 통계 · 진열 순서 ────────────────────────────────────────

    @Nested
    @DisplayName("관리자 통계 · 진열 순서")
    class Insights {

        @Test
        @DisplayName("사이즈별: 교환으로 나간 사이즈와 들어온 사이즈가 '남은 수' 에 반영된다. 취소 주문은 세지 않는다")
        void sizeStats() throws Exception {
            Cookie admin = login(mockMvc, ADMIN, PASSWORD);
            String no = deliveredOrder(admin);
            String id = returnId(requestReturn(no, "EXCHANGE", "SIZE", firstItemId(no), 1, "95"));
            send(admin, post("/api/admin/returns/" + id + "/approve"), "{}").andExpect(status().isNoContent());
            send(admin, post("/api/admin/returns/" + id + "/collected"), "{}").andExpect(status().isNoContent());
            send(admin, post("/api/admin/returns/" + id + "/reship"), "{\"courier\":\"CJ대한통운\",\"trackingNumber\":\"9\"}")
                    .andExpect(status().isNoContent());
            // 취소된 주문 — 통계에서 빠져야 한다
            String cancelled = paidOrder();
            send(buyer, post("/api/orders/" + cancelled + "/cancel"), null).andExpect(status().isOk());

            String res = mockMvc.perform(get("/api/admin/stats/sizes").param("days", "30").cookie(admin))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            assertThat(JsonPath.<Integer>read(res, "$.products.length()")).isEqualTo(1);
            assertThat(JsonPath.<String>read(res, "$.products[0].productName")).isEqualTo("브라운 셔츠");
            assertThat(JsonPath.<Integer>read(res, "$.products[0].sold")).isEqualTo(1);
            // 95 가 100 보다 앞 (사이즈 순서)
            assertThat(JsonPath.<String>read(res, "$.products[0].sizes[0].size")).isEqualTo("95");
            assertThat(JsonPath.<Integer>read(res, "$.products[0].sizes[0].kept")).isEqualTo(1);
            assertThat(JsonPath.<Integer>read(res, "$.products[0].sizes[0].exchangedIn")).isEqualTo(1);
            assertThat(JsonPath.<String>read(res, "$.products[0].sizes[1].size")).isEqualTo("100");
            assertThat(JsonPath.<Integer>read(res, "$.products[0].sizes[1].exchangedOut")).isEqualTo(1);
            assertThat(JsonPath.<Integer>read(res, "$.products[0].sizes[1].kept")).isZero();
        }

        @Test
        @DisplayName("진열 순서는 공개 상품 전부를 보내야 바뀌고, 손님 목록 순서가 그대로 따른다")
        void reorder() throws Exception {
            Cookie admin = login(mockMvc, ADMIN, PASSWORD);
            String list = mockMvc.perform(get("/api/products")).andReturn().getResponse().getContentAsString();
            List<String> slugs = JsonPath.read(list, "$[*].slug");
            assertThat(slugs).contains("ot-shirt", "ot-trousers");
            List<String> ids = JsonPath.read(mockMvc.perform(get("/api/admin/products").cookie(admin))
                    .andReturn().getResponse().getContentAsString(), "$[?(@.status == 'PUBLISHED')].id");
            String trousers = products.findAll().stream().filter(p -> p.getSlug().equals("ot-trousers"))
                    .findFirst().orElseThrow().getId().toString();

            send(admin, put("/api/admin/products/order"), "{\"ids\":[\"%s\"]}".formatted(trousers))
                    .andExpect(status().isConflict());

            List<String> wanted = new java.util.ArrayList<>(ids);
            wanted.remove(trousers);
            wanted.addFirst(trousers);
            send(admin, put("/api/admin/products/order"), "{\"ids\":[%s]}".formatted(
                    String.join(",", wanted.stream().map(i -> "\"" + i + "\"").toList())))
                    .andExpect(status().isNoContent());
            List<String> after = JsonPath.read(mockMvc.perform(get("/api/products"))
                    .andReturn().getResponse().getContentAsString(), "$[*].slug");
            assertThat(after.getFirst()).isEqualTo("ot-trousers");
        }
    }
}
