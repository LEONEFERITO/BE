package com.leoneferito.order.api;

import com.leoneferito.auth.MemberPrincipal;
import com.leoneferito.order.AdminReturnService;
import com.leoneferito.order.ReturnStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 관리자 교환·반품 API (ADMIN). 신청을 지우는 API 는 없다 — 주문과 같은 계약 기록. */
@RestController
@RequestMapping("/api/admin/returns")
public class AdminReturnController {

    private final AdminReturnService service;

    public AdminReturnController(AdminReturnService service) {
        this.service = service;
    }

    @GetMapping
    public ReturnResponse.AdminPage search(@RequestParam(required = false) ReturnStatus status,
                                           @RequestParam(defaultValue = "false") boolean open,
                                           @RequestParam(defaultValue = "0") int page) {
        return ReturnResponse.AdminPage.of(service.search(status, open, page));
    }

    @GetMapping("/{id}")
    public ReturnResponse.AdminDetail detail(@PathVariable UUID id) {
        return ReturnResponse.AdminDetail.of(service.detail(id));
    }

    @PostMapping("/{id}/approve")
    public ResponseEntity<Void> approve(@AuthenticationPrincipal MemberPrincipal me, @PathVariable UUID id,
                                        @Valid @RequestBody Note request) {
        service.approve(me.getId(), id, request.note());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/collected")
    public ResponseEntity<Void> collected(@AuthenticationPrincipal MemberPrincipal me, @PathVariable UUID id,
                                          @Valid @RequestBody Note request) {
        service.markCollected(me.getId(), id, request.note());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/reject")
    public ResponseEntity<Void> reject(@AuthenticationPrincipal MemberPrincipal me, @PathVariable UUID id,
                                       @Valid @RequestBody Reject request) {
        service.reject(me.getId(), id, request.reason());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/refund")
    public ResponseEntity<Void> refund(@AuthenticationPrincipal MemberPrincipal me, @PathVariable UUID id,
                                       @Valid @RequestBody Refund request) {
        service.completeReturn(me.getId(), id, request.refundAmountKrw());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/reship")
    public ResponseEntity<Void> reship(@AuthenticationPrincipal MemberPrincipal me, @PathVariable UUID id,
                                       @Valid @RequestBody Reship request) {
        service.completeExchange(me.getId(), id, request.courier(), request.trackingNumber());
        return ResponseEntity.noContent().build();
    }

    /** 손님에게 보이는 안내 (회수 방법 · 일정). 비워도 된다. */
    public record Note(@Size(max = 300) String note) {
    }

    /** 거절 사유는 필수 — 손님에게 그대로 보인다. */
    public record Reject(@NotBlank @Size(max = 200) String reason) {
    }

    public record Refund(@Min(0) long refundAmountKrw) {
    }

    public record Reship(@NotBlank @Size(max = 50) String courier, @NotBlank @Size(max = 50) String trackingNumber) {
    }
}
