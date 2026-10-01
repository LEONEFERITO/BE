package com.leoneferito.order;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CartItemRepository extends JpaRepository<CartItem, UUID> {

    List<CartItem> findByMemberIdOrderByCreatedAtAsc(UUID memberId);

    Optional<CartItem> findByMemberIdAndProductIdAndSize(UUID memberId, UUID productId, String size);

    /** 다른 사람의 장바구니 줄을 고치지 못하게 — 찾을 때부터 주인을 조건에 넣는다. */
    Optional<CartItem> findByIdAndMemberId(UUID id, UUID memberId);

    void deleteByMemberIdAndIdIn(UUID memberId, Collection<UUID> ids);
}
