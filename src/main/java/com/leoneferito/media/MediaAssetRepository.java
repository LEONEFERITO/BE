package com.leoneferito.media;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MediaAssetRepository extends JpaRepository<MediaAsset, UUID> {

    /**
     * 저장 키로 찾는다. 서빙 엔드포인트가 쓴다.
     *
     * <p>파일이 디스크에 있어도 <b>이 행이 없으면 내보내지 않는다.</b>
     * 그래야 우리가 기록하지 않은 파일이 우연히 같은 폴더에 생겨도 새어 나가지 않고,
     * Content-Type 을 DB 에 기록된 값(매직바이트로 판별한 것)으로만 쓸 수 있다.
     */
    Optional<MediaAsset> findByStorageKey(String storageKey);
}
