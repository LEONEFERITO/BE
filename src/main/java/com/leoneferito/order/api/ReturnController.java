package com.leoneferito.order.api;

import com.leoneferito.auth.MemberPrincipal;
import com.leoneferito.order.ReturnReason;
import com.leoneferito.order.ReturnService;
import com.leoneferito.order.ReturnType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 교환·반품 (손님). 회원만. 신청 내역은 주문 상세({@code GET /api/orders/{no}})에 붙어 나간다.
 * 남의 주문·신청은 "없다" 와 같은 404 다.
 */
@RestController
public class ReturnController {

    private final ReturnService returns;

    public ReturnController(ReturnService returns) {
        this.returns = returns;
    }

    @PostMapping("/api/orders/{orderNumber}/returns")
    public ResponseEntity<ReturnResponse.View> request(@AuthenticationPrincipal MemberPrincipal me,
                                                       @PathVariable String orderNumber,
                                                       @Valid @RequestBody Create request) {
        var created = returns.request(me.getId(), orderNumber, request.type(), request.reason(), request.detail(),
                request.items().stream()
                        .map(i -> new ReturnService.Line(i.orderItemId(), i.quantity(), i.exchangeSize()))
                        .toList());
        return ResponseEntity.status(HttpStatus.CREATED).body(ReturnResponse.View.of(created));
    }

    @PostMapping("/api/returns/{id}/withdraw")
    public ReturnResponse.View withdraw(@AuthenticationPrincipal MemberPrincipal me, @PathVariable UUID id) {
        return ReturnResponse.View.of(returns.withdraw(me.getId(), id));
    }

    public record Create(@NotNull ReturnType type, @NotNull ReturnReason reason,
                         @Size(max = 1000) String detail,
                         @NotEmpty @Size(max = 50) List<@Valid Line> items) {
    }

    public record Line(@NotNull UUID orderItemId, @Min(1) @Max(10) int quantity, @Size(max = 20) String exchangeSize) {
    }
}
