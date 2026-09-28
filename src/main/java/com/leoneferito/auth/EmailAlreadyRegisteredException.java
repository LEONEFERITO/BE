package com.leoneferito.auth;

/**
 * 이미 가입된 이메일로 가입을 시도한 경우.
 *
 * <p>생성자가 받는 이메일은 <b>로그용</b>이다. 응답에 실어 보내지 않는다 —
 * 입력값을 그대로 돌려주면 그 값이 에러 화면에 그려지는 경로가 생긴다.
 *
 * <p>이 예외의 존재 자체가 계정 열거 통로라는 점은 {@link AuthService#signup} 주석 참고.
 */
public class EmailAlreadyRegisteredException extends RuntimeException {

    public EmailAlreadyRegisteredException(String emailForLog) {
        super("이미 가입된 이메일: " + emailForLog);
    }
}
