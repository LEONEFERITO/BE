package com.leoneferito.member.api;

import com.leoneferito.auth.MemberPrincipal;
import com.leoneferito.member.Member;
import com.leoneferito.member.MemberAccountService;
import com.leoneferito.member.MemberProvider;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 내 정보 (마이페이지). 대상은 언제나 로그인한 본인이다.
 * 경로에 회원 id 를 받지 않는다 — 받으면 그 id 가 본인 것인지 확인하는 코드를 빠뜨린 곳이 남의 정보를 연다.
 */
@RestController
@RequestMapping("/api/me")
public class MeController {

    private final MemberAccountService accounts;
    private final SecurityContextRepository contextRepository;

    public MeController(MemberAccountService accounts, SecurityContextRepository contextRepository) {
        this.accounts = accounts;
        this.contextRepository = contextRepository;
    }

    @GetMapping("/profile")
    public Profile profile(@AuthenticationPrincipal MemberPrincipal principal) {
        return Profile.from(accounts.get(principal.getId()));
    }

    /** 이름 · 전화번호 수정. 세션에 든 이름도 새로 담는다 — 헤더의 "○○님" 이 바로 바뀌게. */
    @PatchMapping("/profile")
    public Profile updateProfile(@AuthenticationPrincipal MemberPrincipal principal,
                                 @Valid @RequestBody ProfileUpdate request,
                                 HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        Member member = accounts.updateProfile(principal.getId(), request.name(), request.phone());

        MemberPrincipal refreshed = MemberPrincipal.from(member);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                refreshed, null, refreshed.getAuthorities()));
        SecurityContextHolder.setContext(context);
        contextRepository.saveContext(context, httpRequest, httpResponse);

        return Profile.from(member);
    }

    @PostMapping("/password")
    public ResponseEntity<Void> changePassword(@AuthenticationPrincipal MemberPrincipal principal,
                                               @Valid @RequestBody PasswordChange request,
                                               HttpServletRequest httpRequest) {
        HttpSession session = httpRequest.getSession(false);
        accounts.changePassword(principal.getId(), request.currentPassword(), request.newPassword(),
                session == null ? null : session.getId());
        return ResponseEntity.noContent().build();
    }

    /** 탈퇴. 성공하면 이 세션도 끝난다. */
    @PostMapping("/withdraw")
    public ResponseEntity<Void> withdraw(@AuthenticationPrincipal MemberPrincipal principal,
                                         @Valid @RequestBody Withdraw request,
                                         HttpServletRequest httpRequest) {
        accounts.withdraw(principal.getId(), request.password());
        HttpSession session = httpRequest.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        SecurityContextHolder.clearContext();
        return ResponseEntity.noContent().build();
    }

    /** 마이페이지가 그리는 값만. 잠금·실패 횟수 같은 내부 상태는 주지 않는다. */
    public record Profile(String email, String name, String phone, MemberProvider provider,
                          boolean hasPassword, Instant createdAt) {
        static Profile from(Member m) {
            return new Profile(m.getEmail(), m.getName(), m.getPhone(), m.getProvider(),
                    m.hasPassword(), m.getCreatedAt());
        }
    }

    public record ProfileUpdate(
            @NotBlank @Size(max = 50) String name,
            @Pattern(regexp = "^[0-9-]{9,20}$", message = "전화번호 형식이 올바르지 않습니다.")
            String phone) {
    }

    public record PasswordChange(
            @NotBlank @Size(max = 1000) String currentPassword,
            @NotBlank @Size(max = 1000) String newPassword) {
    }

    /** 간편가입 회원은 비밀번호가 없으므로 비워서 보낸다. */
    public record Withdraw(@Size(max = 1000) String password) {
    }
}