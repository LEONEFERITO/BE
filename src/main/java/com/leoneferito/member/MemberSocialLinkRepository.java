package com.leoneferito.member;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface MemberSocialLinkRepository extends JpaRepository<MemberSocialLink, UUID> {
    Optional<MemberSocialLink> findByProviderAndProviderUserId(MemberProvider provider, String providerUserId);
    boolean existsByMemberIdAndProvider(UUID memberId, MemberProvider provider);
    @Modifying
    @Query("DELETE FROM MemberSocialLink l WHERE l.memberId = :memberId")
    int deleteByMemberId(UUID memberId);
}