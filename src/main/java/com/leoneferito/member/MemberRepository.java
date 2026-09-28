package com.leoneferito.member;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

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
}
