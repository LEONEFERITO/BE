package com.leoneferito.media;

/**
 * 업로드된 파일이 이미지로서 받아들일 수 없는 경우.
 *
 * <p>이 예외의 메시지는 <b>관리자에게 그대로 보여준다.</b> 업로드하는 사람은 신뢰 경계
 * 안쪽(운영자)이고, 무엇이 잘못됐는지 알아야 다시 올릴 수 있다.
 * 다만 메시지에 <b>내부 경로나 라이브러리 이름을 넣지 않는다</b> — 화면 캡처는 밖으로 나간다.
 */
public class InvalidImageException extends RuntimeException {

    public InvalidImageException(String userFacingMessage) {
        super(userFacingMessage);
    }
}
