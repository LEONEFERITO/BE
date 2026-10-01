package com.leoneferito.member;

import com.leoneferito.auth.LoginAttemptRecorder;
import com.leoneferito.auth.PasswordPolicy;
import com.leoneferito.auth.SessionTerminator;
import com.leoneferito.common.error.ResourceNotFoundException;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 회원 본인이 하는 일 — 정보 수정 · 비밀번호 변경 · 탈퇴.
 *
 * <p>비밀번호 변경과 탈퇴는 현재 비밀번호를 다시 묻는다. 틀리면 로그인 실패와 똑같이 센다 —
 * 안 세면 이 화면이 비밀번호 대입 창구가 된다.
 */
@Service
public class MemberAccountService {

    private static final Logger log = LoggerFactory.getLogger(MemberAccountService.class);

    private final MemberRepository members;
    private final PasswordEncoder passwordEncoder;
    private final PasswordPolicy passwordPolicy;
    private final SessionTerminator sessionTerminator;
    private final LoginAttemptRecorder attempts;

    public MemberAccountService(MemberRepository members, PasswordEncoder passwordEncoder,
                                PasswordPolicy passwordPolicy, SessionTerminator sessionTerminator,
                                LoginAttemptRecorder attempts) {
        this.members = members;
        this.passwordEncoder = passwordEncoder;
        this.passwordPolicy = passwordPolicy;
        this.sessionTerminator = sessionTerminator;
        this.attempts = attempts;
    }

    @Transactional(readOnly = true)
    public Member get(UUID memberId) {
        return members.findById(memberId)
                .filter(Member::isActive)
                .orElseThrow(() -> new ResourceNotFoundException("회원 없음 id=" + memberId));
    }

    /** 이름 · 전화번호. 이메일은 로그인 아이디라 여기서 바꾸지 않는다. */
    @Transactional
    public Member updateProfile(UUID memberId, String name, String phone) {
        Member member = get(memberId);
        member.setName(name.trim());
        member.setPhone(phone == null || phone.isBlank() ? null : phone.trim());
        return member;
    }

    /** 비밀번호 변경. 지금 쓰는 세션은 남기고 다른 기기의 세션은 끊는다. */
    @Transactional
    public void changePassword(UUID memberId, String currentPassword, String newPassword,
                               String keepSessionId) {
        Member member = get(memberId);
        if (!member.hasPassword()) {
            throw new NoPasswordException();
        }
        verifyPassword(member, currentPassword);
        passwordPolicy.validate(newPassword, member.getEmail());

        member.changePassword(passwordEncoder.encode(newPassword));
        sessionTerminator.terminateOthers(member.getEmail(), keepSessionId);
        log.info("비밀번호 변경 memberId={}", member.getId());
    }

    /**
     * 탈퇴. 개인정보를 지우고(Member.withdraw) 모든 세션을 끊는다.
     * 간편가입 회원은 비밀번호가 없어서 다시 묻지 못한다 — 화면이 한 번 더 확인한다.
     * 관리자는 스스로 탈퇴하지 못한다. 먼저 권한을 내려야 한다.
     *
     * <p>TODO(주문 도메인) 진행 중인 주문·교환이 있으면 탈퇴를 막는다.
     */
    @Transactional
    public void withdraw(UUID memberId, String password) {
        Member member = get(memberId);
        if (member.getRole().isAdmin()) {
            throw new AdminWithdrawalException();
        }
        if (member.hasPassword()) {
            verifyPassword(member, password);
        }

        // 세션은 이메일로 찾는다. 익명화하면 이메일이 바뀌므로 그 전에 끊는다.
        sessionTerminator.terminateAll(member.getEmail());
        member.withdraw(Instant.now());
        log.info("회원 탈퇴 memberId={}", member.getId());
    }

    private void verifyPassword(Member member, String rawPassword) {
        Instant now = Instant.now();
        if (member.isLocked(now)) {
            throw new WrongPasswordException(true);
        }
        if (rawPassword == null || !passwordEncoder.matches(rawPassword, member.getPasswordHash())) {
            // 독립 트랜잭션이라 아래 예외로 이 트랜잭션이 롤백돼도 횟수는 남는다.
            attempts.recordFailure(member.getId(), now);
            throw new WrongPasswordException(false);
        }
    }

    /** 현재 비밀번호가 틀렸다. locked 면 실패 누적으로 잠겨서 확인조차 하지 않았다. */
    public static class WrongPasswordException extends RuntimeException {
        private final boolean locked;

        public WrongPasswordException(boolean locked) {
            super(locked ? "잠김" : "비밀번호 불일치");
            this.locked = locked;
        }

        public boolean isLocked() {
            return locked;
        }
    }

    /** 간편가입 계정이라 바꿀 비밀번호가 없다. */
    public static class NoPasswordException extends RuntimeException {
        public NoPasswordException() {
            super("비밀번호 없는 계정");
        }
    }

    public static class AdminWithdrawalException extends RuntimeException {
        public AdminWithdrawalException() {
            super("관리자 탈퇴 시도");
        }
    }
}