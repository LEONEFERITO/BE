package com.leoneferito.member;

import java.util.List;

/**
 * 회원 역할.
 *
 * <p>기본은 {@link #MEMBER} 다.
 * <ul>
 *   <li>{@link #ADMIN} — 상품·회원·주문 운영. {@link #SUPER_ADMIN} 이 회원 관리 화면에서 지정한다.</li>
 *   <li>{@link #SUPER_ADMIN} — 관리자 지정·해제. <b>서버 명령(create-admin)으로만</b> 만든다.
 *       화면으로는 만들 수도 내릴 수도 없다 — 그래서 마지막 최고 관리자가 사라지는 일이 없다.</li>
 * </ul>
 * 관리자 계정 하나가 뚫려도 그 계정으로 관리자를 늘릴 수 없게 한 단계를 나눈 것이다 (V10).
 */
public enum MemberRole {
    MEMBER,
    ADMIN,
    SUPER_ADMIN;

    /**
     * Spring Security 권한. 이름에 ROLE_ 접두사가 붙는다.
     *
     * <p>SUPER_ADMIN 은 ADMIN 권한도 <b>함께</b> 갖는다. 그래야 {@code /api/admin/**} 의
     * {@code hasRole("ADMIN")} 규칙 하나로 두 역할이 다 들어온다 — 규칙마다 둘을 나열하면
     * 새 엔드포인트에서 하나를 빠뜨리는 날이 온다.
     */
    public List<String> authorities() {
        return switch (this) {
            case MEMBER -> List.of("ROLE_MEMBER");
            case ADMIN -> List.of("ROLE_ADMIN");
            case SUPER_ADMIN -> List.of("ROLE_ADMIN", "ROLE_SUPER_ADMIN");
        };
    }

    public boolean isAdmin() {
        return this != MEMBER;
    }
}
