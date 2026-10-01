package com.leoneferito.product.api;

import com.leoneferito.product.AdminProductQueryService;
import com.leoneferito.product.AdminProductService;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
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
 * <p>삭제가 없다. 판매가 끝난 상품은 지우는 게 아니라 {@code ARCHIVED} 로 내린다 —
 * 지우면 그 상품이 걸린 과거 주문의 내용이 사라진다. 지금은 공개/비공개만 둔다.
 */
@RestController
@RequestMapping("/api/admin/products")
public class AdminProductController {

    private final AdminProductService service;
    private final AdminProductQueryService queries;

    public AdminProductController(AdminProductService service, AdminProductQueryService queries) {
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
}
