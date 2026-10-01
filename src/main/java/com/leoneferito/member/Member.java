package com.leoneferito.member;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * 회원.
 *
 * <p><b>평문 비밀번호가 이 클래스에 머무르지 않는다.</b> 생성자도 해시를 받는다.
 * 엔티티가 평문을 필드로 들고 있으면 로그·힙덤프·직렬화 어디로든 샐 수 있다.
 * 해싱은 {@code AuthService} 가 경계에서 한 번만 한다.
 *
 * <p>이메일은 항상 소문자다. 사람은 {@code Kim@x.com} 과 {@code kim@x.com} 을 같은 주소로
 * 여기는데 그대로 두면 계정이 둘 생긴다. {@link #normalizeEmail} 로만 값을 만든다.
 */
@Entity
@Table(name = "member")
public class Member {

    /** 이 횟수만큼 연속으로 틀리면 잠근다. */
    public static final int MAX_FAILED_ATTEMPTS = 5;

    /** 잠금 시간(분). 사람은 기다리면 되지만 대입 공격은 속도를 잃는다. */
    public static final int LOCK_MINUTES = 15;

    @Id
    private UUID id;

    @Column(nullable = false, unique = true)
    private String email;

    /** 간편가입 회원은 {@code null}. DB CHECK(V9)가 "LOCAL 이면 반드시 있음" 을 강제한다. */
    @Column(name = "password_hash")
    private String passwordHash;

    /** 어떻게 가입했는가. LOCAL 이면 비밀번호로, 그 외는 그 제공자로 로그인한다. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MemberProvider provider = MemberProvider.LOCAL;

    /** 제공자가 준 사용자 id. LOCAL 이면 {@code null}. (provider, providerUserId) 가 유일하다. */
    @Column(name = "provider_user_id")
    private String providerUserId;

    @Column(nullable = false)
    private String name;

    @Column
    private String phone;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MemberStatus status = MemberStatus.ACTIVE;

    /**
     * 역할. 기본은 손님이다.
     *
     * <p>공개 setter 를 두지 않는다. 바꾸는 길은 {@link #changeRole} 하나이고 <b>이 패키지 안에서만</b>
     * 부를 수 있다 — 회원 관리 서비스와 서버 명령. 다른 곳에서 역할을 바꾸는 코드가 생기면
     * 그곳이 곧 권한 상승 경로가 된다.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MemberRole role = MemberRole.MEMBER;

    @Column(name = "failed_login_attempts", nullable = false)
    private short failedLoginAttempts;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private Instant updatedAt;

    /**
     * 현재 이용약관 판. 약관을 개정하면 이 값을 바꾼다 — 그 뒤 가입자는 새 판에 동의한 것으로 남는다.
     * TODO(고객확인) 약관 확정 · 시행일이 정해지면 "2026-MM-DD" 로 바꾼다.
     */
    public static final String CURRENT_TERMS_VERSION = "draft-2026-10-01";

    /** 약관 동의 · 만 14세 확인 (V12). 동의 없이는 가입이 되지 않는다. */
    @Column(name = "terms_agreed_at")
    private Instant termsAgreedAt;

    /** FORM(가입 화면 체크) · SOCIAL_NOTICE(간편가입 버튼 위 고지를 보고 진행). */
    @Column(name = "terms_agreed_via")
    private String termsAgreedVia;

    @Column(name = "terms_version")
    private String termsVersion;

    /** 탈퇴 시각. 탈퇴하면 개인정보가 지워지고 이 값만 남는다 (V10). */
    @Column(name = "withdrawn_at")
    private Instant withdrawnAt;

    /** 관리자 아이디 로그인용 (V15). 서버 명령으로만 붙는다. */
    @Column(name = "login_id")
    private String loginId;

    /** 임시 비밀번호 계정. 바꾸기 전에는 관리자 API 가 막힌다 (AdminPasswordGate). */
    @Column(name = "must_change_password", nullable = false)
    private boolean mustChangePassword;

    protected Member() {
        // JPA
    }

    /** 이메일·비밀번호 가입. */
    public Member(UUID id, String email, String passwordHash, String name, String phone) {
        this.id = Objects.requireNonNull(id, "id");
        this.email = normalizeEmail(email);
        this.passwordHash = Objects.requireNonNull(passwordHash, "passwordHash");
        this.name = Objects.requireNonNull(name, "name");
        this.phone = phone;
    }

    /**
     * 간편가입. 비밀번호가 없고, 대신 제공자와 그쪽 사용자 id 로 식별한다.
     *
     * <p>이메일은 그래도 받는다 — 로그인 아이디이자 주문 안내가 가는 곳이다.
     * 제공자가 이메일을 안 주면 여기까지 오지 않는다 ({@code SocialLoginService}).
     */
    public static Member social(UUID id, MemberProvider provider, String providerUserId,
                                String email, String name) {
        if (provider == MemberProvider.LOCAL) {
            throw new IllegalArgumentException("LOCAL 은 비밀번호 가입이다");
        }
        Member member = new Member();
        member.id = Objects.requireNonNull(id, "id");
        member.provider = provider;
        member.providerUserId = Objects.requireNonNull(providerUserId, "providerUserId");
        member.email = normalizeEmail(email);
        member.name = Objects.requireNonNull(name, "name");
        return member;
    }

    /** 비밀번호로 로그인할 수 있는 계정인가. 간편가입 회원은 아니다. */
    public boolean hasPassword() {
        return passwordHash != null;
    }

    /**
     * 이메일 정규화. 저장·조회 <b>양쪽</b>에서 반드시 거쳐야 한다.
     *
     * <p>{@link Locale#ROOT} 를 명시하는 이유: 터키어 로캘에서 {@code "I".toLowerCase()} 는
     * 점 없는 {@code ı} 가 된다. 서버 로캘에 따라 같은 이메일이 다른 값이 되는 걸 막는다.
     */
    public static String normalizeEmail(String raw) {
        return Objects.requireNonNull(raw, "email").trim().toLowerCase(Locale.ROOT);
    }

    /** 지금 로그인할 수 있는 상태인가. 잠금은 시간이 지나면 저절로 풀린다. */
    public boolean isLocked(Instant now) {
        return lockedUntil != null && lockedUntil.isAfter(now);
    }

    public boolean isActive() {
        return status == MemberStatus.ACTIVE;
    }

    /**
     * 로그인 실패 한 번. 임계치를 넘으면 잠근다.
     *
     * <p>잠근 뒤 카운터를 0 으로 되돌리지 <b>않는다.</b> 되돌리면 잠금이 풀린 직후
     * 다시 {@value #MAX_FAILED_ATTEMPTS} 번을 공짜로 얻는다. 성공했을 때만 0 이 된다.
     */
    public void recordFailedLogin(Instant now) {
        failedLoginAttempts++;
        if (failedLoginAttempts >= MAX_FAILED_ATTEMPTS) {
            lockedUntil = now.plusSeconds(LOCK_MINUTES * 60L);
        }
    }

    public void recordSuccessfulLogin(Instant now) {
        failedLoginAttempts = 0;
        lockedUntil = null;
        lastLoginAt = now;
    }

    /** 비밀번호를 바꾼다. 임시 비밀번호 표시도 함께 지운다 — 본인이 정한 비밀번호가 됐다. */
    public void changePassword(String newPasswordHash) {
        this.passwordHash = Objects.requireNonNull(newPasswordHash, "passwordHash");
        this.mustChangePassword = false;
    }

    /** 임시 비밀번호. 첫 로그인 뒤 바꾸기 전까지 관리자 API 가 막힌다. 서버 명령(로컬)만 쓴다. */
    void setTemporaryPassword(String passwordHash) {
        this.passwordHash = Objects.requireNonNull(passwordHash, "passwordHash");
        this.mustChangePassword = true;
    }

    void assignLoginId(String loginId) {
        this.loginId = loginId;
    }

    public String getLoginId() {
        return loginId;
    }

    public boolean isMustChangePassword() {
        return mustChangePassword;
    }

    /**
     * 탈퇴 = 익명화.
     *
     * <p>행은 남긴다(주문이 참조한다). 대신 사람을 알아볼 수 있는 값을 전부 지운다.
     * 이메일은 NOT NULL · UNIQUE 라 id 로 만든 자리표시 주소를 넣는다 — {@code .invalid} 는
     * 절대 존재할 수 없는 도메인으로 예약되어 있어(RFC 2606) 메일이 새어 나갈 일이 없다.
     *
     * <p>이메일이 비워지므로 같은 주소로 다시 가입할 수 있다. 제공자 id 도 지워서 간편가입도 다시 된다.
     * <b>세션은 여기서 끊지 못한다</b> — 세션은 이메일로 찾으므로, 부르는 쪽이 익명화 <b>전에</b> 끊는다.
     */
    public void withdraw(Instant now) {
        this.status = MemberStatus.WITHDRAWN;
        this.withdrawnAt = Objects.requireNonNull(now, "now");
        this.email = "withdrawn-" + id + "@withdrawn.invalid";
        this.name = "탈퇴 회원";
        this.phone = null;
        this.passwordHash = null;
        this.providerUserId = null;
        this.failedLoginAttempts = 0;
        this.lockedUntil = null;
    }

    /**
     * 약관 동의와 만 14세 확인을 기록한다. 동의를 받았는지 판단하는 건 부르는 쪽이다
     * (가입 요청 검증 · 간편가입 고지). 이 기록은 탈퇴해도 지우지 않는다 — 개인을 알아볼 수 없는 값이다.
     */
    public void agreeTerms(String via, Instant now) {
        this.termsAgreedVia = Objects.requireNonNull(via, "via");
        this.termsAgreedAt = Objects.requireNonNull(now, "now");
        this.termsVersion = CURRENT_TERMS_VERSION;
    }

    public Instant getTermsAgreedAt() {
        return termsAgreedAt;
    }

    public String getTermsAgreedVia() {
        return termsAgreedVia;
    }

    public String getTermsVersion() {
        return termsVersion;
    }

    /** 이용 정지. 탈퇴한 계정은 정지할 것이 없다. */
    public void suspend() {
        if (status != MemberStatus.ACTIVE) {
            throw new IllegalStateException("정지할 수 없는 상태 status=" + status);
        }
        this.status = MemberStatus.SUSPENDED;
    }

    public void reactivate() {
        if (status != MemberStatus.SUSPENDED) {
            throw new IllegalStateException("정지 상태가 아님 status=" + status);
        }
        this.status = MemberStatus.ACTIVE;
    }

    /** 로그인 실패 잠금을 사람이 푼다. 손님이 15분을 못 기다리는 CS 상황용. */
    public void unlock() {
        this.failedLoginAttempts = 0;
        this.lockedUntil = null;
    }

    /** 역할 변경. 규칙(누가 누구를)은 부르는 쪽이 판단한다. 위 필드 주석 참고. */
    void changeRole(MemberRole newRole) {
        this.role = Objects.requireNonNull(newRole, "role");
    }

    public boolean isSuspended() {
        return status == MemberStatus.SUSPENDED;
    }

    public boolean isWithdrawn() {
        return status == MemberStatus.WITHDRAWN;
    }

    public UUID getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = Objects.requireNonNull(name, "name");
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public MemberStatus getStatus() {
        return status;
    }

    public MemberRole getRole() {
        return role;
    }

    public MemberProvider getProvider() {
        return provider;
    }

    public String getProviderUserId() {
        return providerUserId;
    }

    public short getFailedLoginAttempts() {
        return failedLoginAttempts;
    }

    public Instant getLockedUntil() {
        return lockedUntil;
    }

    public Instant getLastLoginAt() {
        return lastLoginAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getWithdrawnAt() {
        return withdrawnAt;
    }

    /**
     * 비밀번호 해시가 로그에 실려 나가지 않게 한다.
     *
     * <p>엔티티를 그대로 로그에 찍는 코드는 언젠가 반드시 생긴다. 그때 해시가 통째로
     * 남으면 오프라인 대입의 재료가 된다. 이메일도 개인정보라 빼고 id 만 남긴다.
     */
    @Override
    public String toString() {
        return "Member{id=" + id + ", status=" + status + "}";
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Member other && id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
