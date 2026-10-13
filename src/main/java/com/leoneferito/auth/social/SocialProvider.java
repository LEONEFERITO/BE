package com.leoneferito.auth.social;

import com.leoneferito.member.MemberProvider;
import java.util.Locale;
import java.util.Map;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;

public enum SocialProvider {

    KAKAO(MemberProvider.KAKAO, "kakao") {
        @Override
        ClientRegistration.Builder registration() {
            return ClientRegistration.withRegistrationId(registrationId)
                    .clientName("카카오")
                    .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST)
                    .authorizationUri("https://kauth.kakao.com/oauth/authorize")
                    .tokenUri("https://kauth.kakao.com/oauth/token")
                    .userInfoUri("https://kapi.kakao.com/v2/user/me")
                    .userNameAttributeName("id")
                    .scope("profile_nickname", "account_email");
        }

        @Override
        public Profile profileOf(Map<String, Object> attributes) {
            Object id = attributes.get("id");
            Map<String, Object> account = map(attributes.get("kakao_account"));
            Map<String, Object> profile = map(account.get("profile"));
            // 카카오가 "유효하고 인증된 이메일" 이라고 알려 줄 때만 확인된 이메일로 본다 (V28 기존 회원 연결)
            boolean verified = Boolean.TRUE.equals(account.get("is_email_valid"))
                    && Boolean.TRUE.equals(account.get("is_email_verified"));
            return new Profile(
                    id == null ? null : String.valueOf(id),
                    text(account.get("email")),
                    text(profile.get("nickname")),
                    verified);
        }
    },

    NAVER(MemberProvider.NAVER, "naver") {
        @Override
        ClientRegistration.Builder registration() {
            return ClientRegistration.withRegistrationId(registrationId)
                    .clientName("네이버")
                    .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST)
                    .authorizationUri("https://nid.naver.com/oauth2.0/authorize")
                    .tokenUri("https://nid.naver.com/oauth2.0/token")
                    .userInfoUri("https://openapi.naver.com/v1/nid/me")
                    .userNameAttributeName("response");
        }

        @Override
        public Profile profileOf(Map<String, Object> attributes) {
             Map<String, Object> response = map(attributes.get("response"));
            String name = text(response.get("name"));
            String email = text(response.get("email"));
            // 네이버는 확인 여부를 주지 않는다. 그 네이버 계정 자체의 주소(@naver.com)만 확인된 것으로 본다 —
            // 연락처 이메일을 다른 주소로 바꿔 둔 경우는 그 주소의 주인인지 알 수 없다.
            boolean verified = email != null && email.toLowerCase(Locale.ROOT).endsWith("@naver.com");
            return new Profile(
                    text(response.get("id")),
                    email,
                    name != null ? name : text(response.get("nickname")),
                    verified);
        }
    };

    /**
     * 제공자가 준 사용자 정보. {@code emailVerified} — 제공자가 이 이메일의 주인을 확인했는가.
     * true 일 때만 같은 이메일의 기존 회원에 연결한다(SocialLoginService, V28).
     */
    public record Profile(String userId, String email, String name, boolean emailVerified) { }
    public final MemberProvider memberProvider;
    public final String registrationId;

    SocialProvider(MemberProvider memberProvider, String registrationId) {
        this.memberProvider = memberProvider;
        this.registrationId = registrationId;
    }
     abstract ClientRegistration.Builder registration();

     public abstract Profile profileOf(Map<String, Object> attributes);

    public ClientRegistration registration(String clientId, String clientSecret) {
        return registration()
                .clientId(clientId)
                .clientSecret(clientSecret)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                .build();
    }
    public static SocialProvider of(String registrationId) {
        return valueOf(registrationId.toUpperCase(Locale.ROOT));
    }
    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }
    static String text(Object value) {
        if (value == null) return null;
        String s = String.valueOf(value).trim();
        return s.isEmpty() ? null : s;
    }
}