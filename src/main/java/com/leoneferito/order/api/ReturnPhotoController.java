package com.leoneferito.order.api;

import com.leoneferito.auth.MemberPrincipal;
import com.leoneferito.media.MediaStorage;
import com.leoneferito.member.MemberRole;
import com.leoneferito.order.ReturnPhoto;
import com.leoneferito.order.ReturnPhotoService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** 교환·반품 증빙 사진 — 올리기(손님)와 보기(주인 · 관리자). 공개 주소(/media/**)로는 나가지 않는다(V34). */
@RestController
public class ReturnPhotoController {

    private final ReturnPhotoService photos;
    private final MediaStorage storage;

    public ReturnPhotoController(ReturnPhotoService photos, MediaStorage storage) {
        this.photos = photos;
        this.storage = storage;
    }

    /**
     * 보기. 주인이거나 관리자만 — 아니면 404. 로그인 쿠키로 판단하므로 캐시는 브라우저 개인용(private)으로만.
     * Content-Type · nosniff 는 /media/** 와 같은 이유(MediaController).
     */
    @GetMapping("/api/returns/photos/{photoId}")
    public ResponseEntity<Resource> view(@AuthenticationPrincipal MemberPrincipal me, @PathVariable UUID photoId)
            throws IOException {
        boolean admin = me.getRole() == MemberRole.ADMIN || me.getRole() == MemberRole.SUPER_ADMIN;
        ReturnPhotoService.PhotoFile file = photos.view(photoId, me.getId(), admin);
        Path path = storage.read(file.storageKey());
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(file.contentType()))
                .contentLength(Files.size(path))
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
                .cacheControl(CacheControl.maxAge(java.time.Duration.ofHours(1)).cachePrivate())
                .body(new FileSystemResource(path));
    }
    @PostMapping("/api/returns/photos")
    public ResponseEntity<Uploaded> upload(@AuthenticationPrincipal MemberPrincipal me,
                                           @RequestParam("file") MultipartFile file) throws IOException {
        ReturnPhoto photo = photos.upload(me.getId(), file);
        return ResponseEntity.status(HttpStatus.CREATED).body(new Uploaded(photo.getId(), photo.getUrl()));
    }
    public record Uploaded(UUID id, String url) { }
}