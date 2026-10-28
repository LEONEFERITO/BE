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
    private final com.leoneferito.order.ShippingPolicy shipping;

    public AdminOrderController(AdminOrderService service, AdminReturnService returns,
                                com.leoneferito.order.ShippingPolicy shipping) {
        this.service = service;
        this.returns = returns;
        this.shipping = shipping;
    }

    /** 배송비 면제 스위치(V32) — 지금 상태. */
    @GetMapping("/shipping-waiver")
    public ShippingWaiver shippingWaiver() {
        return new ShippingWaiver(shipping.isWaived());
    }

    /** 배송비 면제 켜기 · 끄기. 켜면 다음 주문부터 배송비 0원(제주 · 도서산간 추가 포함). 이미 만든 주문은 그대로. */
    @org.springframework.web.bind.annotation.PutMapping("/shipping-waiver")
    public ShippingWaiver setShippingWaiver(@AuthenticationPrincipal MemberPrincipal admin,
                                            @RequestBody ShippingWaiver in) {
        shipping.setWaived(in.waived(), admin == null ? null : admin.getEmail());
        org.slf4j.LoggerFactory.getLogger(AdminOrderController.class)
                .info("배송비 면제 {} by={}", in.waived() ? "켬" : "끔", admin == null ? null : admin.getEmail());
        return new ShippingWaiver(shipping.isWaived());
    }

    public record ShippingWaiver(boolean waived) {
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

    /** 주문 목록 엑셀 — 보낼 주문(결제 완료 · 제작 중)의 받는 분 · 주소 · 연락처 (2026-10-28). */
    @GetMapping("/export")
    public ResponseEntity<byte[]> export() {
        String day = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul")).toString();
        return ResponseEntity.ok()
                .contentType(org.springframework.http.MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"leoneferito-orders-" + day + ".xlsx\"")
                .body(service.exportShippable());
    }

    /**
     * 송장 일괄 입력 (2026-10-28) — 엑셀 · CSV 의 주문번호 · 송장번호(· 택배사)로 여러 주문을 발송 처리.
     * 한 줄이라도 틀리면 아무것도 하지 않고 400 IMPORT_INVALID + 줄 · 칸 · 이유.
     */
    @PostMapping("/tracking-import")
    public TrackingImportResult trackingImport(@AuthenticationPrincipal MemberPrincipal me,
                                               @RequestParam("file") org.springframework.web.multipart.MultipartFile file,
                                               @RequestParam(value = "courier", required = false) String courier)
            throws java.io.IOException {
        return new TrackingImportResult(service.importTracking(me.getId(), file.getBytes(), courier));
    }

    public record TrackingImportResult(java.util.List<AdminOrderService.Shipped> shipped) {
    }

    /** 취소 사유는 필수다. 손님에게 환불 사유로 보이고 토스에도 남는다. */
    public record Cancel(@NotBlank @Size(max = 200) String reason) {
    }
}
