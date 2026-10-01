package com.leoneferito.member.api;

import com.leoneferito.auth.MemberPrincipal;
import com.leoneferito.member.AdminMemberService;
import com.leoneferito.member.MemberRole;
import com.leoneferito.member.MemberStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 관리자 회원 API. /api/admin/** 는 ADMIN, 역할 변경(PUT …/role)만 SUPER_ADMIN (SecurityConfig).
 * 회원을 지우는 API 는 없다. 탈퇴는 본인만 한다.
 */
@RestController
@RequestMapping("/api/admin/members")
public class AdminMemberController {

    private final AdminMemberService service;

    public AdminMemberController(AdminMemberService service) {
        this.service = service;
    }

    @GetMapping
    public AdminMemberResponse.Page search(@RequestParam(defaultValue = "") String q,
                                           @RequestParam(required = false) MemberStatus status,
                                           @RequestParam(defaultValue = "0") int page) {
        return AdminMemberResponse.Page.of(service.search(q, status, page));
    }

    @GetMapping("/{id}")
    public AdminMemberResponse.Detail detail(@AuthenticationPrincipal MemberPrincipal actor,
                                             @PathVariable UUID id) {
        return AdminMemberResponse.Detail.of(service.detail(actor.getId(), id));
    }

    @PostMapping("/{id}/unlock")
    public ResponseEntity<Void> unlock(@AuthenticationPrincipal MemberPrincipal actor, @PathVariable UUID id) {
        service.unlock(actor.getId(), id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/suspend")
    public ResponseEntity<Void> suspend(@AuthenticationPrincipal MemberPrincipal actor, @PathVariable UUID id,
                                        @Valid @RequestBody Suspend request) {
        service.suspend(actor.getId(), id, request.reason());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/reactivate")
    public ResponseEntity<Void> reactivate(@AuthenticationPrincipal MemberPrincipal actor, @PathVariable UUID id) {
        service.reactivate(actor.getId(), id);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{id}/role")
    public ResponseEntity<Void> changeRole(@AuthenticationPrincipal MemberPrincipal actor, @PathVariable UUID id,
                                           @Valid @RequestBody RoleChange request) {
        service.changeRole(actor.getId(), id, request.role());
        return ResponseEntity.noContent().build();
    }

    /** 정지 사유는 필수다. 나중에 "왜 막혔냐" 는 문의에 답할 근거가 된다. */
    public record Suspend(@NotBlank @Size(max = 200) String reason) {
    }

    public record RoleChange(@NotNull MemberRole role) {
    }
}