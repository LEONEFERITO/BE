package com.leoneferito.order.api;

import com.leoneferito.auth.MemberPrincipal;
import com.leoneferito.order.CartService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 장바구니. 회원만 (비회원 주문 없음 — D1). 대상은 언제나 로그인한 본인이다.
 * 바뀐 뒤에는 장바구니 전체를 돌려준다 — 화면이 합계를 따로 계산하지 않게.
 */
@RestController
@RequestMapping("/api/cart")
public class CartController {

    private final CartService cart;

    public CartController(CartService cart) {
        this.cart = cart;
    }

    @GetMapping
    public CartService.CartView view(@AuthenticationPrincipal MemberPrincipal me) {
        return cart.view(me.getId());
    }

    @PostMapping("/items")
    public CartService.CartView add(@AuthenticationPrincipal MemberPrincipal me, @Valid @RequestBody Add request) {
        cart.add(me.getId(), request.slug(), request.size(), request.quantity());
        return cart.view(me.getId());
    }

    @PatchMapping("/items/{id}")
    public CartService.CartView change(@AuthenticationPrincipal MemberPrincipal me, @PathVariable UUID id,
                                       @Valid @RequestBody Change request) {
        cart.changeQuantity(me.getId(), id, request.quantity());
        return cart.view(me.getId());
    }

    @DeleteMapping("/items/{id}")
    public CartService.CartView remove(@AuthenticationPrincipal MemberPrincipal me, @PathVariable UUID id) {
        cart.remove(me.getId(), id);
        return cart.view(me.getId());
    }

    public record Add(@NotBlank @Size(max = 80) String slug,
                      @NotBlank @Size(max = 20) String size,
                      @Min(1) @Max(10) int quantity) {
    }

    public record Change(@Min(1) @Max(10) int quantity) {
    }
}
