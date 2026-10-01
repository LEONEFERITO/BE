package com.leoneferito.auth;

import com.leoneferito.mail.Mailer;
import com.leoneferito.member.Member;
import com.leoneferito.member.MemberRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 비밀번호 찾기 (재설정 링크 메일).
 *
 * <p>요청에는 가입 여부와 상관없이 항상 같은 답을 한다 — 메일이 가느냐로만 갈린다.
 * 링크는 비밀번호와 같다: 원문은 메일에만, DB 에는 해시만. 30분, 한 번.
 * 토큰은 주소의 # 뒤(fragment)에 싣는다 — 서버 로그에도 Referer 에도 안 실린다.
 */
@Service
public class PasswordResetService {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetService.class);

    static final Duration TTL = Duration.ofMinutes(30);

    /** 한 계정에 한 시간 동안 보내는 최대 메일 수. */
    static final int MAX_REQUESTS_PER_HOUR = 5;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final MemberRepository members;
    private final PasswordResetTokenRepository tokens;
    private final PasswordPolicy passwordPolicy;
    private final PasswordEncoder passwordEncoder;
    private final SessionTerminator sessionTerminator;
    private final Mailer mailer;
    private final String frontBaseUrl;

    public PasswordResetService(MemberRepository members, PasswordResetTokenRepository tokens,
                                PasswordPolicy passwordPolicy, PasswordEncoder passwordEncoder,
                                SessionTerminator sessionTerminator, Mailer mailer,
                                @Value("${app.front.base-url}") String frontBaseUrl) {
        this.members = members;
        this.tokens = tokens;
        this.passwordPolicy = passwordPolicy;
        this.passwordEncoder = passwordEncoder;
        this.sessionTerminator = sessionTerminator;
        this.mailer = mailer;
        this.frontBaseUrl = frontBaseUrl;
    }

    /** 재설정 메일 요청. 결과를 돌려주지 않는다 — 부르는 쪽도 가입 여부를 몰라야 한다. */
    @Transactional
    public void request(String rawEmail) {
        String email = Member.normalizeEmail(rawEmail);
        Instant now = Instant.now();

        // 정지·탈퇴 계정에는 보내지 않는다. 정지를 비밀번호 재설정으로 우회하면 안 된다.
        Optional<Member> found = members.findByEmail(email).filter(Member::isActive);
        if (found.isEmpty()) {
            log.info("재설정 요청 reason=NO_ACCOUNT");
            return;
        }
        Member member = found.get();

        if (tokens.countByMemberIdAndCreatedAtAfter(member.getId(), now.minus(Duration.ofHours(1)))
                >= MAX_REQUESTS_PER_HOUR) {
            log.info("재설정 요청 제한 memberId={}", member.getId());
            return;
        }

        if (!member.hasPassword()) {
            /*
             * 간편가입 계정은 바꿀 비밀번호가 없다. 어떻게 들어오는지 알려 준다.
             * 안내 메일도 횟수 제한에 넣으려고 링크 행을 하나 남긴다. 그 링크는 쓸 수 없다 —
             * reset() 이 비밀번호 없는 계정을 거절하고, 원문은 어디에도 보내지 않는다.
             */
            issueLink(member);
            mailer.send(member.getEmail(), "[LEONE FERITO] 로그인 방법 안내", socialGuide(member));
            log.info("재설정 요청 — 간편가입 계정 안내 memberId={}", member.getId());
            return;
        }

        String link = issueLink(member);
        mailer.send(member.getEmail(), "[LEONE FERITO] 비밀번호 재설정 안내", resetGuide(member, link));
        log.info("재설정 링크 발송 memberId={}", member.getId());
    }

    /**
     * 새 재설정 링크를 만든다. 이 사람의 옛 링크는 전부 무효가 된다.
     * 관리자 생성 명령도 이걸 쓴다 — 명령이 비밀번호를 정하지 않고 본인이 링크로 정한다.
     */
    @Transactional
    public String issueLink(Member member) {
        Instant now = Instant.now();
        tokens.invalidateAll(member.getId(), now);

        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);

        tokens.save(new PasswordResetToken(UUID.randomUUID(), member.getId(), hash(raw), now.plus(TTL)));
        return frontBaseUrl + "/reset/#token=" + raw;
    }

    /**
     * 링크로 새 비밀번호를 정한다.
     * 성공하면: 비밀번호 교체 · 잠금 해제 · 남은 링크 무효 · 모든 세션 종료.
     */
    @Transactional
    public void reset(String rawToken, String newPassword) {
        Instant now = Instant.now();
        PasswordResetToken token = tokens.findByTokenHash(hash(rawToken))
                .filter(t -> t.isUsable(now))
                .orElseThrow(InvalidTokenException::new);

        Member member = members.findById(token.getMemberId())
                .filter(Member::isActive)
                .filter(Member::hasPassword)
                .orElseThrow(InvalidTokenException::new);

        passwordPolicy.validate(newPassword, member.getEmail());

        member.changePassword(passwordEncoder.encode(newPassword));
        member.unlock();
        token.markUsed(now);
        tokens.invalidateAll(member.getId(), now);
        sessionTerminator.terminateAll(member.getEmail());

        log.info("비밀번호 재설정 완료 memberId={}", member.getId());
    }

    static String hash(String rawToken) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 없음", e); // 모든 JVM 에 있다
        }
    }

    private String resetGuide(Member member, String link) {
        return """
                %s님, 비밀번호 재설정을 요청하셨습니다.

                아래 링크에서 새 비밀번호를 정해 주세요.
                링크는 %d분 동안, 한 번만 쓸 수 있습니다.

                %s

                요청하지 않으셨다면 이 메일을 무시하셔도 됩니다. 비밀번호는 바뀌지 않습니다.
                """.formatted(member.getName(), TTL.toMinutes(), link);
    }

    private String socialGuide(Member member) {
        String provider = switch (member.getProvider()) {
            case KAKAO -> "카카오";
            case NAVER -> "네이버";
            case LOCAL -> "이메일";
        };
        return """
                %s님의 계정은 %s 간편가입 계정이라 비밀번호가 없습니다.

                로그인 화면에서 "%s로 계속하기" 를 눌러 주세요.

                %s/login/
                """.formatted(member.getName(), provider, provider, frontBaseUrl);
    }

    /** 링크가 없거나 · 만료됐거나 · 이미 썼다. 셋을 구분해서 알려주지 않는다. */
    public static class InvalidTokenException extends RuntimeException {
        public InvalidTokenException() {
            super("재설정 링크 무효");
        }
    }
}