package com.leoneferito.auth;

/**
 * 비밀번호가 규칙을 만족하지 못한 경우.
 *
 * <p>이 예외의 메시지는 <b>손님에게 그대로 보여준다.</b> 다른 예외들과 반대다.
 * "입력값을 확인해 주세요" 만 주면 무엇을 고쳐야 하는지 알 수 없어 같은 실패를 반복한다.
 * 비밀번호 규칙은 어차피 공개된 정보이므로 숨겨서 얻는 것이 없다.
 */
public class WeakPasswordException extends RuntimeException {

    public WeakPasswordException(String userFacingMessage) {
        super(userFacingMessage);
    }
}
