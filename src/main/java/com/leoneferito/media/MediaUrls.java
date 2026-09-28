package com.leoneferito.media;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 업로드된 이미지의 공개 URL 을 만든다.
 *
 * <p>DB 에는 스토리지 키만 있다(V2 참고). 그 키를 어느 주소로 내보낼지는 배포 환경마다
 * 다르다 — 로컬은 서버가 직접 서빙하고, 운영은 CDN 앞단이 받는다. 그래서 기준 주소를
 * 설정으로 빼고, 키를 붙이는 규칙은 여기 한 곳에만 둔다.
 *
 * <p>규칙이 여러 곳에 흩어지면 CDN 주소가 바뀌는 날 한 군데가 반드시 남는다.
 *
 * <p><b>지금 상태:</b> 업로드·서빙이 아직 없다. 그래서 이 메서드가 만든 주소는
 * 실제로는 비어 있다. 주소 형식을 먼저 고정해 두는 것이고, 스토리지가 붙으면
 * {@code app.media.base-url} 만 바꾸면 된다.
 */
@Component
public class MediaUrls {

    private final String baseUrl;

    public MediaUrls(@Value("${app.media.base-url:/media}") String baseUrl) {
        // 뒤에 / 가 붙어 오든 말든 결과가 같아야 한다. 설정 실수로 //key 가 되는 걸 막는다.
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    public String urlFor(MediaAsset asset) {
        return baseUrl + "/" + asset.getStorageKey();
    }
}
