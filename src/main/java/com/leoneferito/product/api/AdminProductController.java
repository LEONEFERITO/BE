package com.leoneferito.product.api;

import com.leoneferito.product.AdminProductQueryService;
import com.leoneferito.product.AdminProductService;
import com.leoneferito.product.ProductCategory;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 관리자 상품 API.
 *
 * <p>경로가 {@code /api/admin} 으로 시작한다. 이 접두사가 두 가지를 동시에 정한다:
 * <ul>
 *   <li>{@code SecurityConfig} 가 ADMIN 권한을 요구한다</li>
 *   <li>{@code GlobalExceptionHandler} 가 <b>필드별 검증 메시지</b>를 내보낸다 —
 *       관리자는 신뢰 경계 안이고 어느 칸이 틀렸는지 알아야 고칠 수 있다.
 *       공개 API 는 같은 상황에서 뭉뚱그린 한 줄만 준다.</li>
 * </ul>
 *
 * <p>삭제는 주문 이력이 없는 상품만 된다(잘못 만든 초안 · 시험 등록). 주문이 한 번이라도 걸린 상품은 409 —
 * 주문 기록은 5년 보관이라, 판매가 끝난 상품은 지우지 않고 비공개로 내린다.
 */
@RestController
@RequestMapping("/api/admin/products")
public class AdminProductController {

    private final AdminProductService service;
    private final AdminProductQueryService queries;
    private final com.leoneferito.product.ProductQueryService categories;

    public AdminProductController(AdminProductService service, AdminProductQueryService queries,
                                  com.leoneferito.product.ProductQueryService categories) {
        this.categories = categories;
        this.service = service;
        this.queries = queries;
    }

    /** 목록. 초안 포함 — 공개 목록과 반대다. */
    @GetMapping
    public List<AdminProductResponse.Row> list() {
        return queries.list();
    }

    /** 수정 화면이 채울 현재 상태. 받은 그대로 PUT 으로 돌려보낼 수 있다. */
    @GetMapping("/{id}")
    public AdminProductResponse.Edit get(@PathVariable UUID id) {
        return queries.edit(id);
    }

    /**
     * 등록.
     *
     * <p>항상 초안(DRAFT)으로 만들어진다. 등록과 공개를 한 번에 하지 않는 이유는
     * {@link AdminProductService} 주석 참고.
     */
    @PostMapping
    public ResponseEntity<Map<String, UUID>> create(
            @Valid @RequestBody AdminProductRequests.Save request) {

        UUID id = service.create(request);
        // 만든 것의 주소를 Location 으로 알려준다. 화면이 바로 편집으로 이동할 수 있다.
        return ResponseEntity.created(URI.create("/api/admin/products/" + id))
                .body(Map.of("id", id));
    }

    @PutMapping("/{id}")
    public ResponseEntity<Void> update(
            @PathVariable UUID id, @Valid @RequestBody AdminProductRequests.Save request) {

        service.update(id, request);
        return ResponseEntity.noContent().build();
    }

    /** 진열 순서 (메인 구성) — 공개 상품 id 를 보여 줄 순서대로. */
    @PutMapping("/order")
    public ResponseEntity<Void> reorder(@jakarta.validation.Valid @RequestBody Reorder request) {
        service.reorderPublished(request.ids());
        return ResponseEntity.noContent().build();
    }

    public record Reorder(@jakarta.validation.constraints.NotNull
                          @jakarta.validation.constraints.Size(max = 500) java.util.List<UUID> ids) {
    }

    /** 분류 메뉴 순서 (V27) — 지금 순서. 손님용 {@code GET /api/products/category-order} 와 같은 값. */
    @GetMapping("/category-order")
    public ProductController.CategoryOrderView categoryOrder() {
        return new ProductController.CategoryOrderView(categories.categoryOrder());
    }

    /** 분류 메뉴 순서 바꾸기 — 분류 전부를 앞에서부터. 빠지거나 겹치면 409. 저장하면 손님 화면을 다시 만든다. */
    @PutMapping("/category-order")
    public ResponseEntity<Void> reorderCategories(@jakarta.validation.Valid @RequestBody CategoryReorder request) {
        service.reorderCategories(request.categories());
        return ResponseEntity.noContent().build();
    }

    public record CategoryReorder(@jakarta.validation.constraints.NotNull
                                  @jakarta.validation.constraints.Size(max = 20)
                                  java.util.List<@jakarta.validation.constraints.NotNull
                                          ProductCategory> categories) {
    }

    @PostMapping("/{id}/publish")
    public ResponseEntity<Void> publish(@PathVariable UUID id) {
        service.publish(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/unpublish")
    public ResponseEntity<Void> unpublish(@PathVariable UUID id) {
        service.unpublish(id);
        return ResponseEntity.noContent().build();
    }

    /** 복제 — 초안으로 복사본을 만들고 그 id 를 준다. 화면은 바로 수정 화면으로 간다. */
    @PostMapping("/{id}/duplicate")
    public ResponseEntity<Map<String, UUID>> duplicate(@PathVariable UUID id) {
        UUID copy = service.duplicate(id);
        return ResponseEntity.created(URI.create("/api/admin/products/" + copy)).body(Map.of("id", copy));
    }

    /** 삭제 — 주문 이력이 있으면 409(PRODUCT_HAS_ORDERS). 담아 둔 장바구니 줄 · 이 상품의 메인 배너도 함께 지워진다. */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
