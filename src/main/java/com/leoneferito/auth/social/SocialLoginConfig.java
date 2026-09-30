package com.leoneferito.auth.social;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestRedirectFilter;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;

@Configuration
@EnableConfigurationProperties(SocialLoginConfig.Properties.class)
public class SocialLoginConfig {
    @ConfigurationProperties(prefix = "app.social")
    public record Properties(Credential kakao, Credential naver){
        public record Credential(String clientId, String clientSecret){
            boolean enabled(){
                return clientId != null && !clientId.isBlank();
            }
        }
        Credential of(SocialProvider provider){
            return switch(provider){
                case KAKAO -> kakao;
                case NAVER -> naver;
            };
        }
    }
    public record Enabled(List<SocialProvider> providers){}
    @Bean
    public Enabled enabledSocialProviders(Properties props){
        return new Enabled(Arrays.stream(SocialProvider.values())
                .filter(p -> props.of(p) != null && props.of(p).enabled())
                .toList());
    }
    @Bean
    public ClientRegistrationRepository clientRegistrationRepository(Properties props, Enabled enabled){
        Map<String, ClientRegistration>byId = new HashMap<>();
        for(SocialProvider provider : enabled.providers()){
            Properties.Credential credential = props.of(provider);
            String secret = credential.clientSecret() == null ? "" : credential.clientSecret();
            byId.put(provider.registrationId, provider.registration(credential.clientId(), secret));
        }
        return byId::get;
    }

    /**
     * 시작 주소({@code /oauth2/authorization/{id}})를 인가 요청으로 바꾸는 쪽.
     *
     * <p>Spring 기본 구현은 등록되지 않은 id 를 만나면 <b>500</b> 을 낸다. 우리는 키가 없는
     * 제공자를 "꺼진 것" 으로 두므로, 그 주소는 그냥 모르는 주소(404)여야 한다.
     * 등록이 있을 때만 기본 구현에 맡기고, 없으면 아무것도 하지 않는다.
     */
    @Bean
    public OAuth2AuthorizationRequestResolver authorizationRequestResolver(
            ClientRegistrationRepository registrations) {
        var delegate = new DefaultOAuth2AuthorizationRequestResolver(
                registrations, OAuth2AuthorizationRequestRedirectFilter.DEFAULT_AUTHORIZATION_REQUEST_BASE_URI);
        return new OAuth2AuthorizationRequestResolver() {
            @Override
            public OAuth2AuthorizationRequest resolve(HttpServletRequest request) {
                String path = request.getRequestURI();
                String id = path.substring(path.lastIndexOf('/') + 1);
                return registrations.findByRegistrationId(id) == null ? null : delegate.resolve(request);
            }

            @Override
            public OAuth2AuthorizationRequest resolve(HttpServletRequest request, String id) {
                return registrations.findByRegistrationId(id) == null ? null : delegate.resolve(request, id);
            }
        };
    }
}
