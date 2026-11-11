package com.leoneferito.auth;

import com.leoneferito.auth.AuthenticationFailedException.Reason;
import com.leoneferito.member.Member;
import com.leoneferito.member.MemberRepository;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
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
    private final JdbcTemplate jdbc;
    /** 관리자 2단계 인증(V54). 끄는 곳은 통합 테스트 · 로컬뿐 — 운영에서 끄는 건 OTP 를 잃었을 때의 비상 스위치다(ADMIN_TWO_FACTOR=false) */
    private final boolean adminTwoFactor;

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
                       LoginAttemptRecorder attempts, PasswordPolicy passwordPolicy, JdbcTemplate jdbc,
                       @Value("${app.admin.two-factor:true}") boolean adminTwoFactor) {
        this.members = members;
        this.passwordEncoder = passwordEncoder;
        this.attempts = attempts;
        this.passwordPolicy = passwordPolicy;
        this.jdbc = jdbc;
        this.adminTwoFactor = adminTwoFactor;
        if (!adminTwoFactor) {
            log.warn("관리자 2단계 인증이 꺼져 있다(app.admin.two-factor=false) — 운영이면 OTP 를 되찾은 뒤 바로 다시 켠다");
        }
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
        return authenticate(members.findByEmail(email), rawPassword);
    }

    /**
     * 관리자 로그인 (/admin/login). 아이디(login_id) 또는 이메일로 받는다.
     *
     * <p>비밀번호가 맞아도 관리자가 아니면 <b>같은 "아이디 또는 비밀번호가 맞지 않습니다"</b> 로 답하고
     * 세션을 만들지 않는다. "관리자가 아닙니다" 라고 답하면 그 계정의 비밀번호가 맞았다는 게 샌다.
     * 잠금 · 실패 기록 · 정지 규칙은 일반 로그인과 같다.
     */
    public Member adminLogin(String rawIdentifier, String rawPassword) {
        String id = rawIdentifier == null ? "" : rawIdentifier.trim().toLowerCase(java.util.Locale.ROOT);
        Optional<Member> found = id.contains("@") ? members.findByEmail(Member.normalizeEmail(id))
                : members.findByLoginId(id);
        Member member = authenticate(found, rawPassword);
        if (!member.getRole().isAdmin()) {
            log.info("관리자 로그인 거절 — 관리자 아님 memberId={}", member.getId());
            throw new AuthenticationFailedException(Reason.INVALID_CREDENTIALS);
        }
        return member;
    }

    // ── 관리자 2단계 인증 (V54) ─────────────────────────────

    /** 이 사람이 세션에서 관리자 권한을 받으려면 OTP 를 거쳐야 하는가 — 손님용 로그인에서는 손님 권한만 준다 */
    public boolean needsAdminOtp(Member member) {
        return adminTwoFactor && member.getRole().isAdmin();
    }

    public boolean isAdminTwoFactor() {
        return adminTwoFactor;
    }

    /** 2단계 대기 표 — 5분, 5번까지 */
    static final Duration CHALLENGE_TTL = Duration.ofMinutes(5);
    static final int CHALLENGE_TRIES = 5;

    /**
     * 비밀번호를 통과한 관리자에게 6자리를 물을 표를 만든다. 아직 OTP 가 없으면 새 비밀 키를 만들어 함께 돌려준다(QR 등록).
     * 같은 사람의 이전 표는 지운다 — 표는 한 번에 하나다.
     */
    public AdminChallenge startAdminChallenge(Member member) {
        UUID id = UUID.randomUUID();
        String pending = member.getOtpSecret() == null ? Totp.newSecret() : null;
        jdbc.update("DELETE FROM admin_login_challenge WHERE member_id = ?", member.getId());
        jdbc.update("INSERT INTO admin_login_challenge (id, member_id, pending_secret, expires_at) VALUES (?, ?, ?, ?)",
                id, member.getId(), pending, Timestamp.from(Instant.now().plus(CHALLENGE_TTL)));
        String account = member.getLoginId() != null ? member.getLoginId() : member.getEmail();
        log.info("관리자 로그인 2단계 시작 memberId={} setup={}", member.getId(), pending != null);
        return new AdminChallenge(id, pending, pending == null ? null : Totp.uri(pending, account));
    }

    /** setupSecret · otpauthUri 는 처음 등록일 때만 있다 */
    public record AdminChallenge(UUID challengeId, String setupSecret, String otpauthUri) {
    }

    /**
     * 6자리 확인. 맞으면 그 관리자(세션은 부르는 쪽이 만든다). 처음 등록이면 이때 비밀 키를 저장한다.
     * 틀리면 표의 횟수를 올리고 401 — 5번 틀리거나 5분이 지나면 표가 사라져 비밀번호부터 다시 한다.
     * 트랜잭션을 걸지 않는다 — 걸면 예외와 함께 «틀린 횟수» 도 되돌아가 무제한으로 찍을 수 있다(LoginAttemptRecorder 와 같은 이유).
     */
    public Member verifyAdminOtp(UUID challengeId, String code) {
        Instant now = Instant.now();
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT member_id, pending_secret, attempts FROM admin_login_challenge WHERE id = ? AND expires_at > ?",
                challengeId, Timestamp.from(now));
        if (rows.isEmpty() || ((Number) rows.getFirst().get("attempts")).intValue() >= CHALLENGE_TRIES) {
            jdbc.update("DELETE FROM admin_login_challenge WHERE id = ?", challengeId);
            throw new AuthenticationFailedException(Reason.OTP_EXPIRED);
        }
        UUID memberId = (UUID) rows.getFirst().get("member_id");
        String pending = (String) rows.getFirst().get("pending_secret");
        Member member = members.findById(memberId)
                .filter(m -> m.isActive() && m.getRole().isAdmin())
                .orElseThrow(() -> new AuthenticationFailedException(Reason.OTP_EXPIRED));
        String secret = pending != null ? pending : member.getOtpSecret();
        long step = secret == null ? -1 : Totp.match(secret, code, now, member.getOtpLastStep());
        if (step < 0) {
            jdbc.update("UPDATE admin_login_challenge SET attempts = attempts + 1 WHERE id = ?", challengeId);
            log.info("관리자 로그인 2단계 실패 memberId={}", memberId);
            throw new AuthenticationFailedException(Reason.INVALID_OTP);
        }
        jdbc.update("DELETE FROM admin_login_challenge WHERE id = ?", challengeId);
        if (pending != null) {
            member.enableOtp(pending, now, step);
            log.info("관리자 OTP 등록 memberId={}", memberId);
        } else {
            member.useOtpStep(step);
        }
        members.save(member);
        return member;
    }

    /**
     * TOTP (RFC 6238) — 30초마다 바뀌는 6자리. Google OTP · Microsoft Authenticator 등과 같은 기본값(HMAC-SHA1).
     * 앱과 서버 시계가 조금 어긋나도 되게 앞뒤 한 칸(±30초)까지 받는다. 이미 받은 칸과 그 앞은 다시 받지 않는다.
     */
    public static final class Totp {

        static final long PERIOD = 30;
        private static final SecureRandom RANDOM = new SecureRandom();
        private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

        private Totp() {
        }

        /** 160비트 비밀 키 — Base32 32자 */
        public static String newSecret() {
            byte[] key = new byte[20];
            RANDOM.nextBytes(key);
            return base32(key);
        }

        public static long stepAt(Instant t) {
            return t.getEpochSecond() / PERIOD;
        }

        /** 그 칸의 6자리 */
        public static String code(String secret, long step) {
            try {
                Mac mac = Mac.getInstance("HmacSHA1");
                mac.init(new SecretKeySpec(unbase32(secret), "HmacSHA1"));
                byte[] h = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array());
                int o = h[h.length - 1] & 0x0f;
                int bin = ((h[o] & 0x7f) << 24) | ((h[o + 1] & 0xff) << 16) | ((h[o + 2] & 0xff) << 8) | (h[o + 3] & 0xff);
                return String.format("%06d", bin % 1_000_000);
            } catch (NoSuchAlgorithmException | InvalidKeyException e) {
                throw new IllegalStateException(e);
            }
        }

        /** 맞으면 그 칸 번호, 아니면 -1. lastStep 이하(이미 쓴 칸)는 받지 않는다 */
        static long match(String secret, String code, Instant now, Long lastStep) {
            if (code == null || !code.matches("\\d{6}")) {
                return -1;
            }
            long current = stepAt(now);
            for (long s = current - 1; s <= current + 1; s++) {
                if (lastStep != null && s <= lastStep) {
                    continue;
                }
                if (MessageDigest.isEqual(code(secret, s).getBytes(StandardCharsets.US_ASCII), code.getBytes(StandardCharsets.US_ASCII))) {
                    return s;
                }
            }
            return -1;
        }

        /** 앱이 QR 로 읽는 주소 */
        public static String uri(String secret, String account) {
            String issuer = "LEONE FERITO";
            String label = java.net.URLEncoder.encode(issuer + ":" + account, StandardCharsets.UTF_8).replace("+", "%20");
            return "otpauth://totp/" + label + "?secret=" + secret + "&issuer="
                    + java.net.URLEncoder.encode(issuer, StandardCharsets.UTF_8).replace("+", "%20") + "&digits=6&period=30";
        }

        static String base32(byte[] data) {
            StringBuilder sb = new StringBuilder();
            int buffer = 0, bits = 0;
            for (byte b : data) {
                buffer = (buffer << 8) | (b & 0xff);
                bits += 8;
                while (bits >= 5) {
                    sb.append(ALPHABET.charAt((buffer >> (bits - 5)) & 31));
                    bits -= 5;
                }
            }
            if (bits > 0) {
                sb.append(ALPHABET.charAt((buffer << (5 - bits)) & 31));
            }
            return sb.toString();
        }

        static byte[] unbase32(String text) {
            String s = text.replace("=", "").toUpperCase(java.util.Locale.ROOT);
            ByteBuffer out = ByteBuffer.allocate(s.length() * 5 / 8);
            int buffer = 0, bits = 0;
            for (char c : s.toCharArray()) {
                int v = ALPHABET.indexOf(c);
                if (v < 0) {
                    throw new IllegalArgumentException("Base32 가 아닌 글자");
                }
                buffer = (buffer << 5) | v;
                bits += 5;
                if (bits >= 8) {
                    out.put((byte) (buffer >> (bits - 8)));
                    bits -= 8;
                }
            }
            return out.array();
        }
    }

    /** 관리자가 비밀번호를 바꿔야 하는 상태인가 (임시 비밀번호). 화면이 변경 화면으로 보낼지 정한다. */
    public boolean mustChangePassword(java.util.UUID memberId) {
        return members.findById(memberId).map(Member::isMustChangePassword).orElse(false);
    }

    private Member authenticate(Optional<Member> candidate, String rawPassword) {
        Instant now = Instant.now();

        Optional<Member> found = candidate
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

        /*
         * 잠긴 동안에는 비밀번호가 맞든 틀리든 같은 답을 한다(2026-11-04 오픈 전 보안 점검).
         * 맞을 때만 "잠겼다" 고 답하면, 일부러 잠가 놓고 잠긴 15분 동안 횟수 제한 없이 비밀번호를 맞혀 볼 수 있다.
         * 손님에게는 실패 문구에 "여러 번 틀리면 잠시 잠긴다" 를 함께 적어 둔다(GlobalExceptionHandler).
         */
        if (member.isLocked(now)) {
            log.info("잠긴 계정 접근 memberId={} passwordMatched={}", member.getId(), passwordMatches);
            throw new AuthenticationFailedException(Reason.INVALID_CREDENTIALS);
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
