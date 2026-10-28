package com.leoneferito.product;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 상품 조회.
 *
 * <p>공개용 조회 메서드는 이름에 {@code Published} 를 붙이고 <b>쿼리 안에 상태 조건을 넣는다.</b>
 * "호출하는 쪽에서 거르겠지" 로 두면 어느 한 화면에서 DRAFT 가 새는 날이 온다.
 * 조건이 쿼리에 있으면 새로 만든 화면도 자동으로 안전하다.
 *
 * <p>목록에서 {@code JOIN FETCH} 를 쓰는 이유: 카드가 대표 이미지를 그리므로 상품마다
 * 이미지 쿼리가 한 번씩 더 나간다(N+1). 20개면 21번이다. 한 번에 가져온다.
 * 컬렉션을 조인하면 상품 행이 이미지 수만큼 곱해지므로 {@code DISTINCT} 가 필요하다.
 */
public interface ProductRepository extends JpaRepository<Product, UUID> {

    @Query("""
            SELECT DISTINCT p FROM Product p
            LEFT JOIN FETCH p.images
            WHERE p.status = com.leoneferito.product.ProductStatus.PUBLISHED
            ORDER BY p.displayOrder ASC, p.createdAt DESC
            """)
    List<Product> findPublished();

    @Query("""
            SELECT DISTINCT p FROM Product p
            LEFT JOIN FETCH p.images
            WHERE p.status = com.leoneferito.product.ProductStatus.PUBLISHED
              AND p.category = :category
            ORDER BY p.displayOrder ASC, p.createdAt DESC
            """)
    List<Product> findPublishedByCategory(@Param("category") ProductCategory category);

    /*
     * 상세는 사이즈·실측까지 필요하지만 여기서 같이 fetch 하지 않는다.
     * 컬렉션 둘을 한 쿼리에서 조인하면 두 컬렉션의 곱집합이 되어 행이 폭증하고
     * (Hibernate 는 MultipleBagFetchException 으로 아예 거부한다),
     * 사이즈·실측은 트랜잭션 안에서 이어 읽으면 쿼리 두 번이면 끝난다.
     */
    @Query("""
            SELECT DISTINCT p FROM Product p
            LEFT JOIN FETCH p.images
            WHERE p.status = com.leoneferito.product.ProductStatus.PUBLISHED
              AND p.slug = :slug
            """)
    Optional<Product> findPublishedBySlug(@Param("slug") String slug);

    /**
     * 관리자 목록. 상태와 무관하게 전부 — 초안이 보여야 이어서 고친다.
     *
     * <p>최근에 고친 것이 위로 온다. 관리자는 방금 만지던 걸 찾는다.
     * 이미지의 media 까지 fetch 하는 이유: 목록이 대표 이미지 주소를 만들 때
     * 상품마다 media 쿼리가 한 번씩 더 나간다(N+1).
     */
    @Query("""
            SELECT DISTINCT p FROM Product p
            LEFT JOIN FETCH p.images i
            LEFT JOIN FETCH i.media
            ORDER BY p.updatedAt DESC
            """)
    List<Product> findAllForAdmin();

    /** slug 는 URL 이라 중복되면 안 된다. DB 유니크 제약과 짝을 이루는 사전 확인용이다. */
    boolean existsBySlug(String slug);

    /**
     * 재고를 줄이거나 되돌릴 사이즈 한 줄 — 행을 잠그고 읽는다(V30). 두 손님이 마지막 한 벌을 동시에 결제하면
     * 한쪽은 기다렸다가 줄어든 수량을 본다. 사이즈가 지워졌으면 비어 있다.
     */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM ProductSku s WHERE s.product.id = :productId AND s.piece IS NULL AND s.size = :size")
    Optional<ProductSku> findSkuForUpdate(@Param("productId") UUID productId, @Param("size") String size);

    /** 수트 조각 사이즈 한 줄 — {@link #findSkuForUpdate} 와 같이 잠그고 읽는다 (V43). */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM ProductSku s WHERE s.product.id = :productId AND s.piece = :piece AND s.size = :size")
    Optional<ProductSku> findPieceSkuForUpdate(@Param("productId") UUID productId, @Param("piece") Product.Piece piece,
            @Param("size") String size);

    /**
     * 주문 줄 하나가 가리키는 사이즈 칸들을 잠그고 읽는다 — 보통 상품은 한 칸, 수트 구성("3피스 · 자켓 L / …")은 조각마다 한 칸.
     * 지워진 칸은 빠진다(재고를 셀 곳이 없다).
     */
    default List<ProductSku> findSkusForUpdate(UUID productId, String size) {
        Optional<Product.SuitChoice> choice = Product.SuitChoice.parse(size);
        if (choice.isEmpty()) {
            return findSkuForUpdate(productId, size).stream().toList();
        }
        return choice.get().set().pieces().stream()
                .map(p -> findPieceSkuForUpdate(productId, p, choice.get().sizes().get(p)))
                .flatMap(Optional::stream)
                .toList();
    }

    /** 이 상품이 걸린 주문 항목이 하나라도 있는가 — 있으면 지우지 못한다(주문 기록 5년 보관). 결제 전 주문도 센다. */
    @Query(value = "SELECT EXISTS (SELECT 1 FROM order_item WHERE product_id = :productId)", nativeQuery = true)
    boolean hasOrderItems(@Param("productId") UUID productId);

    /**
     * 이 상품을 담아 둔 장바구니 줄을 모두 지운다 — 상품을 지우기 직전에. 장바구니는 주문이 아니라 지워도 된다.
     * 상품 → 장바구니 FK 에 CASCADE 를 걸지 않은 이유: 주문 항목(order_item)과 같은 실수를 막으려고 지우는 길을 이 한 곳으로 둔다.
     */
    @org.springframework.data.jpa.repository.Modifying
    @Query(value = "DELETE FROM cart_item WHERE product_id = :productId", nativeQuery = true)
    int deleteCartItems(@Param("productId") UUID productId);
}
