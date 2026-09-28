package com.leoneferito.common.error;

/**
 * 요청한 것이 없을 때. {@link GlobalExceptionHandler} 가 404 로 바꾼다.
 *
 * <p><b>"없음" 과 "볼 권한이 없음" 을 구분하지 않는다.</b> 공개 API 에서 비공개(DRAFT) 상품을
 * 403 으로 돌려주면, 그 slug 가 존재한다는 사실이 새어 나간다. 준비 중인 상품의 이름을
 * 출시 전에 알아낼 수 있게 되는 것이다. 둘 다 404 로 답한다.
 *
 * <p>같은 이유로 생성자가 받는 메시지는 <b>로그용</b>이다. 클라이언트에는 나가지 않는다.
 */
public class ResourceNotFoundException extends RuntimeException {

    public ResourceNotFoundException(String logMessage) {
        super(logMessage);
    }
}
