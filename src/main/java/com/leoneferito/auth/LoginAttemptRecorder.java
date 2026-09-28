package com.leoneferito.auth;

import com.leoneferito.member.MemberRepository;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 로그인 시도 결과를 기록한다.
 *
 * <p><b>왜 별도 빈이고 왜 REQUIRES_NEW 인가.</b>
 *
 * <p>처음에는 {@code AuthService.login()} 안에서 실패 횟수를 올리고 곧바로 예외를 던졌다.
 * 그런데 그 메서드가 {@code @Transactional} 이었고, 던진 예외가 {@link RuntimeException} 이라
 * <b>트랜잭션이 통째로 롤백됐다.</b> 올린 횟수가 같이 사라져서 계정 잠금이 한 번도 걸리지
 * 않았다 — 무차별 대입 방어가 이름만 있고 실제로는 없는 상태였다.
 *
 * <p>그래서 기록을 <b>독립된 트랜잭션</b>으로 뺀다. 바깥이 예외로 롤백돼도 여기서 커밋한
 * 횟수는 남는다. 같은 클래스 안의 메서드를 호출하면 프록시를 타지 않아 전파 설정이
 * 무시되므로, 별도 빈이어야 한다.
 *
 * <p>테스트가 없었으면 이 결함은 누군가 실제로 공격당하기 전까지 드러나지 않았을 것이다.
 */
@Component
public class LoginAttemptRecorder {

    private final MemberRepository members;

    public LoginAttemptRecorder(MemberRepository members) {
        this.members = members;
    }

    /** 실패 한 번. 임계치를 넘으면 회원 엔티티가 스스로 잠근다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(UUID memberId, Instant now) {
        members.findById(memberId).ifPresent(m -> m.recordFailedLogin(now));
    }

    /** 성공. 실패 누적을 0 으로 되돌리고 마지막 로그인 시각을 남긴다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordSuccess(UUID memberId, Instant now) {
        members.findById(memberId).ifPresent(m -> m.recordSuccessfulLogin(now));
    }
}
