package com.leoneferito.order.api;

import com.leoneferito.auth.MemberPrincipal;
import com.leoneferito.order.AdminOrderService;
import com.leoneferito.order.AdminReturnService;
import com.leoneferito.order.ShopOrder;
import com.leoneferito.order.OrderStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 관리자 주문 API (ADMIN). 주문을 지우는 API 는 없다 — 결제 기록 5년 보관. */
@RestController
@RequestMapping("/api/admin/orders")
public class AdminOrderController {

    private final AdminOrderService service;
    private final AdminReturnService returns;

    public AdminOrderController(AdminOrderService service, AdminReturnService returns) {
        this.service = service;
        this.returns = returns;
    }

    @GetMapping
    public OrderResponse.AdminPage search(@RequestParam(defaultValue = "") String q,
                                          @RequestParam(required = false) OrderStatus status,
                                          @RequestParam(defaultValue = "0") int page) {
        return OrderResponse.AdminPage.of(service.search(q, status, page));
    }

    @GetMapping("/{orderNumber}")
    public OrderResponse.AdminDetail detail(@PathVariable String orderNumber) {
        ShopOrder order = service.detail(orderNumber);
        return OrderResponse.adminDetail(order, returns.forOrder(order.getId()));
    }

    @PostMapping("/{orderNumber}/start-production")
    public ResponseEntity<Void> startProduction(@AuthenticationPrincipal MemberPrincipal me,
                                                @PathVariable String orderNumber) {
        service.startProduction(me.getId(), orderNumber);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{orderNumber}/ship")
    public ResponseEntity<Void> ship(@AuthenticationPrincipal MemberPrincipal me, @PathVariable String orderNumber,
                                     @Valid @RequestBody Ship request) {
        service.ship(me.getId(), orderNumber, request.courier(), request.trackingNumber());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{orderNumber}/deliver")
    public ResponseEntity<Void> deliver(@AuthenticationPrincipal MemberPrincipal me,
                                        @PathVariable String orderNumber) {
        service.markDelivered(me.getId(), orderNumber);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{orderNumber}/cancel")
    public ResponseEntity<Void> cancel(@AuthenticationPrincipal MemberPrincipal me, @PathVariable String orderNumber,
                                       @Valid @RequestBody Cancel request) {
        service.cancel(me.getId(), orderNumber, request.reason());
        return ResponseEntity.noContent().build();
    }

    public record Ship(@NotBlank @Size(max = 50) String courier, @NotBlank @Size(max = 50) String trackingNumber) {
    }

    /** 취소 사유는 필수다. 손님에게 환불 사유로 보이고 토스에도 남는다. */
    public record Cancel(@NotBlank @Size(max = 200) String reason) {
    }
}
