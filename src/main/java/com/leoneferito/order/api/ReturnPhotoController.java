package com.leoneferito.order.api;

import com.leoneferito.auth.MemberPrincipal;
import com.leoneferito.order.ReturnPhoto;
import com.leoneferito.order.ReturnPhotoService;
import java.io.IOException;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
public class ReturnPhotoController {

    private final ReturnPhotoService photos;
    public ReturnPhotoController(ReturnPhotoService photos) {
        this.photos = photos;
    }
    @PostMapping("/api/returns/photos")
    public ResponseEntity<Uploaded> upload(@AuthenticationPrincipal MemberPrincipal me,
                                           @RequestParam("file") MultipartFile file) throws IOException {
        ReturnPhoto photo = photos.upload(me.getId(), file);
        return ResponseEntity.status(HttpStatus.CREATED).body(new Uploaded(photo.getId(), photo.getUrl()));
    }
    public record Uploaded(UUID id, String url) { }
}