package com.leoneferito.order.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.leoneferito.common.error.ResourceNotFoundException;
import com.leoneferito.order.OrderService;
import com.leoneferito.order.ShopOrder;
import com.leoneferito.payment.NicePayClient;
import com.leoneferito.payment.PaymentGateway.PaymentException;
import java.net.URI;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * 나이스페이 — 결제창 복귀(return)와 웹훅. 결제사가 나이스페이일 때만 켜진다(기본).
 *
 * <h2>결제창 복귀 {@code POST /api/payments/nicepay/return}</h2>
 * 손님이 결제창에서 인증을 마치면 나이스페이가 손님 브라우저를 이 주소로 보낸다(폼 POST).
 * 손님 화면은 정적 파일이라 POST 를 받을 수 없어서 API 가 받는다. 여기서
 * <ol>
 *   <li>나이스페이 서명을 확인한다 — 비밀 키로만 만들 수 있다. 위조한 폼은 여기서 끝난다.</li>
 *   <li>승인한다 — 금액은 폼의 값이 아니라 주문에 저장된 금액이다(OrderService.approveReturned).</li>
 *   <li>손님 화면으로 돌려보낸다 — 성공 /order/success/?orderNumber=… · 실패 /order/fail/?code=…&message=…</li>
 * </ol>
 * 다른 사이트에서 오는 폼이라 로그인 쿠키가 실리지 않는다 — 주문번호로 찾는다. CSRF 는 서명이 대신한다(SecurityConfig).
 *
 * <h2>웹훅 {@code POST /api/payments/nicepay/webhook}</h2>
 * 가맹점관리자 › 개발정보 › 웹훅에 이 주소를 등록한다. 본문은 믿지 않는다 — 서명이 맞으면 tid 로 나이스페이에 다시 물어
 * 맞춘다(OrderService.reconcile). 나이스페이는 답의 본문이 "OK" 가 아니면 실패로 보고 다시 보낸다.
 */
@RestController
@ConditionalOnProperty(name = "app.payment.provider", havingValue = "nicepay", matchIfMissing = true)
public class NicePayController {

    private static final Logger log = LoggerFactory.getLogger(NicePayController.class);
    private static final String ORDER_NUMBER = "^LF\\d{8}-[A-Z2-9]{8}$";
    private static final String TID = "^[A-Za-z0-9_-]{1,64}$";

    private final OrderService orders;
    private final NicePayClient nicepay;
    private final String frontBaseUrl;

    public NicePayController(OrderService orders, NicePayClient nicepay,
                             @Value("${app.front.base-url}") String frontBaseUrl) {
        this.orders = orders;
        this.nicepay = nicepay;
        this.frontBaseUrl = frontBaseUrl.replaceAll("/+$", "");
    }

    @PostMapping(value = "/api/payments/nicepay/return", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<Void> returned(@RequestParam Map<String, String> form) {
        String orderNumber = form.get("orderId");
        if (orderNumber == null || !orderNumber.matches(ORDER_NUMBER)) {
            log.warn("나이스 복귀: 주문번호 형식이 아님");
            return fail("INVALID_REQUEST", "결제 정보가 올바르지 않습니다.", null);
        }
        String code = form.get("authResultCode");
        if (!"0000".equals(code)) {
            // 인증 실패 · 손님이 결제창에서 그만둠 — 돈은 움직이지 않았다. 주문서와 장바구니는 그대로다.
            log.info("나이스 복귀: 인증 실패 orderNumber={} code={}", orderNumber, code);
            return fail(code == null ? "AUTH_FAILED" : code,
                    orElse(form.get("authResultMsg"), "결제가 완료되지 않았습니다."), orderNumber);
        }
        String tid = form.get("tid");
        String amount = form.get("amount");
        if (tid == null || !tid.matches(TID) || amount == null || !amount.matches("^\\d{1,12}$")) {
            log.warn("나이스 복귀: 값 형식이 아님 orderNumber={}", orderNumber);
            return fail("INVALID_REQUEST", "결제 정보가 올바르지 않습니다.", orderNumber);
        }
        if (!nicepay.verifyAuthSignature(form.get("authToken"), form.get("clientId"), amount, form.get("signature"))) {
            log.warn("나이스 복귀: 서명 불일치 — 승인하지 않음 orderNumber={}", orderNumber);
            return fail("INVALID_SIGNATURE", "결제 정보를 확인하지 못했습니다. 다시 시도해 주세요.", orderNumber);
        }
        try {
            ShopOrder order = orders.approveReturned(orderNumber, tid, Long.parseLong(amount));
            return redirect(UriComponentsBuilder.fromUriString(frontBaseUrl + "/order/success/")
                    .queryParam("orderNumber", order.getOrderNumber()));
        } catch (PaymentException e) {
            return fail(e.getCode(), e.getMessage(), orderNumber);
        } catch (OrderService.AmountMismatchException e) {
            return fail("AMOUNT_MISMATCH", "결제 금액이 주문 금액과 다릅니다. 주문서를 다시 만들어 주세요.", orderNumber);
        } catch (ShopOrder.OrderStateException e) {
            return fail("ORDER_STATE", e.getMessage(), orderNumber);
        } catch (ResourceNotFoundException e) {
            return fail("ORDER_NOT_FOUND", "주문을 찾지 못했습니다.", orderNumber);
        }
    }

    @PostMapping(value = "/api/payments/nicepay/webhook", produces = "text/html;charset=utf-8")
    public ResponseEntity<String> webhook(@RequestBody Event event) {
        String amount = event.amount() == null ? null : String.valueOf(event.amount());
        if (event.tid() == null || !event.tid().matches(TID)
                || !nicepay.verifyWebhookSignature(event.tid(), amount, event.ediDate(), event.signature())) {
            // 서명이 맞지 않는 알림(가맹점관리자의 "웹훅 테스트" 포함)은 무시한다. OK 로 답해야 같은 알림을 다시 보내지 않는다.
            log.info("나이스 웹훅: 처리하지 않음 status={}", event.status());
            return ResponseEntity.ok("OK");
        }
        // 조회가 실패하면 오류를 그대로 낸다 — 나이스페이가 다시 보낸다.
        orders.reconcile(event.tid());
        return ResponseEntity.ok("OK");
    }

    private ResponseEntity<Void> fail(String code, String message, String orderNumber) {
        UriComponentsBuilder b = UriComponentsBuilder.fromUriString(frontBaseUrl + "/order/fail/")
                .queryParam("code", code)
                .queryParam("message", message);
        if (orderNumber != null) {
            b.queryParam("orderNumber", orderNumber);
        }
        return redirect(b);
    }

    /** 303 — 브라우저가 POST 를 GET 으로 바꿔 손님 화면을 연다. */
    private static ResponseEntity<Void> redirect(UriComponentsBuilder b) {
        URI to = b.encode().build().toUri();
        return ResponseEntity.status(HttpStatus.SEE_OTHER).location(to).build();
    }

    private static String orElse(String v, String fallback) {
        return v == null || v.isBlank() ? fallback : v;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Event(String resultCode, String tid, String orderId, Long amount, String status, String ediDate,
                        String signature) {
    }
}