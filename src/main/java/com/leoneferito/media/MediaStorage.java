package com.leoneferito.media;

import com.leoneferito.common.error.ResourceNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 업로드된 이미지 바이트를 저장하고 읽는다.
 *
 * <p>DB 에는 키만 두고 바이트는 여기 둔다(V2 주석 참고).
 *
 * <h2>파일 이름을 절대 사용자에게서 받지 않는다</h2>
 * 저장 키는 서버가 UUID 로 만든다. 사용자가 준 이름을 경로에 쓰면
 * {@code ../../etc/passwd} 같은 값으로 디렉터리를 빠져나갈 수 있다(경로 순회).
 * 원본 파일명은 표시용으로 DB 에만 남고, 파일 시스템에는 닿지 않는다.
 *
 * <h2>지금은 로컬 디스크다</h2>
 * 인스턴스가 하나인 동안은 이걸로 충분하다. S3 같은 객체 스토리지로 옮길 때는
 * 이 클래스만 바꾸면 된다 — 호출하는 쪽은 키만 알고 있다.
 * 다만 <b>인스턴스를 늘리는 순간</b> 각 서버가 자기 디스크만 보게 되므로 그전에 옮겨야 한다.
 */
@Component
public class MediaStorage {

    private final Path root;

    public MediaStorage(@Value("${app.media.storage-dir:./media-store}") String dir) {
        this.root = Path.of(dir).toAbsolutePath().normalize();
    }

    /**
     * 저장하고 키를 돌려준다.
     *
     * @param format 매직바이트로 판별한 형식. 확장자는 여기서 나온다 —
     *               사용자가 준 확장자를 쓰면 위장 파일이 그대로 저장된다.
     */
    public String store(InputStream bytes, ImageFormat format) throws IOException {
        // 날짜로 나눠 담는다. 한 폴더에 수만 개가 쌓이면 파일 시스템이 느려진다.
        String day = java.time.LocalDate.now().toString();
        String key = day + "/" + UUID.randomUUID() + format.extension();

        Path target = resolve(key);
        Files.createDirectories(target.getParent());
        Files.copy(bytes, target, StandardCopyOption.REPLACE_EXISTING);
        return key;
    }

    public Path read(String key) {
        Path path = resolve(key);
        if (!Files.isRegularFile(path)) {
            throw new ResourceNotFoundException("미디어 파일 없음: key=" + key);
        }
        return path;
    }

    public void delete(String key) throws IOException {
        Files.deleteIfExists(resolve(key));
    }

    /**
     * 키를 실제 경로로 바꾼다.
     *
     * <p>정규화한 결과가 저장 루트 <b>안</b>에 있는지 반드시 확인한다.
     * 키는 우리가 만든 값이지만, 언젠가 이 메서드에 외부 값이 들어오는 날이 온다.
     * 그때 이 검사가 없으면 조용히 서버의 아무 파일이나 읽어 내보내게 된다.
     */
    private Path resolve(String key) {
        Path path = root.resolve(key).normalize();
        if (!path.startsWith(root)) {
            throw new IllegalArgumentException("저장 경로를 벗어나는 키: " + key);
        }
        return path;
    }
}
