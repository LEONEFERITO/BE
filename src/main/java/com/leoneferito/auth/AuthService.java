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

    /**
     * BCrypt 는 <b>72바이트를 넘는 입력을 잘라낸다.</b>
     *
     * <p>조용히 자르면 73번째 글자부터는 검증에 쓰이지 않는다. 긴 암호를 쓴 사람이
     * 오히려 앞 72바이트만으로 인증되는 셈이라, 본인은 더 안전하다고 믿는데 아니다.
     * 그래서 자르지 않고 <b>거부한다.</b> 한글은 UTF-8 로 3바이트라 24자쯤이 한계다.
     */
    private static final int MAX_PASSWORD_BYTES = 72;

    /** 너무 짧은 비밀번호를 막는다. 구성 규칙(대문자·특수문자)은 두지 않는다 — 아래 주석 참고. */
    private static final int MIN_PASSWORD_LENGTH = 10;

    private final MemberRepository members;
    private final PasswordEncoder passwordEncoder;
    private final LoginAttemptRecorder attempts;

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
                       LoginAttemptRecorder attempts) {
        this.members = members;
        this.passwordEncoder = passwordEncoder;
        this.attempts = attempts;
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
        validatePassword(rawPassword, email);

        if (members.existsByEmail(email)) {
            throw new EmailAlreadyRegisteredException(email);
        }

        Member member = new Member(UUID.randomUUID(), email,
                passwordEncoder.encode(rawPassword), name, phone);
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
                .filter(Member::isActive); // 탈퇴 계정은 없는 것과 같다

        if (found.isEmpty()) {
            // 타이밍을 맞추기 위한 헛수고. 결과는 버린다.
            passwordEncoder.matches(rawPassword, dummyHash);
            log.info("로그인 실패 reason=NO_ACCOUNT");
            throw new AuthenticationFailedException(Reason.INVALID_CREDENTIALS);
        }

        Member member = found.get();
        boolean passwordMatches = passwordEncoder.matches(rawPassword, member.getPasswordHash());

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

        attempts.recordSuccess(member.getId(), now);
        log.info("로그인 성공 memberId={}", member.getId());
        return member;
    }

    /**
     * 비밀번호 규칙.
     *
     * <p>대문자·숫자·특수문자 조합을 강제하지 않는다. 그런 규칙은 사람을 {@code Password1!}
     * 같은 예측 가능한 형태로 몰아넣고, 기억하지 못해 메모지에 적게 만든다.
     * <b>길이</b>가 훨씬 효과적이다 (NIST SP 800-63B 도 같은 방향이다).
     *
     * <p>대신 이메일을 그대로 쓴 비밀번호는 막는다. 가장 먼저 시도되는 후보다.
     */
    private void validatePassword(String rawPassword, String email) {
        if (rawPassword == null || rawPassword.length() < MIN_PASSWORD_LENGTH) {
            throw new WeakPasswordException("비밀번호는 " + MIN_PASSWORD_LENGTH + "자 이상이어야 합니다.");
        }
        if (rawPassword.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES) {
            throw new WeakPasswordException(
                    "비밀번호가 너무 깁니다. 영문 기준 " + MAX_PASSWORD_BYTES + "자 이내로 입력해 주세요.");
        }

        String localPart = email.substring(0, email.indexOf('@') < 0 ? email.length() : email.indexOf('@'));
        if (!localPart.isBlank() && rawPassword.toLowerCase(java.util.Locale.ROOT).contains(localPart)) {
            throw new WeakPasswordException("비밀번호에 이메일 주소를 포함할 수 없습니다.");
        }
    }
}
