package com.leoneferito.order;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ShopOrderRepository extends JpaRepository<ShopOrder, UUID> {

    Optional<ShopOrder> findByOrderNumber(String orderNumber);

    /** 내 주문. 결제 전에 떠난 주문(PENDING_PAYMENT)은 빼고 부른다. */
    List<ShopOrder> findByMemberIdAndStatusInOrderByCreatedAtDesc(UUID memberId, Collection<OrderStatus> statuses);

    boolean existsByMemberIdAndStatusIn(UUID memberId, Collection<OrderStatus> statuses);

    /**
     * 관리자 검색 — 주문번호 · 받는 분 · 연락처(하이픈 무시). 패턴 규칙은 AdminMemberService 와 같다
     * ({@code :q} 는 소문자·이스케이프된 %…% 패턴, {@code :digits} 는 숫자만 모은 패턴).
     */
    @Query("""
            SELECT o FROM ShopOrder o
            WHERE o.status IN :statuses
              AND (lower(o.orderNumber) LIKE :q ESCAPE '!'
                   OR lower(o.recipientName) LIKE :q ESCAPE '!'
                   OR replace(o.recipientPhone, '-', '') LIKE :digits)
            """)
    Page<ShopOrder> search(@Param("q") String q, @Param("digits") String digits,
                           @Param("statuses") Collection<OrderStatus> statuses, Pageable pageable);
}
