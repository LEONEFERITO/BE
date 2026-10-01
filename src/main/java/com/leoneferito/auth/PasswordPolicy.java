package com.leoneferito.auth;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

@Component
public class PasswordPolicy {
    static final int MIN_LENGTH = 10;
    static final int MAX_BYTES = 72;
    public void validate(String rawPassword, String email){
        if(rawPassword == null || rawPassword.length() < MIN_LENGTH){
            throw new WeakPasswordException("비밀번호는" + MIN_LENGTH + "자 이상이어야 합니다.");
        }
        if(rawPassword.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES){
            throw new WeakPasswordException("비밀번호가 너무 깁니다. 영문 기준 " + MAX_BYTES + "자 이내로 입력해 주세요.");
        }
        int at = email.indexOf('@');
        String localPart = at < 0 ? email : email.substring(0, at);
        if(!localPart.isBlank() && rawPassword.toLowerCase(Locale.ROOT).contains(localPart)){
            throw new WeakPasswordException("비밀번호에 이메일 주소를 포함할 수 없습니다.");
        }
    }
}
