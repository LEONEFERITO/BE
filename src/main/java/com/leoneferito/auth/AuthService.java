package com.leoneferito.auth;

import com.leoneferito.auth.AuthenticationFailedException.Reason;
import com.leoneferito.member.Member;
import com.leoneferito.member.MemberRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 가입과 로그인.
 *
 * <p>여기가 <b>평문 비밀번호가 존재하는 유일한 지점</b>이다. 들어오자마자 해시로 바꾸고
 * 밖으로 내보내지 않는다. 로그에도 남기지 않는다 — 로그는 보관 기간이 길고 열람 범위가 넓다.
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final MemberRepository members;
    private final PasswordEncoder passwordEncoder;
    private final LoginAttemptRecorder attempts;
    private final PasswordPolicy passwordPolicy;

    /**
     * 존재하지 않는 계정으로 로그인을 시도했을 때 <b>대조할 가짜 해시</b>.
     *
     * <p>없는 이메일이면 즉시 실패를 돌려주고 싶지만, 그러면 응답 시간이 눈에 띄게 짧아진다.
     * BCrypt 검증은 일부러 느리기 때문에(수십~수백 ms) 그 차이만으로
     * "이 이메일은 가입되어 있다" 를 알아낼 수 있다(타이밍 기반 계정 열거).
     * 그래서 없는 계정에도 같은 비용의 검증을 한 번 돌린다.
     */
    private final String dummyHash;

    public AuthService(MemberRepository members, PasswordEncoder passwordEncoder,
                       LoginAttemptRecorder attempts, PasswordPolicy passwordPolicy) {
        this.members = members;
        this.passwordEncoder = passwordEncoder;
        this.attempts = attempts;
        this.passwordPolicy = passwordPolicy;
        this.dummyHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    /**
     * 회원가입.
     *
     * <p><b>알려진 한계:</b> 이미 가입된 이메일이면 409 를 돌려준다. 이것만으로도
     * "그 이메일이 가입되어 있다" 가 새어, 로그인 쪽에서 막아둔 계정 열거가 여기서 뚫린다.
     * 제대로 막으려면 가입 요청을 항상 성공으로 답하고 <b>메일로 분기</b>해야 하는데
     * (이미 가입된 주소에는 "이미 계정이 있습니다" 메일), 메일 발송이 아직 없다.
     * 메일이 붙는 시점에 이 동작을 바꾼다.
     */
    @Transactional
    public UUID signup(String rawEmail, String rawPassword, String name, String phone) {
        String email = Member.normalizeEmail(rawEmail);
        passwordPolicy.validate(rawPassword, email);

        if (members.existsByEmail(email)) {
            throw new EmailAlreadyRegisteredException(email);
        }

        Member member = new Member(UUID.randomUUID(), email,
                passwordEncoder.encode(rawPassword), name, phone);
        // 약관 동의 · 만 14세 확인은 가입 요청 검증(AuthRequests.Signup)이 받는다. 여기서는 기록만 한다.
        member.agreeTerms("FORM", Instant.now());
        members.save(member);

        // 이메일은 개인정보다. 로그에는 식별자만 남긴다.
        log.info("회원 가입 memberId={}", member.getId());
        return member.getId();
    }

    /**
     * 로그인.
     *
     * <p>순서가 중요하다. <b>잠금 여부를 먼저 보고 바로 거절하지 않는다.</b>
     * 그러면 비밀번호를 모르는 사람도 "이 계정은 잠겨 있다" = "이 계정은 존재한다" 를 알게 된다.
     * 비밀번호를 먼저 대조하고, 맞았을 때만 잠금을 알려준다.
     *
     * <p>잠겨 있는 동안에는 실패 횟수를 <b>더 올리지 않는다.</b> 올리면 공격자가 계속 두드려서
     * 잠금이 영원히 연장되고, 정작 주인이 15분 뒤에도 못 들어온다.
     */
    /*
     * 트랜잭션을 걸지 않는다. 걸면 아래에서 던지는 예외에 실패 횟수 증가까지 함께
     * 롤백되어 계정 잠금이 영원히 동작하지 않는다 (LoginAttemptRecorder 주석 참고).
     * 기록은 독립 트랜잭션을 가진 recorder 가 맡는다.
     */
    public Member login(String rawEmail, String rawPassword) {
        String email = Member.normalizeEmail(rawEmail);
        Instant now = Instant.now();

        Optional<Member> found = members.findByEmail(email)
                .filter(m -> !m.isWithdrawn()); // 탈퇴 계정은 없는 것과 같다

        if (found.isEmpty()) {
            // 타이밍을 맞추기 위한 헛수고. 결과는 버린다.
            passwordEncoder.matches(rawPassword, dummyHash);
            log.info("로그인 실패 reason=NO_ACCOUNT");
            throw new AuthenticationFailedException(Reason.INVALID_CREDENTIALS);
        }

        Member member = found.get();

        /*
         * 간편가입 회원은 비밀번호가 없다. 그래도 가짜 해시와 한 번 대조해서 시간을 맞춘다 —
         * 즉시 거절하면 "이 이메일은 간편가입 계정이다" 가 응답 시간으로 샌다.
         * 결과는 항상 실패다. 그 사람은 카카오/네이버 버튼으로 들어와야 한다.
         */
        boolean passwordMatches;
        if (member.hasPassword()) {
            passwordMatches = passwordEncoder.matches(rawPassword, member.getPasswordHash());
        } else {
            passwordEncoder.matches(rawPassword, dummyHash);
            passwordMatches = false;
        }

        if (member.isLocked(now)) {
            log.info("잠긴 계정 접근 memberId={} passwordMatched={}", member.getId(), passwordMatches);
            throw new AuthenticationFailedException(
                    passwordMatches ? Reason.ACCOUNT_LOCKED : Reason.INVALID_CREDENTIALS);
        }

        if (!passwordMatches) {
            attempts.recordFailure(member.getId(), now);
            log.info("로그인 실패 memberId={}", member.getId());
            throw new AuthenticationFailedException(Reason.INVALID_CREDENTIALS);
        }

        /*
         * 이용 정지는 잠금과 같은 규칙이다 — 비밀번호가 맞았을 때만 알린다.
         * 틀린 비밀번호에 "정지된 계정" 이라고 답하면 그 이메일이 가입되어 있다는 게 샌다.
         */
        if (member.isSuspended()) {
            log.info("정지된 계정 로그인 시도 memberId={}", member.getId());
            throw new AuthenticationFailedException(Reason.ACCOUNT_SUSPENDED);
        }

        attempts.recordSuccess(member.getId(), now);
        log.info("로그인 성공 memberId={}", member.getId());
        return member;
    }
}
