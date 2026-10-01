package com.leoneferito.order.api;

import com.leoneferito.auth.MemberPrincipal;
import com.leoneferito.order.OrderService;
import com.leoneferito.order.ShopOrder;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 주문 · 결제 (손님). 회원만. 남의 주문번호는 "없다" 와 같은 404 다. */
@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderService orders;

    public OrderController(OrderService orders) {
        this.orders = orders;
    }

    /** 결제를 받을 수 있는가 (토스 키 · 배송비 정책). 주문서 화면이 결제 버튼을 열지 정한다. */
    @GetMapping("/readiness")
    public Map<String, Boolean> readiness() {
        return Map.of("paymentReady", orders.paymentReady());
    }

    /** 주문서 화면의 금액. 고른 장바구니 줄로 서버가 계산한다 (주문서 작성과 같은 계산). */
    @PostMapping("/quote")
    public OrderService.Quote quote(@AuthenticationPrincipal MemberPrincipal me, @Valid @RequestBody QuoteRequest request) {
        return orders.quote(me.getId(), request.cartItemIds());
    }

    public record QuoteRequest(@NotEmpty @Size(max = 50) List<UUID> cartItemIds) {
    }

    @PostMapping
    public ResponseEntity<OrderResponse.Created> create(@AuthenticationPrincipal MemberPrincipal me,
                                                        @Valid @RequestBody Create request) {
        ShopOrder order = orders.create(me.getId(), request.cartItemIds(),
                new ShopOrder.Recipient(request.recipientName().trim(), request.recipientPhone().trim(),
                        request.zipCode().trim(), request.address1().trim(),
                        blankToNull(request.address2()), blankToNull(request.deliveryMemo())));
        return ResponseEntity.status(HttpStatus.CREATED).body(new OrderResponse.Created(
                order.getOrderNumber(), order.getOrderName(), order.getTotalAmountKrw(),
                me.getId().toString(), me.getName(), me.getEmail()));
    }

    /** 토스 successUrl 로 돌아온 화면이 부른다. 금액은 서버가 저장된 금액과 대조한다. */
    @PostMapping("/{orderNumber}/confirm")
    public OrderResponse.Detail confirm(@AuthenticationPrincipal MemberPrincipal me, @PathVariable String orderNumber,
                                        @Valid @RequestBody Confirm request) {
        return OrderResponse.Detail.of(
                orders.confirm(me.getId(), orderNumber, request.paymentKey(), request.amount()));
    }

    @PostMapping("/{orderNumber}/cancel")
    public OrderResponse.Detail cancel(@AuthenticationPrincipal MemberPrincipal me,
                                       @PathVariable String orderNumber) {
        return OrderResponse.Detail.of(orders.cancelByCustomer(me.getId(), orderNumber));
    }

    @GetMapping
    public List<OrderResponse.Summary> mine(@AuthenticationPrincipal MemberPrincipal me) {
        return orders.myOrders(me.getId()).stream().map(OrderResponse.Summary::of).toList();
    }

    @GetMapping("/{orderNumber}")
    public OrderResponse.Detail detail(@AuthenticationPrincipal MemberPrincipal me,
                                       @PathVariable String orderNumber) {
        return OrderResponse.Detail.of(orders.myOrder(me.getId(), orderNumber));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    /**
     * 주문서. agree = 결제 전 확인 (주문 상품 · 금액 · 제작 기간 · 교환/반품 조건 —
     * 전자상거래법 제8조, 약관 제9조). 확인 시각이 주문에 남는다(agreed_at).
     */
    public record Create(
            @NotEmpty @Size(max = 50) List<UUID> cartItemIds,
            @NotBlank @Size(max = 50) String recipientName,
            @NotBlank @Pattern(regexp = "^[0-9-]{9,20}$", message = "연락처 형식이 올바르지 않습니다.")
            String recipientPhone,
            @NotBlank @Pattern(regexp = "^[0-9]{5}$", message = "우편번호는 숫자 5자리입니다.") String zipCode,
            @NotBlank @Size(max = 200) String address1,
            @Size(max = 200) String address2,
            @Size(max = 100) String deliveryMemo,
            @AssertTrue(message = "주문 내용과 결제 조건을 확인해 주세요.") boolean agree) {
    }

    public record Confirm(@NotBlank @Size(max = 200) String paymentKey, @Positive long amount) {
    }
}
