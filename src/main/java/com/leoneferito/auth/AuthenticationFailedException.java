package com.leoneferito.auth;

/**
 * 로그인 실패.
 *
 * <p><b>이유를 두 가지로만 나눈다.</b> "이메일이 없음" 과 "비밀번호가 틀림" 을 구분해서
 * 알려주면, 공격자가 로그인 화면만으로 <b>어떤 이메일이 가입되어 있는지</b> 알아낼 수 있다
 * (계정 열거). 둘 다 {@link Reason#INVALID_CREDENTIALS} 하나로 답한다.
 *
 * <p>{@link Reason#ACCOUNT_LOCKED} 는 <b>비밀번호가 맞았을 때만</b> 내보낸다.
 * 틀린 비밀번호로 시도한 사람에게 "잠겼습니다" 라고 하면 그 계정의 존재가 새기 때문이다.
 * 비밀번호를 아는 사람에게는 이미 계정이 있다는 게 비밀이 아니고, 왜 못 들어가는지
 * 알려주지 않으면 손님이 같은 시도를 반복한다.
 */
public class AuthenticationFailedException extends RuntimeException {

    public enum Reason {
        /** 이메일이 없거나, 비밀번호가 틀리거나, 탈퇴한 계정이다. 셋을 구분하지 않는다. */
        INVALID_CREDENTIALS,
        /** 자격증명은 맞지만 실패 누적으로 잠겨 있다. */
        ACCOUNT_LOCKED
    }

    private final Reason reason;

    public AuthenticationFailedException(Reason reason) {
        super(reason.name());
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}
