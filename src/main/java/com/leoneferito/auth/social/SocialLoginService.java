package com.leoneferito.auth.social;

import com.leoneferito.auth.SessionTerminator;
import com.leoneferito.auth.MemberPrincipal;
import com.leoneferito.member.Member;
import com.leoneferito.member.MemberRepository;
import com.leoneferito.member.MemberSocialLink;
import com.leoneferito.member.MemberSocialLinkRepository;
import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;

/**
 * 간편 로그인 (카카오 · 네이버).
 *
 * <ol>
 *   <li>그 제공자 계정으로 가입한 회원이 있으면 그 회원.</li>
 *   <li>기존 회원에 연결해 둔 제공자 계정이면 그 회원 (V28 member_social_link).</li>
 *   <li>같은 이메일의 회원이 있으면 — 제공자가 <b>확인한 이메일</b>이고 <b>관리자가 아닐 때만</b> 그 회원에 연결한다
 *       (2026-10-13 "이미 가입된 이메일이라 간편 로그인이 안 된다"). 확인되지 않은 이메일이면 거절한다 —
 *       남의 이메일로 제공자 계정을 만든 사람이 그 회원으로 들어오면 안 된다. 관리자는 비밀번호로만 들어온다.</li>
 *   <li>아무도 없으면 새로 가입.</li>
 * </ol>
 */
@Service
public class SocialLoginService implements OAuth2UserService<OAuth2UserRequest, OAuth2User> {
    private static final Logger log = LoggerFactory.getLogger(SocialLoginService.class);
    private final MemberRepository members;
    private final MemberSocialLinkRepository links;
    private final SessionTerminator sessions;
    private final org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;
    private final OAuth2UserService<OAuth2UserRequest, OAuth2User> delegate;

    @Autowired
    public SocialLoginService(MemberRepository members, MemberSocialLinkRepository links, SessionTerminator sessions,
                              org.springframework.security.crypto.password.PasswordEncoder passwordEncoder) {
        this(members, links, sessions, passwordEncoder, new DefaultOAuth2UserService());
    }

    public SocialLoginService(MemberRepository members, MemberSocialLinkRepository links, SessionTerminator sessions,
                              org.springframework.security.crypto.password.PasswordEncoder passwordEncoder,
                              OAuth2UserService<OAuth2UserRequest, OAuth2User> delegate) {
        this.members = members;
        this.links = links;
        this.sessions = sessions;
        this.passwordEncoder = passwordEncoder;
        this.delegate = delegate;
    }

    @Override
    public OAuth2User loadUser(OAuth2UserRequest request) throws OAuth2AuthenticationException {
        OAuth2User remote = delegate.loadUser(request);
        SocialProvider provider = SocialProvider.of(request.getClientRegistration().getRegistrationId());
        SocialProvider.Profile profile = provider.profileOf(remote.getAttributes());
        if (profile.userId() == null) {
            throw reject("provider_error", "제공자가 사용자 id 를 주지 않았습니다.");
        }
        if (profile.email() == null) {
            throw reject("email_required", "이메일 제공에 동의해야 가입할 수 있습니다.");
        }

        Member member = members.findByProviderAndProviderUserId(provider.memberProvider, profile.userId())
                .or(() -> links.findByProviderAndProviderUserId(provider.memberProvider, profile.userId())
                        .flatMap(link -> members.findById(link.getMemberId())))
                .orElseGet(() -> linkOrSignup(provider, profile));

        // 관리자는 비밀번호로만 들어온다 — 이미 연결된 간편 로그인으로도 관리자 세션을 주지 않는다(2026-11-04 보안 점검).
        // 간편 로그인으로 들어오면 비밀번호 잠금 · 요청 제한 · 임시 비밀번호 차단 같은 관리자 쪽 방어를 모두 건너뛴다.
        if (member.getRole().isAdmin()) {
            throw reject("admin_social", "관리자 계정은 간편 로그인으로 들어올 수 없습니다. 이메일과 비밀번호로 로그인해 주세요.");
        }
        if (member.isSuspended()) {
            throw reject("suspended", "이용이 정지된 계정입니다.");
        }
        if (!member.isActive()) {
            throw reject("withdrawn", "탈퇴한 계정입니다.");
        }

        member.recordSuccessfulLogin(Instant.now());
        members.save(member);
        log.info("간편 로그인 provider={} memberId={}", provider, member.getId());

        return new SocialUser(MemberPrincipal.from(member), remote);
    }

    /** 같은 이메일의 회원이 있으면 (조건이 맞을 때) 연결, 없으면 가입. */
    private Member linkOrSignup(SocialProvider provider, SocialProvider.Profile profile) {
        String email = Member.normalizeEmail(profile.email());
        Member existing = members.findByEmail(email).orElse(null);
        if (existing == null) {
            return signup(provider, profile, email);
        }
        if (!profile.emailVerified()) {
            throw reject("email_in_use", "이미 이메일로 가입된 주소입니다. 이메일로 로그인해 주세요.");
        }
        if (existing.getRole().isAdmin()) {
            throw reject("admin_social", "관리자 계정은 간편 로그인으로 들어올 수 없습니다. 이메일과 비밀번호로 로그인해 주세요.");
        }
        if (existing.isSuspended()) {
            throw reject("suspended", "이용이 정지된 계정입니다.");
        }
        if (!existing.isActive()) {
            throw reject("withdrawn", "탈퇴한 계정입니다.");
        }
        // 이 회원에 같은 제공자의 다른 계정이 이미 있다(가입한 방법이든 연결이든) — 둘째 계정은 붙이지 않는다
        if (existing.getProvider() == provider.memberProvider
                || links.existsByMemberIdAndProvider(existing.getId(), provider.memberProvider)) {
            throw reject("email_in_use", "이 이메일에는 다른 " + provider.registrationId + " 계정이 이미 연결되어 있습니다.");
        }
        links.save(new MemberSocialLink(existing.getId(), provider.memberProvider, profile.userId(), Instant.now()));
        /*
         * 이메일 가입은 이메일 확인 없이 된다. 그래서 남이 이 이메일로 먼저 가입해 비밀번호를 정해 두었을 수 있다 —
         * 제공자가 확인한 이메일의 주인(지금 들어오는 사람)에게 연결하면서, 그 비밀번호를 아무도 모르는 값으로 바꾸고
         * 로그인 중인 세션은 끊는다(이메일 가입 계정은 비밀번호를 비울 수 없다 — DB member_credential_shape).
         * 본인이 비밀번호로도 쓰려면 «비밀번호 찾기» 로 새로 정한다. (2026-11-04 오픈 전 보안 점검)
         */
        if (existing.hasPassword()) {
            sessions.terminateAll(existing.getEmail());
            existing.changePassword(passwordEncoder.encode(UUID.randomUUID().toString()));
            members.save(existing);
            log.info("간편 로그인 연결 — 기존 비밀번호를 임의 값으로 바꾸고 세션을 끊음 memberId={}", existing.getId());
        }
        log.info("간편 로그인 연결 provider={} memberId={}", provider, existing.getId());
        return existing;
    }

    private Member signup(SocialProvider provider, SocialProvider.Profile profile, String email) {
        String name = profile.name() != null ? profile.name() : "회원";
        Member member = Member.social(UUID.randomUUID(), provider.memberProvider,
                profile.userId(), email, name);
        /*
         * 간편가입은 가입 화면의 체크를 거치지 않는다. 대신 버튼 바로 위에 "처음이면 약관 동의 ·
         * 만 14세 이상 확인으로 가입됩니다" 를 고지한다(SocialButtons). 그 고지를 보고 누른 것으로 기록한다.
         */
        member.agreeTerms("SOCIAL_NOTICE", Instant.now());
        members.save(member);
        log.info("간편 가입 provider={} memberId={}", provider, member.getId());
        return member;
    }
    private static OAuth2AuthenticationException reject(String code, String message) {
        return new OAuth2AuthenticationException(new OAuth2Error(code, message, null), message);
    }
    public record SocialUser(MemberPrincipal principal, OAuth2User remote) implements OAuth2User {
        @Override
        public Map<String, Object> getAttributes() {
            return remote.getAttributes();
        }
        @Override
        public Collection<? extends GrantedAuthority> getAuthorities() {
            return principal.getAuthorities();
        }
        @Override
        public String getName() {
            return principal.getUsername();
        }
    }
}