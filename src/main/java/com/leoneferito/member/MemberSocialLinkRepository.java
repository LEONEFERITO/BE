package com.leoneferito.member;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface MemberSocialLinkRepository extends JpaRepository<MemberSocialLink, UUID> {
    Optional<MemberSocialLink> findByProviderAndProviderUserId(MemberProvider provider, String providerUserId);
    boolean existsByMemberIdAndProvider(UUID memberId, MemberProvider provider);
    /** 이 회원에 연결한 간편 로그인 — 마이페이지 「내 정보」에 보인다 */
    List<MemberSocialLink> findByMemberIdOrderByLinkedAtAsc(UUID memberId);
    @Modifying
    @Query("DELETE FROM MemberSocialLink l WHERE l.memberId = :memberId")
    int deleteByMemberId(UUID memberId);
}