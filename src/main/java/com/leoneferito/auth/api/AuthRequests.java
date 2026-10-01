package com.leoneferito.auth.api;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 인증 요청 본문.
 *
 * <p>비밀번호에 {@code @Size} 상한만 걸고 하한은 여기 두지 않았다.
 * 길이·구성 규칙은 {@code AuthService} 한 곳에서 판단한다 — 규칙이 두 군데 있으면
 * 한쪽만 바뀌는 날이 오고, 그때 어느 쪽이 진짜인지 아무도 모른다.
 * 여기 상한은 <b>해시 계산 전에</b> 터무니없이 큰 입력을 끊기 위한 방어선이다.
 */
public final class AuthRequests {

    private AuthRequests() {
    }

    public record Signup(
            @NotBlank @Email @Size(max = 254) String email,
            // 1000자 제한은 규칙이 아니라 방어다. BCrypt 에 메가바이트를 먹이지 않기 위한 것.
            @NotBlank @Size(max = 1000) String password,
            @NotBlank @Size(max = 50) String name,
            /*
             * 전화번호는 선택이다. 형식은 숫자와 하이픈만 허용한다 —
             * 국가별 형식을 강하게 검증하면 멀쩡한 번호가 거부된다.
             */
            @Pattern(regexp = "^[0-9-]{9,20}$", message = "전화번호 형식이 올바르지 않습니다.")
            String phone,
            /*
             * 약관 동의 · 만 14세 확인. 화면의 체크만 믿지 않고 서버가 다시 본다 —
             * API 를 직접 부르면 화면 검사는 없는 것과 같다. 빠지면 false 라 거절된다.
             */
            @AssertTrue(message = "이용약관에 동의해 주세요.") boolean agreeTerms,
            @AssertTrue(message = "만 14세 이상만 가입할 수 있습니다.") boolean over14) {
    }

    public record Login(
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(max = 1000) String password) {
    }

    /** 관리자 로그인 — 아이디(login_id) 또는 이메일. */
    public record AdminLogin(
            @NotBlank @Size(max = 254) String loginId,
            @NotBlank @Size(max = 1000) String password) {
    }

    /** 비밀번호 찾기 — 메일 요청. 가입 여부와 상관없이 같은 답을 한다. */
    public record PasswordResetRequest(@NotBlank @Email @Size(max = 254) String email) {
    }

    /** 비밀번호 찾기 — 메일 속 링크의 토큰과 새 비밀번호. 토큰은 43자(32바이트 base64url). */
    public record PasswordResetConfirm(
            @NotBlank @Size(max = 100) String token,
            @NotBlank @Size(max = 1000) String newPassword) {
    }
}
