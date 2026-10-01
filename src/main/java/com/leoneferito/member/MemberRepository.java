package com.leoneferito.member;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 회원 조회.
 *
 * <p>이메일로 찾을 때는 <b>반드시</b> {@link Member#normalizeEmail}(소문자) 를 거친 값을 넘긴다.
 * 저장은 소문자로 되어 있으므로, 사용자가 입력한 원문을 그대로 넘기면
 * {@code Kim@x.com} 으로 가입한 사람이 자기 계정을 못 찾는다.
 */
public interface MemberRepository extends JpaRepository<Member, UUID> {

    Optional<Member> findByEmail(String normalizedEmail);

    boolean existsByEmail(String normalizedEmail);

    Optional<Member> findByLoginId(String loginId);

    /** 간편가입 회원. (provider, providerUserId) 는 유일하다 (V9). */
    Optional<Member> findByProviderAndProviderUserId(MemberProvider provider, String providerUserId);

    /**
     * 관리자 회원 검색. 이메일 · 이름 · 전화번호(하이픈 무시)에서 찾는다.
     *
     * <p>{@code :q} 는 이미 {@code %…%} 로 감싸고 {@code % _ !} 를 이스케이프한 소문자 패턴이다
     * ({@code AdminMemberService}). 빈 검색어면 {@code %} 하나라 전부 걸린다.
     * {@code :digits} 는 검색어의 숫자만 모은 패턴이고, 숫자가 없으면 아무것도 안 걸리는 값이다.
     *
     * <p>"값이 null 이면 조건 무시" 식({@code :q IS NULL OR …})으로 쓰지 않는다 —
     * PostgreSQL 이 null 파라미터의 타입을 못 정해서 오류가 나는 경우가 있다.
     */
    @Query("""
            SELECT m FROM Member m
            WHERE m.status IN :statuses
              AND (m.email LIKE :q ESCAPE '!'
                   OR lower(m.name) LIKE :q ESCAPE '!'
                   OR replace(coalesce(m.phone, ''), '-', '') LIKE :digits)
            """)
    Page<Member> search(@Param("q") String q, @Param("digits") String digits,
                        @Param("statuses") Collection<MemberStatus> statuses, Pageable pageable);
}
