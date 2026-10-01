package com.leoneferito.member;

import com.leoneferito.auth.SessionTerminator;
import com.leoneferito.common.error.ResourceNotFoundException;
import com.leoneferito.member.MemberAdminLog.Action;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 관리자 회원 관리.
 *
 * <ul>
 *   <li>ADMIN — 검색 · 상세 · 잠금 해제 · 이용 정지/해제 (일반 회원만)</li>
 *   <li>SUPER_ADMIN — 위 전부 + 관리자 지정·해제</li>
 *   <li>SUPER_ADMIN 은 서버 명령으로만 만든다. 화면에서는 누구도 만들거나 바꾸지 못한다.</li>
 *   <li>자기 자신에게는 아무것도 못 한다.</li>
 * </ul>
 * 행위자의 권한은 세션이 아니라 DB 에서 다시 읽는다. 모든 조치는 MemberAdminLog 에 남는다.
 */
@Service
public class AdminMemberService {

    private static final Logger log = LoggerFactory.getLogger(AdminMemberService.class);

    static final int PAGE_SIZE = 20;

    /**
     * 숫자가 없는 검색어일 때 전화번호 조건이 아무것도 걸지 않게 하는 값.
     * 하이픈을 뺀 전화번호는 숫자뿐이라 이 글자와 같을 수 없다. (NUL 문자는 PostgreSQL 이 거부한다)
     */
    private static final String MATCH_NOTHING = "#";

    private final MemberRepository members;
    private final MemberAdminLogRepository logs;
    private final SessionTerminator sessionTerminator;
    private final PasswordEncoder passwordEncoder;

    public AdminMemberService(MemberRepository members, MemberAdminLogRepository logs,
                              SessionTerminator sessionTerminator, PasswordEncoder passwordEncoder) {
        this.members = members;
        this.logs = logs;
        this.sessionTerminator = sessionTerminator;
        this.passwordEncoder = passwordEncoder;
    }

    /** 검색. status 가 없으면 탈퇴 회원을 뺀 전부. */
    @Transactional(readOnly = true)
    public Page<Member> search(String query, MemberStatus status, int page) {
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        String pattern = "%" + q.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
        String digits = q.replaceAll("[^0-9]", "");
        String digitPattern = digits.isEmpty() ? MATCH_NOTHING : "%" + digits + "%";

        Set<MemberStatus> statuses = status == null
                ? Set.of(MemberStatus.ACTIVE, MemberStatus.SUSPENDED)
                : Set.of(status);

        return members.search(pattern, digitPattern, statuses,
                PageRequest.of(Math.max(page, 0), PAGE_SIZE, Sort.by(Sort.Direction.DESC, "createdAt")));
    }

    /** 상세. 열어 본 것 자체를 기록한다 (개인정보 접속 기록). */
    @Transactional
    public Detail detail(UUID actorId, UUID memberId) {
        Member member = find(memberId);
        logs.save(new MemberAdminLog(memberId, actorId, Action.VIEWED, null));

        List<MemberAdminLog> recent = logs.findTop20ByMemberIdOrderByIdDesc(memberId);
        Set<UUID> actorIds = recent.stream()
                .map(MemberAdminLog::getActorId)
                .filter(id -> id != null)
                .collect(Collectors.toSet());
        Map<UUID, Member> actors = members.findAllById(actorIds).stream()
                .collect(Collectors.toMap(Member::getId, Function.identity()));
        return new Detail(member, recent, actors);
    }

    @Transactional
    public void unlock(UUID actorId, UUID memberId) {
        requireAdmin(actorId);
        Member member = findOther(actorId, memberId);
        member.unlock();
        record(member, actorId, Action.UNLOCKED, null);
    }

    /** 이용 정지. 일반 회원만. 관리자를 정지하려면 먼저 권한을 내린다. 세션도 끊는다. */
    @Transactional
    public void suspend(UUID actorId, UUID memberId, String reason) {
        requireAdmin(actorId);
        Member member = findOther(actorId, memberId);
        if (member.getRole().isAdmin()) {
            throw new MemberRuleException("관리자는 정지할 수 없습니다. 먼저 관리자 권한을 해제해 주세요.");
        }
        if (!member.isActive()) {
            throw new MemberRuleException("이미 정지되었거나 탈퇴한 회원입니다.");
        }
        member.suspend();
        sessionTerminator.terminateAll(member.getEmail());
        record(member, actorId, Action.SUSPENDED, reason.trim());
    }

    @Transactional
    public void reactivate(UUID actorId, UUID memberId) {
        requireAdmin(actorId);
        Member member = findOther(actorId, memberId);
        if (!member.isSuspended()) {
            throw new MemberRuleException("정지된 회원이 아닙니다.");
        }
        member.reactivate();
        record(member, actorId, Action.REACTIVATED, null);
    }

    /**
     * 관리자 지정 · 해제. SUPER_ADMIN 만.
     * 바꾸면 그 사람의 세션을 끊는다 — 내릴 때는 필수(세션에 옛 권한), 올릴 때도 끊는다(규칙 하나).
     */
    @Transactional
    public void changeRole(UUID actorId, UUID memberId, MemberRole newRole) {
        Member actor = requireAdmin(actorId);
        if (actor.getRole() != MemberRole.SUPER_ADMIN) {
            throw new MemberRuleException("관리자 지정은 최고 관리자만 할 수 있습니다.");
        }
        if (newRole == MemberRole.SUPER_ADMIN) {
            throw new MemberRuleException("최고 관리자는 서버 명령으로만 만들 수 있습니다.");
        }
        Member member = findOther(actorId, memberId);
        if (member.getRole() == MemberRole.SUPER_ADMIN) {
            throw new MemberRuleException("최고 관리자의 권한은 화면에서 바꿀 수 없습니다.");
        }
        if (!member.isActive()) {
            throw new MemberRuleException("정지되었거나 탈퇴한 회원입니다.");
        }
        if (member.getRole() == newRole) {
            return;
        }

        MemberRole before = member.getRole();
        member.changeRole(newRole);
        sessionTerminator.terminateAll(member.getEmail());
        record(member, actorId, Action.ROLE_CHANGED, before + " → " + newRole);
    }

    /**
     * 서버 명령(create-admin). 없으면 만들고, 있으면 역할만 올린다.
     * 새 계정의 비밀번호는 아무도 모르는 무작위 값이다 — 명령이 재설정 링크를 찍고 본인이 정한다.
     */
    @Transactional
    public CommandResult createOrPromoteByCommand(String rawEmail, String name, MemberRole role) {
        if (!role.isAdmin()) {
            throw new MemberRuleException("--role 은 ADMIN 또는 SUPER_ADMIN 이어야 합니다.");
        }
        String email = Member.normalizeEmail(rawEmail);
        Member existing = members.findByEmail(email).orElse(null);

        if (existing != null) {
            if (!existing.isActive()) {
                throw new MemberRuleException("정지되었거나 탈퇴한 계정입니다.");
            }
            MemberRole before = existing.getRole();
            existing.changeRole(role);
            sessionTerminator.terminateAll(existing.getEmail());
            record(existing, null, Action.ROLE_CHANGED, before + " → " + role + " (서버 명령)");
            return new CommandResult(existing, false);
        }

        Member created = new Member(UUID.randomUUID(), email,
                passwordEncoder.encode(UUID.randomUUID().toString()), name, null);
        created.changeRole(role);
        members.save(created);
        record(created, null, Action.CREATED_BY_COMMAND, role.name());
        return new CommandResult(created, true);
    }

    private Member find(UUID memberId) {
        return members.findById(memberId)
                .orElseThrow(() -> new ResourceNotFoundException("회원 없음 id=" + memberId));
    }

    private Member findOther(UUID actorId, UUID memberId) {
        if (memberId.equals(actorId)) {
            throw new MemberRuleException("자기 자신에게는 할 수 없습니다.");
        }
        return find(memberId);
    }

    /** 행위자가 지금도 활성 관리자인가. 세션이 아니라 DB 로 확인한다. */
    private Member requireAdmin(UUID actorId) {
        Member actor = members.findById(actorId)
                .filter(Member::isActive)
                .filter(m -> m.getRole().isAdmin())
                .orElseThrow(() -> new MemberRuleException("관리자 권한이 없습니다. 다시 로그인해 주세요."));
        return actor;
    }

    private void record(Member member, UUID actorId, Action action, String detail) {
        logs.save(new MemberAdminLog(member.getId(), actorId, action, detail));
        log.info("회원 관리 action={} memberId={} actorId={}", action, member.getId(), actorId);
    }

    public record Detail(Member member, List<MemberAdminLog> logs, Map<UUID, Member> actors) {
    }

    public record CommandResult(Member member, boolean created) {
    }

    /** 관리 규칙 위반. 메시지는 관리자 화면에 그대로 보인다 (관리자는 신뢰 경계 안). */
    public static class MemberRuleException extends RuntimeException {
        public MemberRuleException(String userFacingMessage) {
            super(userFacingMessage);
        }
    }
}