package com.leoneferito.common.error;

/**
 * 순서를 바꾸려는 목록이 그 사이 바뀌었다(다른 관리자가 추가·삭제·공개). 409 STALE_LIST.
 * 옛 목록으로 덮으면 순서가 뒤섞이므로, 다시 불러오게 한다.
 */
public class StaleListException extends RuntimeException {
    public StaleListException() {
        super("목록이 바뀌었습니다. 새로고침한 뒤 다시 정렬해 주세요.");
    }
}
