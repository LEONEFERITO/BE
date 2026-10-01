package com.leoneferito.auth;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, UUID> {

    Optional<PasswordResetToken> findByTokenHash(String tokenHash);

    /** 최근 요청 횟수. 한 사람의 메일함을 재설정 메일로 채우는 걸 막는다. */
    long countByMemberIdAndCreatedAtAfter(UUID memberId, Instant since);

    /** 아직 안 쓴 링크를 전부 무효로. 새 링크를 만들거나 비밀번호가 바뀌면 옛 링크는 죽어야 한다. */
    @Modifying
    @Query("UPDATE PasswordResetToken t SET t.usedAt = :now WHERE t.memberId = :memberId AND t.usedAt IS NULL")
    int invalidateAll(@Param("memberId") UUID memberId, @Param("now") Instant now);
}