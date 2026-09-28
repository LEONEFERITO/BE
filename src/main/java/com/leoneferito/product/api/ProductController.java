package com.leoneferito.product.api;

import com.leoneferito.product.ProductCategory;
import com.leoneferito.product.ProductLine;
import com.leoneferito.product.ProductQueryService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 공개 상품 API. 인증이 필요 없다.
 *
 * <p>쓰기(등록·수정)는 여기 없다. 관리자 API 는 인증이 붙은 뒤
 * {@code /api/admin/products} 로 따로 만든다 — 지금 열면 아무나 상품을 고칠 수 있다.
 *
 * <p>필터를 문자열이 아니라 enum 으로 받는 이유: 모르는 값이 오면 Spring 이 변환 단계에서
 * 거부하므로, 조건이 조용히 무시된 채 <b>전체 목록이 나가는 일</b>이 없다.
 * 잘못된 필터는 빈 목록이 아니라 400 이어야 한다 — 그래야 프론트 버그가 드러난다.
 */
@RestController
@RequestMapping("/api/products")
public class ProductController {

    private final ProductQueryService query;

    public ProductController(ProductQueryService query) {
        this.query = query;
    }

    @GetMapping
    public List<ProductResponse.Summary> list(
            @RequestParam(required = false) ProductCategory category,
            @RequestParam(required = false) ProductLine line) {
        return query.list(category, line);
    }

    /**
     * 상세.
     *
     * <p>비공개(DRAFT) 상품도 404 다. 403 으로 나누면 "그 slug 는 존재한다" 가 새어
     * 출시 전 상품명을 알아낼 수 있게 된다.
     */
    @GetMapping("/{slug}")
    public ProductResponse.Detail detail(@PathVariable String slug) {
        return query.detail(slug);
    }
}
