package com.leoneferito.payment;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 나이스페이 결제 API — Server 승인 모델 (2026-10-12, 포스타트로 시작 → 매출이 늘면 일반 계약).
 *
 * <h2>포스타트 → 일반 계약</h2>
 * 둘 다 같은 API(api.nicepay.co.kr/v1)다. 계약을 바꾸면 새 상점의 키 두 개(NICEPAY_CLIENT_ID · NICEPAY_SECRET_KEY)만
 * 서버 .env 에서 바꾸고 API 를 다시 띄운다. 코드는 그대로다.
 *
 * <h2>흐름</h2>
 * <ol>
 *   <li>화면이 결제창(JS SDK)을 연다. 돌아올 곳(returnUrl)은 API 의 /api/payments/nicepay/return 이다.</li>
 *   <li>손님이 인증하면 나이스페이가 그 주소로 tid · 금액 · 서명을 보낸다 → {@link #verifyAuthSignature} 로 위조를 막는다.</li>
 *   <li>{@link #approve} 로 승인을 요청한다 — 이때 처음으로 돈이 움직인다.</li>
 * </ol>
 *
 * <h2>망취소</h2>
 * 승인 요청이 시간 초과 · 연결 끊김으로 끝나면 결제됐는지 알 수 없다. 그대로 두면 손님 돈만 빠지고 주문은 결제 전이 된다.
 * 나이스페이 안내대로 주문번호로 망취소를 보내 "결제 안 됨" 으로 확정한 뒤, 손님에게 다시 시도하게 한다.
 *
 * <h2>키</h2>
 * 인증은 {@code clientId:secretKey} 를 base64 로 묶은 Basic 헤더다. 테스트 상점(샌드박스)과 운영 상점은 키가 다르고
 * 주소도 다르다 — NICEPAY_API_BASE: 샌드박스 https://sandbox-api.nicepay.co.kr · 운영 https://api.nicepay.co.kr.
 * 기본값은 샌드박스다: 운영 키를 넣고 주소를 빠뜨리면 결제가 <b>실패</b>할 뿐 돈이 잘못 움직이지 않는다.
 * 비밀 키는 로그에 남기지 않는다.
 */
@Component
@ConditionalOnProperty(name = "app.payment.provider", havingValue = "nicepay", matchIfMissing = true)
public class NicePayClient implements PaymentGateway {

    private static final Logger log = LoggerFactory.getLogger(NicePayClient.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final String OK = "0000";

    private final RestClient http;
    private final String clientId;
    private final String secretKey;
    private final boolean ready;

    public NicePayClient(@Value("${app.payment.nicepay.client-id:}") String clientId,
                         @Value("${app.payment.nicepay.secret-key:}") String secretKey,
                         @Value("${app.payment.nicepay.api-base:https://sandbox-api.nicepay.co.kr}") String apiBase) {
        this.clientId = clientId == null ? "" : clientId.trim();
        this.secretKey = secretKey == null ? "" : secretKey.trim();
        this.ready = !this.clientId.isEmpty() && !this.secretKey.isEmpty();
        String basic = Base64.getEncoder()
                .encodeToString((this.clientId + ":" + this.secretKey).getBytes(StandardCharsets.UTF_8));
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5_000);
        // 승인은 카드사까지 다녀온다. 너무 짧으면 승인은 됐는데 우리는 실패로 아는 일이 생긴다(그때는 망취소).
        factory.setReadTimeout(30_000);
        this.http = RestClient.builder()
                .baseUrl(apiBase)
                .requestFactory(factory)
                .defaultHeader("Authorization", "Basic " + basic)
                .build();
    }

    @Override
    public String name() {
        return "nicepay";
    }

    @Override
    public String clientKey() {
        return clientId;
    }

    @Override
    public boolean isReady() {
        return ready;
    }

    // ── 위변조 검증 ──────────────────────────────────────────────

    /**
     * 결제창 인증 결과(returnUrl 로 온 값)가 나이스페이가 보낸 것인가.
     * {@code signature = hex(sha256(authToken + clientId + amount + secretKey))} — 비밀 키를 모르면 만들 수 없다.
     */
    public boolean verifyAuthSignature(String authToken, String clientId, String amount, String signature) {
        if (!ready || authToken == null || clientId == null || amount == null || signature == null) {
            return false;
        }
        return same(sha256(authToken + clientId + amount + secretKey), signature)
                && this.clientId.equals(clientId);
    }

    /** 웹훅이 나이스페이가 보낸 것인가. {@code signature = hex(sha256(tid + amount + ediDate + secretKey))} */
    public boolean verifyWebhookSignature(String tid, String amount, String ediDate, String signature) {
        if (!ready || tid == null || amount == null || ediDate == null || signature == null) {
            return false;
        }
        return same(sha256(tid + amount + ediDate + secretKey), signature);
    }

    // ── 승인 · 조회 · 취소 ──────────────────────────────────────

    /** 승인. 시간 초과 · 연결 끊김이면 망취소한 뒤 NETWORK 로 알린다 — 손님은 다시 결제하면 된다. */
    @Override
    public Payment approve(String tid, String orderId, long amount) {
        requireReady();
        String ediDate = ediDate();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("amount", amount);
        body.put("ediDate", ediDate);
        body.put("signData", sha256(tid + amount + ediDate + secretKey));
        JsonNode res;
        try {
            res = post("/v1/payments/" + tid, body);
        } catch (ResourceAccessException e) {
            log.error("나이스 승인 응답 없음 — 망취소 orderId={}", orderId, e);
            netCancel(orderId);
            throw new PaymentException("NETWORK",
                    "결제 결과를 확인하지 못해 결제를 취소했습니다. 잠시 후 다시 시도해 주세요.");
        }
        Payment payment = toPayment(res);
        // 응답 서명이 오면 확인한다 — 다르면 이 응답은 믿을 수 없다. 돈이 움직였을 수 있으니 망취소로 되돌린다.
        String sig = text(res, "signature");
        if (sig != null && !sig.isEmpty()
                && !same(sha256(text(res, "tid") + number(res, "amount") + text(res, "ediDate") + secretKey), sig)) {
            log.error("나이스 승인 응답 서명 불일치 — 망취소 orderId={}", orderId);
            netCancel(orderId);
            throw new PaymentException("MISMATCH", "결제 확인 중 문제가 생겼습니다. 잠시 후 다시 시도해 주세요.");
        }
        return payment;
    }

    @Override
    public Payment fetch(String tid) {
        requireReady();
        try {
            return toPayment(read(http.get()
                    .uri("/v1/payments/{tid}", tid)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (req, res) -> {
                        throw failure(res.getBody());
                    })
                    .body(String.class)));
        } catch (PaymentException e) {
            throw e;
        } catch (RestClientException e) {
            log.error("나이스 결제 조회 실패", e);
            throw new PaymentException("NETWORK", "결제사와 연결하지 못했습니다.");
        }
    }

    /**
     * 취소. {@code requestId} 를 나이스페이의 취소 주문번호(orderId)로 보낸다 — 나이스페이는 취소마다 새 주문번호를 받고
     * 같은 번호를 다시 받지 않는다. 그래서 같은 취소를 두 번 보내도 두 번 환불되지 않는다.
     */
    @Override
    public Payment cancel(String tid, String orderId, String reason, String requestId, Long amount) {
        requireReady();
        String ediDate = ediDate();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("reason", reason);
        body.put("orderId", requestId);
        if (amount != null) {
            body.put("cancelAmt", amount);
        }
        body.put("ediDate", ediDate);
        body.put("signData", sha256(tid + ediDate + secretKey));
        try {
            return toPayment(post("/v1/payments/" + tid + "/cancel", body));
        } catch (ResourceAccessException e) {
            log.error("나이스 취소 응답 없음 requestId={}", requestId, e);
            throw new PaymentException("NETWORK", "결제사와 연결하지 못했습니다. 잠시 후 다시 시도해 주세요.");
        }
    }

    /** 망취소 — 승인 결과를 모를 때 주문번호로 "이 결제는 없던 것" 으로 확정한다. 실패해도 로그만 남긴다(사람이 본다). */
    void netCancel(String orderId) {
        String ediDate = ediDate();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("orderId", orderId);
        body.put("ediDate", ediDate);
        body.put("signData", sha256(orderId + ediDate + secretKey));
        try {
            post("/v1/payments/netcancel", body);
            log.warn("나이스 망취소 완료 orderId={}", orderId);
        } catch (RuntimeException e) {
            log.error("나이스 망취소 실패 — 가맹점관리자에서 확인 필요 orderId={}", orderId, e);
        }
    }

    // ── 내부 ────────────────────────────────────────────────────

    /** POST 하고 결과 코드를 확인한다. 0000 이 아니면 나이스페이가 준 코드 · 메시지로 {@link PaymentException}. */
    private JsonNode post(String path, Map<String, Object> body) {
        String safePath = path.replaceAll("/payments/[^/]+", "/payments/*");
        try {
            JsonNode res = read(http.post()
                    .uri(path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (req, r) -> {
                        PaymentException e = failure(r.getBody());
                        log.warn("나이스 거절 path={} status={} code={}", safePath, r.getStatusCode().value(), e.getCode());
                        throw e;
                    })
                    .body(String.class));
            String code = text(res, "resultCode");
            if (!OK.equals(code)) {
                log.warn("나이스 거절 path={} code={}", safePath, code);
                throw new PaymentException(code == null ? "UNKNOWN" : code,
                        orElse(text(res, "resultMsg"), "결제를 처리하지 못했습니다."));
            }
            return res;
        } catch (PaymentException | ResourceAccessException e) {
            throw e;
        } catch (RestClientException e) {
            log.error("나이스 호출 실패 path={}", safePath, e);
            throw new PaymentException("NETWORK", "결제사와 연결하지 못했습니다. 잠시 후 다시 시도해 주세요.");
        }
    }

    private static JsonNode read(String body) {
        try {
            return JSON.readTree(body == null ? "{}" : body);
        } catch (Exception e) {
            throw new PaymentException("UNKNOWN", "결제사 응답을 읽지 못했습니다.");
        }
    }

    private static PaymentException failure(java.io.InputStream in) {
        try {
            JsonNode node = read(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            return new PaymentException(orElse(text(node, "resultCode"), "UNKNOWN"),
                    orElse(text(node, "resultMsg"), "결제를 처리하지 못했습니다."));
        } catch (Exception e) {
            return new PaymentException("UNKNOWN", "결제를 처리하지 못했습니다.");
        }
    }

    private static Payment toPayment(JsonNode n) {
        Status status = switch (orElse(text(n, "status"), "")) {
            case "paid" -> Status.PAID;
            case "cancelled" -> Status.CANCELLED;
            case "partialCancelled" -> Status.PARTIAL_CANCELLED;
            default -> Status.OTHER;
        };
        return new Payment(text(n, "tid"), text(n, "orderId"), status, methodLabel(text(n, "payMethod")),
                number(n, "amount"), number(n, "balanceAmt"), instant(text(n, "paidAt")));
    }

    /** 손님 화면 · 관리자 화면에 보이는 결제 수단 이름. */
    static String methodLabel(String payMethod) {
        if (payMethod == null) {
            return null;
        }
        return switch (payMethod) {
            case "card" -> "카드";
            case "vbank" -> "가상계좌";
            case "bank" -> "계좌이체";
            case "cellphone" -> "휴대폰";
            case "naverpay" -> "네이버페이";
            case "kakaopay" -> "카카오페이";
            case "samsungpay" -> "삼성페이";
            case "payco" -> "페이코";
            case "ssgpay" -> "SSG페이";
            default -> payMethod;
        };
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? null : v.asString();
    }

    private static Long number(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? null : v.asLong();
    }

    /** 결제 시각. 결제 전이면 나이스페이는 "0" 을 준다 — 그건 시각이 없다는 뜻이다. */
    private static java.time.Instant instant(String iso) {
        if (iso == null || iso.isBlank() || "0".equals(iso)) {
            return null;
        }
        try {
            return OffsetDateTime.parse(iso.replaceFirst("([+-]\\d{2})(\\d{2})$", "$1:$2")).toInstant();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static String orElse(String v, String fallback) {
        return v == null || v.isBlank() ? fallback : v;
    }

    private static String ediDate() {
        return OffsetDateTime.now(SEOUL).truncatedTo(ChronoUnit.SECONDS).toString();
    }

    static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 길이가 같아도 앞에서부터 비교하면 시간 차로 값이 새어 나간다 — 고정 시간 비교. */
    private static boolean same(String expected, String given) {
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                given.toLowerCase(java.util.Locale.ROOT).getBytes(StandardCharsets.UTF_8));
    }

    private void requireReady() {
        if (!ready) {
            throw new PaymentException("NOT_CONFIGURED", "결제 준비 중입니다.");
        }
    }
}