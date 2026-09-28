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

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(nullable = false)
    private String name;

    @Column
    private String phone;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MemberStatus status = MemberStatus.ACTIVE;

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

    protected Member() {
        // JPA
    }

    public Member(UUID id, String email, String passwordHash, String name, String phone) {
        this.id = Objects.requireNonNull(id, "id");
        this.email = normalizeEmail(email);
        this.passwordHash = Objects.requireNonNull(passwordHash, "passwordHash");
        this.name = Objects.requireNonNull(name, "name");
        this.phone = phone;
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

    public void changePassword(String newPasswordHash) {
        this.passwordHash = Objects.requireNonNull(newPasswordHash, "passwordHash");
    }

    public void withdraw() {
        this.status = MemberStatus.WITHDRAWN;
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
