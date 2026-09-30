package com.leoneferito.auth.social;

import com.leoneferito.auth.MemberPrincipal;
import com.leoneferito.member.Member;
import com.leoneferito.member.MemberRepository;
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

@Service
public class SocialLoginService implements OAuth2UserService<OAuth2UserRequest, OAuth2User> {
    private static final Logger log = LoggerFactory.getLogger(SocialLoginService.class);
    private final MemberRepository members;
    private final OAuth2UserService<OAuth2UserRequest, OAuth2User> delegate;

    @Autowired
    public SocialLoginService(MemberRepository members) {
        this(members, new DefaultOAuth2UserService());
    }

    public SocialLoginService(MemberRepository members,
                              OAuth2UserService<OAuth2UserRequest, OAuth2User> delegate) {
        this.members = members;
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
                .orElseGet(() -> signup(provider, profile));

        if (!member.isActive()) {
            throw reject("withdrawn", "탈퇴한 계정입니다.");
        }

        member.recordSuccessfulLogin(Instant.now());
        members.save(member);
        log.info("간편 로그인 provider={} memberId={}", provider, member.getId());

        return new SocialUser(MemberPrincipal.from(member), remote);
    }

    private Member signup(SocialProvider provider, SocialProvider.Profile profile) {
        String email = Member.normalizeEmail(profile.email());
        if (members.existsByEmail(email)) {
            throw reject("email_in_use", "이미 이메일로 가입된 주소입니다. 이메일로 로그인해 주세요.");
        }
        String name = profile.name() != null ? profile.name() : "회원";
        Member member = Member.social(UUID.randomUUID(), provider.memberProvider,
                profile.userId(), email, name);
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