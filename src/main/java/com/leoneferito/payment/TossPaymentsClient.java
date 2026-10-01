package com.leoneferito.payment;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * 토스페이먼츠 결제 API (승인 · 취소).
 *
 * <p>인증은 시크릿 키 뒤에 {@code :} 를 붙여 base64 로 인코딩한 Basic 헤더다(토스 문서).
 * 시크릿 키가 없으면(로컬 · 계약 전) 결제를 열지 않는다 — {@link #isReady()}.
 * 시크릿 키는 로그에 남기지 않는다.
 *
 * <p>승인·취소는 <b>멱등키</b>를 붙인다. 네트워크가 끊겨 다시 보내도 두 번 승인·환불되지 않는다.
 */
@Component
public class TossPaymentsClient {

    private static final Logger log = LoggerFactory.getLogger(TossPaymentsClient.class);

    private final RestClient http;
    private final boolean ready;

    public TossPaymentsClient(@Value("${app.payment.toss.secret-key:}") String secretKey,
                              @Value("${app.payment.toss.api-base:https://api.tosspayments.com}") String apiBase) {
        this.ready = secretKey != null && !secretKey.isBlank();
        String basic = Base64.getEncoder()
                .encodeToString(((secretKey == null ? "" : secretKey) + ":").getBytes(StandardCharsets.UTF_8));
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5_000);
        // 승인은 카드사까지 다녀온다. 너무 짧으면 승인은 됐는데 우리는 실패로 아는 일이 생긴다.
        factory.setReadTimeout(30_000);
        this.http = RestClient.builder()
                .baseUrl(apiBase)
                .requestFactory(factory)
                .defaultHeader("Authorization", "Basic " + basic)
                .build();
    }

    public boolean isReady() {
        return ready;
    }

    /** 결제 승인. 토스가 거절하면 {@link PaymentException} (코드·메시지는 토스가 준 것). */
    public Payment confirm(String paymentKey, String orderId, long amount) {
        requireReady();
        return call("/v1/payments/confirm", "confirm-" + orderId,
                Map.of("paymentKey", paymentKey, "orderId", orderId, "amount", amount));
    }

    /** 결제 취소(전액). 환불 사유는 손님에게도 보인다. */
    public Payment cancel(String paymentKey, String reason, String idempotencyKey) {
        requireReady();
        return call("/v1/payments/" + paymentKey + "/cancel", idempotencyKey,
                Map.of("cancelReason", reason));
    }

    private Payment call(String path, String idempotencyKey, Map<String, Object> body) {
        try {
            return http.post()
                    .uri(path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Idempotency-Key", idempotencyKey)
                    .body(body)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (req, res) -> {
                        TossError err = readError(res.getBody());
                        log.warn("토스 거절 path={} status={} code={}", path.replaceAll("/payments/[^/]+/", "/payments/*/"),
                                res.getStatusCode().value(), err.code());
                        throw new PaymentException(err.code(), err.message());
                    })
                    .body(Payment.class);
        } catch (PaymentException e) {
            throw e;
        } catch (RestClientException e) {
            log.error("토스 호출 실패 path={}", path.replaceAll("/payments/[^/]+/", "/payments/*/"), e);
            throw new PaymentException("NETWORK", "결제사와 연결하지 못했습니다. 잠시 후 다시 시도해 주세요.");
        }
    }

    private static TossError readError(java.io.InputStream in) {
        try {
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            var node = new tools.jackson.databind.ObjectMapper().readTree(text);
            return new TossError(node.path("code").asString("UNKNOWN"),
                    node.path("message").asString("결제를 처리하지 못했습니다."));
        } catch (Exception e) {
            return new TossError("UNKNOWN", "결제를 처리하지 못했습니다.");
        }
    }

    private void requireReady() {
        if (!ready) {
            throw new PaymentException("NOT_CONFIGURED", "결제 준비 중입니다.");
        }
    }

    private record TossError(String code, String message) {
    }

    /** 토스 결제 객체 중 우리가 쓰는 값만. 나머지 필드는 무시한다. */
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public record Payment(String paymentKey, String orderId, String status, String method,
                          Long totalAmount, Long balanceAmount, OffsetDateTime approvedAt) {
    }

    /** 결제사가 거절했거나 연결이 안 됐다. code 는 토스 오류 코드(또는 NETWORK · NOT_CONFIGURED). */
    public static class PaymentException extends RuntimeException {
        private final String code;

        public PaymentException(String code, String message) {
            super(message);
            this.code = code;
        }

        public String getCode() {
            return code;
        }
    }
}
