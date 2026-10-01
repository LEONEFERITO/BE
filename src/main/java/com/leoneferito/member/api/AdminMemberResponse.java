package com.leoneferito.member.api;

import com.leoneferito.member.AdminMemberService;
import com.leoneferito.member.Member;
import com.leoneferito.member.MemberAdminLog;
import com.leoneferito.member.MemberProvider;
import com.leoneferito.member.MemberRole;
import com.leoneferito.member.MemberStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 관리자 회원 응답. 목록에서는 전화번호를 가린다 — 전체 번호는 상세에서만, 상세를 연 것은 기록된다.
 */
public final class AdminMemberResponse {

    private AdminMemberResponse() {
    }

    public record Row(UUID id, String email, String name, String phone, MemberProvider provider,
                      MemberRole role, MemberStatus status, boolean locked,
                      Instant createdAt, Instant lastLoginAt) {

        static Row of(Member m, boolean maskPhone) {
            return new Row(m.getId(), m.getEmail(), m.getName(),
                    maskPhone ? mask(m.getPhone()) : m.getPhone(),
                    m.getProvider(), m.getRole(), m.getStatus(), m.isLocked(Instant.now()),
                    m.getCreatedAt(), m.getLastLoginAt());
        }
    }

    public record Page(List<Row> items, int page, int totalPages, long totalElements) {

        static Page of(org.springframework.data.domain.Page<Member> page) {
            return new Page(page.map(m -> Row.of(m, true)).getContent(),
                    page.getNumber(), page.getTotalPages(), page.getTotalElements());
        }
    }

    public record Detail(Row member, int failedLoginAttempts, Instant lockedUntil,
                         Instant withdrawnAt, List<Log> logs) {

        static Detail of(AdminMemberService.Detail d) {
            Member m = d.member();
            List<Log> logs = d.logs().stream().map(l -> {
                Member actor = l.getActorId() == null ? null : d.actors().get(l.getActorId());
                String actorLabel = l.getActorId() == null ? "서버 명령"
                        : actor == null ? "알 수 없음" : actor.getName() + " (" + actor.getEmail() + ")";
                return new Log(l.getAction(), l.getDetail(), actorLabel, l.getCreatedAt());
            }).toList();
            return new Detail(Row.of(m, false), m.getFailedLoginAttempts(), m.getLockedUntil(),
                    m.getWithdrawnAt(), logs);
        }
    }

    public record Log(MemberAdminLog.Action action, String detail, String actor, Instant createdAt) {
    }

    /** 010-1234-5678 → 010-****-5678. 형식이 달라도 끝 4자리만 남긴다. */
    static String mask(String phone) {
        if (phone == null || phone.length() <= 4) {
            return phone;
        }
        String tail = phone.substring(phone.length() - 4);
        String head = phone.substring(0, phone.length() - 4).replaceAll("[0-9]", "*");
        if (phone.startsWith("0") && phone.length() > 7) {
            head = phone.substring(0, 3) + head.substring(3);
        }
        return head + tail;
    }
}