package com.leoneferito.auth;

import com.leoneferito.member.Member;
import java.io.Serializable;
import java.util.Collection;
import java.util.List;
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

    public MemberPrincipal(UUID id, String email, String name) {
        this.id = id;
        this.email = email;
        this.name = name;
    }

    public static MemberPrincipal from(Member member) {
        return new MemberPrincipal(member.getId(), member.getEmail(), member.getName());
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

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        // 지금은 역할이 하나뿐이다. 관리자가 생기면 member 에 role 을 더한다.
        return List.of(new SimpleGrantedAuthority("ROLE_MEMBER"));
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
