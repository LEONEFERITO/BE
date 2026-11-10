package com.leoneferito.order;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CartItemRepository extends JpaRepository<CartItem, UUID> {

    List<CartItem> findByMemberIdOrderByCreatedAtAsc(UUID memberId);

    /** addonOf 가 null 이면 보통 줄(IS NULL)을 찾는다 — 같은 셔츠라도 «수트와 함께» 담은 줄과 따로 담은 줄은 다르다(V52) */
    Optional<CartItem> findByMemberIdAndProductIdAndSizeAndColorAndAddonOf(UUID memberId, UUID productId, String size,
                                                                          String color, UUID addonOf);

    /** 다른 사람의 장바구니 줄을 고치지 못하게 — 찾을 때부터 주인을 조건에 넣는다. */
    Optional<CartItem> findByIdAndMemberId(UUID id, UUID memberId);

    void deleteByMemberIdAndIdIn(UUID memberId, Collection<UUID> ids);

    /** 탈퇴 — 그 회원의 장바구니를 모두 지운다 */
    void deleteByMemberId(UUID memberId);
}
