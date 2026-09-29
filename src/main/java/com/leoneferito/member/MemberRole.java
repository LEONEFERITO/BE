package com.leoneferito.member;

/**
 * 회원 역할.
 *
 * <p>기본은 {@link #MEMBER} 다. 화면으로 관리자가 되는 경로를 두지 않는다 —
 * 승격은 DB 에서 직접 한다(V5 주석 참고). 권한 상승 경로가 적을수록 잃을 것이 적다.
 */
public enum MemberRole {
    MEMBER,
    ADMIN;

    /** Spring Security 는 권한 이름에 ROLE_ 접두사를 요구한다. */
    public String authority() {
        return "ROLE_" + name();
    }
}
