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

    /** slug 는 URL 이라 중복되면 안 된다. DB 유니크 제약과 짝을 이루는 사전 확인용이다. */
    boolean existsBySlug(String slug);
}
