package com.leoneferito.auth;

import com.leoneferito.member.Member;
import com.leoneferito.member.MemberRole;
import java.io.Serializable;
import java.util.Collection;
import java.util.UUID;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * 세션에 담기는 로그인 주체.
 *
 * <p><b>비밀번호 해시를 담지 않는다.</b> 이 객체는 직렬화되어 세션 테이블에 저장된다.
 * 해시를 넣으면 DB 두 곳(회원 테이블, 세션 테이블)에 같은 비밀이 생기고,
 * 세션 테이블은 회원 테이블만큼 조심스럽게 다뤄지지 않는다.
 * {@link #getPassword()} 가 빈 문자열을 돌려주는 건 그래서다 — 우리는 인증을
 * {@link AuthService} 에서 직접 하므로 Spring 이 이 값을 볼 일이 없다.
 *
 * <p>{@link Serializable} 인 이유도 같다. 세션이 DB 에 바이트로 저장되므로
 * 이 클래스에 필드를 더할 때는 <b>기존 세션이 역직렬화되지 않을 수 있다</b>는 걸 염두에 둔다.
 * 그래서 꼭 필요한 것만 둔다 — 이름이 바뀌면 다음 로그인 때 반영된다.
 */
public class MemberPrincipal implements UserDetails, Serializable {

    private static final long serialVersionUID = 1L;

    private final UUID id;
    private final String email;
    private final String name;
    private final MemberRole role;

    public MemberPrincipal(UUID id, String email, String name, MemberRole role) {
        this.id = id;
        this.email = email;
        this.name = name;
        this.role = role;
    }

    public static MemberPrincipal from(Member member) {
        return new MemberPrincipal(member.getId(), member.getEmail(),
                member.getName(), member.getRole());
    }

    public UUID getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public String getName() {
        return name;
    }

    public MemberRole getRole() {
        return role;
    }

    /**
     * 권한.
     *
     * <p>세션에 저장된 값이므로, DB 에서 역할을 바꿔도 <b>이미 로그인한 세션에는
     * 반영되지 않는다.</b> 관리자를 내릴 때는 세션도 함께 끊어야 한다
     * (SPRING_SESSION 에서 해당 PRINCIPAL_NAME 행을 지운다).
     * 반대 방향(승격)은 다시 로그인하면 된다. 회원 관리 화면은 내릴 때 세션을 함께 끊는다
     * ({@code SessionTerminator}).
     */
    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return role.authorities().stream().map(SimpleGrantedAuthority::new).toList();
    }

    /** 세션에 비밀번호를 담지 않는다. 위 클래스 주석 참고. */
    @Override
    public String getPassword() {
        return "";
    }

    @Override
    public String getUsername() {
        return email;
    }
}
