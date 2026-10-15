package com.leoneferito.order;

import static com.leoneferito.member.MemberTestSupport.csrf;
import static com.leoneferito.member.MemberTestSupport.login;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.leoneferito.TestMailConfiguration;
import com.leoneferito.TestcontainersConfiguration;
import com.leoneferito.auth.AuthService;
import com.leoneferito.media.ImageFormat;
import com.leoneferito.media.MediaAsset;
import com.leoneferito.media.MediaAssetRepository;
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
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 나이스페이(기본 결제사) — 결제창 복귀 · 승인 · 망취소 · 취소 · 웹훅.
 *
 * <p>진짜 나이스페이 대신 이 JVM 안에 가짜 서버를 띄우고, 무엇이 몇 번 · 얼마로 불렸는지 센다.
 * 지키려는 것: 서명이 틀리거나 금액이 다르면 승인하지 않는다 · 두 번 돌아와도 한 번만 승인한다 ·
 * 승인 응답을 믿을 수 없으면 망취소로 되돌린다 · 환불마다 고유한 취소 주문번호를 쓴다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestMailConfiguration.class})
class NicePayApiTest {

    private static final String CLIENT_ID = "S2_test_client";
    private static final String MERCHANT_SIGNING_VALUE = "fake_signing_value_for_tests";
    private static final String FRONT = "https://shop.test";

    // ── 가짜 나이스페이 ──────────────────────────────────────────

    record Call(String method, String path, String body, String authorization) {
    }

    static final List<Call> CALLS = new CopyOnWriteArrayList<>();
    /** 거래 조회(GET)에 돌려줄 답 — 웹훅 테스트가 "나이스페이가 실제로 알고 있는 결제" 를 심는다. */
    static final Map<String, String> PAYMENTS = new ConcurrentHashMap<>();
    static final HttpServer NICE;

    static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static {
        try {
            NICE = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        NICE.createContext("/v1/payments", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            CALLS.add(new Call(method, path, body, exchange.getRequestHeaders().getFirst("Authorization")));
            String json;
            String tid = path.substring("/v1/payments/".length()).replace("/cancel", "");
            String ediDate = "2026-10-12T17:00:00.000+0900";
            if ("GET".equals(method)) {
                json = PAYMENTS.getOrDefault(tid, "{\"resultCode\":\"2001\",\"resultMsg\":\"거래 없음\"}");
            } else if (path.endsWith("/netcancel")) {
                json = "{\"resultCode\":\"0000\",\"resultMsg\":\"망취소 성공\"}";
            } else if (path.endsWith("/cancel")) {
                Number cancelAmt = body.contains("cancelAmt") ? JsonPath.read(body, "$.cancelAmt") : null;
                json = """
                        {"resultCode":"0000","resultMsg":"취소 성공","tid":"%s","status":"%s","amount":293000,
                         "balanceAmt":%d,"payMethod":"card","paidAt":"2026-10-12T17:00:00.000+0900"}
                        """.formatted(tid, cancelAmt == null ? "cancelled" : "partialCancelled",
                        cancelAmt == null ? 0 : 293000 - cancelAmt.longValue());
            } else if (tid.startsWith("reject")) {
                json = "{\"resultCode\":\"3011\",\"resultMsg\":\"카드번호 오류\"}";
            } else {
                long amount = ((Number) JsonPath.read(body, "$.amount")).longValue();
                String sig = tid.startsWith("badsig") ? "0".repeat(64) : sha256(tid + amount + ediDate + MERCHANT_SIGNING_VALUE);
                json = """
                        {"resultCode":"0000","resultMsg":"정상 처리되었습니다.","tid":"%s","orderId":"x","status":"paid",
                         "paidAt":"%s","amount":%d,"balanceAmt":%d,"payMethod":"card","ediDate":"%s","signature":"%s",
                         "somethingNew":"무시돼야 한다"}
                        """.formatted(tid, ediDate, amount, amount, ediDate, sig);
            }
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        NICE.start();
    }

    @DynamicPropertySource
    static void nicepay(DynamicPropertyRegistry registry) {
        registry.add("app.payment.provider", () -> "nicepay");
        registry.add("app.payment.nicepay.client-id", () -> CLIENT_ID);
        registry.add("app.payment.nicepay.secret-key", () -> MERCHANT_SIGNING_VALUE);
        registry.add("app.payment.nicepay.api-base", () -> "http://127.0.0.1:" + NICE.getAddress().getPort());
        registry.add("app.front.base-url", () -> FRONT);
        registry.add("app.order.shipping-fee-krw", () -> "3000");
        registry.add("app.order.free-shipping-threshold-krw", () -> "500000");
        registry.add("app.notify.admin-emails", () -> "ops@leoneferito.test, owner@leoneferito.test");
    }

    // ── 준비 ────────────────────────────────────────────────────

    private static final String PASSWORD = "long enough secret 9";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private AuthService authService;

    @Autowired
    private ProductRepository products;

    @Autowired
    private MediaAssetRepository mediaAssets;

    private Cookie buyer;

    @BeforeEach
    void setUp() throws Exception {
        cleanUp();
        CALLS.clear();
        PAYMENTS.clear();
        authService.signup("buyer@example.com", PASSWORD, "김구매", "010-1111-2222");
        MediaAsset media = mediaAssets.save(new MediaAsset(UUID.randomUUID(), "shot.webp", ImageFormat.WEBP,
                100_000L, 1200, 1600, "test/" + UUID.randomUUID() + ".webp"));
        Product p = new Product(UUID.randomUUID(), "np-shirt", ProductCategory.SHIRT, ProductLine.FERITO);
        p.setName("브라운 셔츠");
        p.setPriceKrw(290_000L);
        p.setLeadTimeDays((short) 14);
        p.setFabric("면 100%");
        p.setCare("드라이클리닝");
        p.setNotice("브라운", "레오네페리토", "대한민국", "2026년 9월");
        p.addImage(new ProductImage(UUID.randomUUID(), media, ProductImageKind.MAIN, "대표", 0));
        p.addSku(new ProductSku(UUID.randomUUID(), "100", 0));
        p.publish();
        products.saveAndFlush(p);
        buyer = login(mockMvc, "buyer@example.com", PASSWORD);
    }

    @AfterEach
    void cleanUp() {
        MemberTestSupport.cleanDatabase(jdbc);
        products.deleteAll(products.findAll().stream().filter(p -> p.getSlug().startsWith("np-")).toList());
    }

    /** 장바구니에 담고 주문서를 만든다. {주문번호, 금액} */
    private Object[] order() throws Exception {
        mockMvc.perform(csrf(mockMvc, post("/api/cart/items"), buyer).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"slug\":\"np-shirt\",\"size\":\"100\",\"quantity\":1}"))
                .andExpect(status().isOk());
        String cart = mockMvc.perform(get("/api/cart").cookie(buyer)).andReturn().getResponse().getContentAsString();
        String line = JsonPath.<List<String>>read(cart, "$.items[*].id").getFirst();
        String res = mockMvc.perform(csrf(mockMvc, post("/api/orders"), buyer).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"cartItemIds":["%s"],"recipientName":"김구매","recipientPhone":"010-1111-2222",
                                 "zipCode":"16677","address1":"수원시 영통구 매영로 1","agree":true}
                                """.formatted(line)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return new Object[] {JsonPath.read(res, "$.orderNumber"), ((Number) JsonPath.read(res, "$.amount")).longValue()};
    }

    /** 나이스페이 결제창이 손님 브라우저로 보내는 폼 — 로그인 쿠키도 CSRF 토큰도 없다. */
    private ResultActions returned(String orderNumber, String tid, long amount, String signature, String code)
            throws Exception {
        String nonce = "NICEUNTT" + UUID.randomUUID().toString().replace("-", "");
        String sig = signature != null ? signature : sha256(nonce + CLIENT_ID + amount + MERCHANT_SIGNING_VALUE);
        return mockMvc.perform(post("/api/payments/nicepay/return")
                // 결제창(나이스페이 도메인)에서 보낸 폼이라 브라우저가 Origin 을 붙인다 — 운영에서 이걸로 403 이 났다(2026-10-15)
                .header("Origin", "https://pay.nicepay.co.kr")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("authResultCode", code)
                .param("authResultMsg", "0000".equals(code) ? "인증 성공" : "고객 결제 취소")
                .param("tid", tid)
                .param("clientId", CLIENT_ID)
                .param("orderId", orderNumber)
                .param("amount", String.valueOf(amount))
                .param("authToken", nonce)
                .param("signature", sig));
    }

    private ResultActions returned(String orderNumber, String tid, long amount) throws Exception {
        return returned(orderNumber, tid, amount, null, "0000");
    }

    private static String location(ResultActions r) {
        return URLDecoder.decode(r.andReturn().getResponse().getHeader("Location"), StandardCharsets.UTF_8);
    }

    private String orderStatus(String orderNumber) {
        return jdbc.queryForObject("SELECT status FROM orders WHERE order_number = ?", String.class, orderNumber);
    }

    private long calls(String suffix) {
        return CALLS.stream().filter(c -> "POST".equals(c.method()) && c.path().endsWith(suffix)).count();
    }

    private long approvals() {
        return CALLS.stream().filter(c -> "POST".equals(c.method())
                && c.path().matches("^/v1/payments/[^/]+$") && !c.path().endsWith("/netcancel")).count();
    }

    // ── 테스트 ──────────────────────────────────────────────────

    @Test
    @DisplayName("주문서 화면은 결제사(nicepay)와 결제창 공개 키를 서버에서 받는다 — 비밀 키는 나가지 않는다")
    void readiness() throws Exception {
        mockMvc.perform(get("/api/orders/readiness").cookie(buyer))
                .andExpect(jsonPath("$.paymentReady").value(true))
                .andExpect(jsonPath("$.provider").value("nicepay"))
                .andExpect(jsonPath("$.clientKey").value(CLIENT_ID))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(MERCHANT_SIGNING_VALUE))));
    }

    @Test
    @DisplayName("결제창에서 돌아오면 저장된 금액으로 승인하고, 결제 완료 화면으로 보내고, 결제한 줄은 장바구니에서 뺀다")
    void approves() throws Exception {
        Object[] o = order();
        String tid = "nicepay_tid_" + UUID.randomUUID().toString().substring(0, 8);
        ResultActions r = returned((String) o[0], tid, (long) o[1]).andExpect(status().isSeeOther());
        assertThat(location(r)).isEqualTo(FRONT + "/order/success/?orderNumber=" + o[0]);
        assertThat(orderStatus((String) o[0])).isEqualTo("PAID");

        Call approve = CALLS.getFirst();
        assertThat(approve.path()).isEqualTo("/v1/payments/" + tid);
        assertThat(approve.authorization()).isEqualTo("Basic " + java.util.Base64.getEncoder()
                .encodeToString((CLIENT_ID + ":" + MERCHANT_SIGNING_VALUE).getBytes(StandardCharsets.UTF_8)));
        long sent = ((Number) JsonPath.read(approve.body(), "$.amount")).longValue();
        String ediDate = JsonPath.read(approve.body(), "$.ediDate");
        assertThat(sent).isEqualTo((long) o[1]);
        assertThat((String) JsonPath.read(approve.body(), "$.signData")).isEqualTo(sha256(tid + sent + ediDate + MERCHANT_SIGNING_VALUE));

        mockMvc.perform(get("/api/orders/" + o[0]).cookie(buyer))
                .andExpect(jsonPath("$.status").value("PAID"))
                .andExpect(jsonPath("$.paymentMethod").value("카드"));
        mockMvc.perform(get("/api/cart").cookie(buyer)).andExpect(jsonPath("$.items.length()").value(0));

        // 뒤로 가기 · 새로고침으로 같은 폼이 다시 와도 승인은 한 번
        returned((String) o[0], tid, (long) o[1]).andExpect(status().isSeeOther());
        assertThat(approvals()).isEqualTo(1);
    }

    @Test
    @DisplayName("서명이 틀리면(위조한 폼) 나이스페이를 부르지도 않고 실패 화면으로 보낸다")
    void forgedSignature() throws Exception {
        Object[] o = order();
        ResultActions r = returned((String) o[0], "tid_forged", (long) o[1], "a".repeat(64), "0000")
                .andExpect(status().isSeeOther());
        assertThat(location(r)).startsWith(FRONT + "/order/fail/?code=INVALID_SIGNATURE");
        assertThat(CALLS).isEmpty();
        assertThat(orderStatus((String) o[0])).isEqualTo("PENDING_PAYMENT");
    }

    @Test
    @DisplayName("금액이 주문과 다르면 승인하지 않는다 — 서명이 맞아도")
    void tamperedAmount() throws Exception {
        Object[] o = order();
        ResultActions r = returned((String) o[0], "tid_cheap", 1000L).andExpect(status().isSeeOther());
        assertThat(location(r)).contains("/order/fail/?code=AMOUNT_MISMATCH");
        assertThat(CALLS).isEmpty();
        assertThat(orderStatus((String) o[0])).isEqualTo("PENDING_PAYMENT");
    }

    @Test
    @DisplayName("결제창에서 그만두면(인증 실패) 그 코드 · 메시지로 실패 화면 — 승인 요청은 없다")
    void authFailed() throws Exception {
        Object[] o = order();
        ResultActions r = returned((String) o[0], "", (long) o[1], "", "9999").andExpect(status().isSeeOther());
        assertThat(location(r)).startsWith(FRONT + "/order/fail/?code=9999&message=고객 결제 취소&orderNumber=" + o[0]);
        assertThat(CALLS).isEmpty();
    }

    @Test
    @DisplayName("나이스페이가 거절하면 그 메시지를 보여 주고, 주문서와 장바구니는 그대로라 다시 시도할 수 있다")
    void rejected() throws Exception {
        Object[] o = order();
        ResultActions r = returned((String) o[0], "reject_card", (long) o[1]).andExpect(status().isSeeOther());
        assertThat(location(r)).contains("/order/fail/?code=3011&message=카드번호 오류");
        assertThat(orderStatus((String) o[0])).isEqualTo("PENDING_PAYMENT");
        mockMvc.perform(get("/api/cart").cookie(buyer)).andExpect(jsonPath("$.items.length()").value(1));
    }

    @Test
    @DisplayName("승인 응답의 서명이 틀리면 믿지 않고 망취소로 되돌린다")
    void badResponseSignatureNetCancels() throws Exception {
        Object[] o = order();
        ResultActions r = returned((String) o[0], "badsig_tid", (long) o[1]).andExpect(status().isSeeOther());
        assertThat(location(r)).contains("/order/fail/?code=MISMATCH");
        assertThat(calls("/netcancel")).isEqualTo(1);
        Call net = CALLS.stream().filter(c -> c.path().endsWith("/netcancel")).findFirst().orElseThrow();
        assertThat((String) JsonPath.read(net.body(), "$.orderId")).isEqualTo(o[0]);
        assertThat(orderStatus((String) o[0])).isEqualTo("PENDING_PAYMENT");
    }

    private Integer stock() {
        return jdbc.queryForObject("SELECT s.stock FROM product_sku s JOIN product p ON p.id = s.product_id "
                + "WHERE p.slug = 'np-shirt' AND s.size = '100'", Integer.class);
    }

    private void setStock(Integer n) {
        jdbc.update("UPDATE product_sku SET stock = ? WHERE size = '100' AND product_id = "
                + "(SELECT id FROM product WHERE slug = 'np-shirt')", n);
    }

    @Test
    @DisplayName("재고 — 결제가 승인되면 줄고, 발송 전 취소면 돌아온다")
    void stockTakenAndReturned() throws Exception {
        setStock(2);
        Object[] o = order();
        assertThat(stock()).isEqualTo(2); // 주문서만으로는 줄지 않는다
        returned((String) o[0], "tid_stock", (long) o[1]).andExpect(status().isSeeOther());
        assertThat(stock()).isEqualTo(1);
        mockMvc.perform(csrf(mockMvc, post("/api/orders/" + o[0] + "/cancel"), buyer)).andExpect(status().isOk());
        assertThat(stock()).isEqualTo(2);
    }

    @Test
    @DisplayName("재고 — 주문서를 만든 뒤 품절되면 승인을 보내지 않는다(청구 없음). 승인이 실패하면 줄인 수량도 돌아온다")
    void soldOutBeforeApproval() throws Exception {
        setStock(1);
        Object[] o = order();
        setStock(0); // 그사이 다른 손님이 마지막 한 벌을 샀다
        ResultActions r = returned((String) o[0], "tid_late", (long) o[1]).andExpect(status().isSeeOther());
        assertThat(location(r)).contains("/order/fail/?code=OUT_OF_STOCK");
        assertThat(approvals()).isZero();
        assertThat(orderStatus((String) o[0])).isEqualTo("PENDING_PAYMENT");
        assertThat(stock()).isZero();

        // 승인 응답이 틀려 망취소된 경우 — 줄였던 재고가 돌아와 있다
        setStock(1);
        Object[] o2 = order2();
        returned((String) o2[0], "badsig_stock", (long) o2[1]).andExpect(status().isSeeOther());
        assertThat(stock()).isEqualTo(1);
    }

    /** 첫 주문서가 장바구니 줄을 쓰고 남아 있으므로, 같은 줄로 주문서를 하나 더 만든다. */
    private Object[] order2() throws Exception {
        String cart = mockMvc.perform(get("/api/cart").cookie(buyer)).andReturn().getResponse().getContentAsString();
        String line = JsonPath.<List<String>>read(cart, "$.items[*].id").getFirst();
        String res = mockMvc.perform(csrf(mockMvc, post("/api/orders"), buyer).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"cartItemIds":["%s"],"recipientName":"김구매","recipientPhone":"010-1111-2222",
                                 "zipCode":"16677","address1":"수원시 영통구 매영로 1","agree":true}
                                """.formatted(line)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return new Object[] {JsonPath.read(res, "$.orderNumber"), ((Number) JsonPath.read(res, "$.amount")).longValue()};
    }

    @Test
    @DisplayName("재고 — 남은 수량보다 많이 담을 수 없고, 품절이면 주문서를 만들 수 없다. 손님 화면에는 품절 · 몇 벌 남음")
    void cartAndProductRespectStock() throws Exception {
        setStock(2);
        mockMvc.perform(get("/api/products/np-shirt"))
                .andExpect(jsonPath("$.skus[0].soldOut").value(false))
                .andExpect(jsonPath("$.skus[0].remaining").value(2));
        mockMvc.perform(csrf(mockMvc, post("/api/cart/items"), buyer).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"slug\":\"np-shirt\",\"size\":\"100\",\"quantity\":3}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("100 사이즈는 2벌 남았습니다. 수량을 줄여 주세요."));

        Object[] o = order(); // 1벌 담고 주문서
        setStock(0);
        mockMvc.perform(get("/api/products/np-shirt"))
                .andExpect(jsonPath("$.skus[0].soldOut").value(true))
                .andExpect(jsonPath("$.skus[0].remaining").doesNotExist());
        mockMvc.perform(get("/api/cart").cookie(buyer))
                .andExpect(jsonPath("$.items[0].available").value(false))
                .andExpect(jsonPath("$.items[0].unavailableReason").value("100 사이즈는 품절입니다."));
        String line = JsonPath.<List<String>>read(
                mockMvc.perform(get("/api/cart").cookie(buyer)).andReturn().getResponse().getContentAsString(),
                "$.items[*].id").getFirst();
        mockMvc.perform(csrf(mockMvc, post("/api/orders"), buyer).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"cartItemIds":["%s"],"recipientName":"김구매","recipientPhone":"010-1111-2222",
                                 "zipCode":"16677","address1":"수원시 영통구 매영로 1","agree":true}
                                """.formatted(line)))
                .andExpect(status().isBadRequest());

        // 세지 않으면(null) 예전처럼 — 품절도 남은 수량도 없다
        setStock(null);
        mockMvc.perform(get("/api/products/np-shirt"))
                .andExpect(jsonPath("$.skus[0].soldOut").value(false))
                .andExpect(jsonPath("$.skus[0].remaining").doesNotExist());
        assertThat(o).isNotNull();
    }

    @Autowired
    private com.leoneferito.TestMailConfiguration.RecordingMailer mailer;

    private List<com.leoneferito.TestMailConfiguration.SentMail> mailsTo(String to) {
        return mailer.sent().stream().filter(m -> m.to().equals(to)).toList();
    }

    @Test
    @DisplayName("주문 알림 — 결제되면 손님에게 주문 내역, 관리자들에게 새 주문. 손님이 취소하면 손님에게 환불 안내, 관리자에게 취소 알림")
    void orderMails() throws Exception {
        mailer.clear();
        Object[] o = order();
        assertThat(mailer.sent()).isEmpty(); // 주문서만으로는 아무것도 안 나간다
        returned((String) o[0], "tid_mail", (long) o[1]).andExpect(status().isSeeOther());

        var toBuyer = mailsTo("buyer@example.com");
        assertThat(toBuyer).hasSize(1);
        assertThat(toBuyer.getFirst().subject()).isEqualTo("[LEONE FERITO] 주문이 완료되었습니다");
        assertThat(toBuyer.getFirst().text())
                .startsWith("김구매님,")
                .contains("주문번호  " + o[0], "브라운 셔츠 · 100 · 1벌 · 290,000원", "293,000원 (상품 290,000원 + 배송비 3,000원)",
                        "약 14일", FRONT + "/mypage/order/?no=" + o[0]);
        for (String admin : List.of("ops@leoneferito.test", "owner@leoneferito.test")) {
            assertThat(mailsTo(admin)).singleElement()
                    .satisfies(m -> assertThat(m.subject()).isEqualTo("[새 주문] " + o[0] + " · 293,000원 · 김구매"))
                    .satisfies(m -> assertThat(m.text()).contains(FRONT + "/admin/orders/detail/?no=" + o[0]));
        }

        mailer.clear();
        mockMvc.perform(csrf(mockMvc, post("/api/orders/" + o[0] + "/cancel"), buyer)).andExpect(status().isOk());
        assertThat(mailsTo("buyer@example.com")).singleElement()
                .satisfies(m -> assertThat(m.subject()).isEqualTo("[LEONE FERITO] 주문이 취소되었습니다"))
                .satisfies(m -> assertThat(m.text()).contains("환불 금액  293,000원"));
        assertThat(mailsTo("ops@leoneferito.test")).singleElement()
                .satisfies(m -> assertThat(m.subject()).startsWith("[주문 취소] " + o[0]));
    }

    @Test
    @DisplayName("주문 알림 — 승인이 실패하면(카드 거절) 아무 메일도 나가지 않는다")
    void noMailWhenRejected() throws Exception {
        mailer.clear();
        Object[] o = order();
        returned((String) o[0], "reject_card", (long) o[1]).andExpect(status().isSeeOther());
        assertThat(mailer.sent()).isEmpty();
    }

    @Test
    @DisplayName("손님 취소 — 나이스페이 취소가 먼저 성공해야 취소되고, 취소 주문번호는 원 주문과 다른 고유 값이다")
    void customerCancel() throws Exception {
        Object[] o = order();
        returned((String) o[0], "tid_to_cancel", (long) o[1]).andExpect(status().isSeeOther());
        mockMvc.perform(csrf(mockMvc, post("/api/orders/" + o[0] + "/cancel"), buyer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        Call cancel = CALLS.stream().filter(c -> c.path().endsWith("/cancel")).findFirst().orElseThrow();
        assertThat(cancel.path()).isEqualTo("/v1/payments/tid_to_cancel/cancel");
        assertThat((String) JsonPath.read(cancel.body(), "$.orderId")).isEqualTo("cancel-" + o[0]);
        assertThat(cancel.body()).doesNotContain("cancelAmt");
        String ediDate = JsonPath.read(cancel.body(), "$.ediDate");
        assertThat((String) JsonPath.read(cancel.body(), "$.signData")).isEqualTo(sha256("tid_to_cancel" + ediDate + MERCHANT_SIGNING_VALUE));
    }

    @Test
    @DisplayName("웹훅 — 서명이 맞으면 조회해서 맞추고(승인 응답을 놓친 주문) 본문 OK 로 답한다. 서명이 틀리면 무시한다")
    void webhook() throws Exception {
        Object[] o = order();
        long amount = (long) o[1];
        PAYMENTS.put("tid_missed", """
                {"resultCode":"0000","tid":"tid_missed","orderId":"%s","status":"paid","amount":%d,"balanceAmt":%d,
                 "payMethod":"card","paidAt":"2026-10-12T17:00:00.000+0900"}
                """.formatted(o[0], amount, amount));
        String ediDate = "2026-10-12T17:00:01.000+0900";

        // 서명이 틀리면 조회조차 하지 않는다
        mockMvc.perform(post("/api/payments/nicepay/webhook").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"resultCode":"0000","tid":"tid_missed","orderId":"%s","amount":%d,"status":"paid",
                                 "ediDate":"%s","signature":"%s"}
                                """.formatted(o[0], amount, ediDate, "b".repeat(64))))
                .andExpect(status().isOk())
                .andExpect(content().string("OK"));
        assertThat(CALLS).isEmpty();
        assertThat(orderStatus((String) o[0])).isEqualTo("PENDING_PAYMENT");

        mockMvc.perform(post("/api/payments/nicepay/webhook").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"resultCode":"0000","tid":"tid_missed","orderId":"%s","amount":%d,"status":"paid",
                                 "ediDate":"%s","signature":"%s"}
                                """.formatted(o[0], amount, ediDate, sha256("tid_missed" + amount + ediDate + MERCHANT_SIGNING_VALUE))))
                .andExpect(status().isOk())
                .andExpect(content().string("OK"));
        assertThat(orderStatus((String) o[0])).isEqualTo("PAID");
    }
}
