package com.leoneferito.order;

import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReturnRequestRepository extends JpaRepository<ReturnRequest, UUID> {

    @Query("SELECT r FROM ReturnRequest r WHERE r.order.id = :orderId ORDER BY r.createdAt DESC")
    List<ReturnRequest> findForOrder(@Param("orderId") UUID orderId);

    boolean existsByMemberIdAndStatusIn(UUID memberId, Collection<ReturnStatus> statuses);

    /** 처리용 — 행을 잠그고 읽는다. 환불 완료가 두 번 눌려도 토스 취소가 두 번 나가지 않게. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM ReturnRequest r WHERE r.id = :id")
    Optional<ReturnRequest> findByIdForUpdate(@Param("id") UUID id);

    Page<ReturnRequest> findByStatusIn(Collection<ReturnStatus> statuses, Pageable pageable);
}
