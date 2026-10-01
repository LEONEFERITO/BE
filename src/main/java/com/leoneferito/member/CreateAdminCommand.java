package com.leoneferito.member;

import com.leoneferito.auth.PasswordResetService;
import java.util.List;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 관리자 생성 명령.
 *
 * <pre>
 *   # 운영 서버
 *   docker compose -f docker-compose.prod.yml run --rm api create-admin --email=ops@example.com --name=운영자 --role=SUPER_ADMIN
 *   # 로컬
 *   ./gradlew bootRun --args="create-admin --email=me@example.com --name=정재윤 --role=SUPER_ADMIN"
 *   # 로컬 전용 — 아이디 로그인 + 임시 비밀번호 (첫 로그인 뒤 바꿔야 관리자 화면이 열린다)
 *   ./gradlew bootRun --args="create-admin --login-id=masteradmin --temp-password=… --role=SUPER_ADMIN --server.port=0"
 * </pre>
 *
 * <p>임시 비밀번호는 <b>local 프로필에서만</b> 받는다. 운영에서 약한 비밀번호 관리자가 생기는 길을 막는다 —
 * 운영 관리자는 위의 이메일 방식(비밀번호 설정 링크)으로 만든다.
 *
 * 없는 이메일이면 계정을 만들고 비밀번호 설정 링크(30분, 한 번)를 찍는다.
 * 이미 있는 계정이면 역할만 올린다. 이 명령이 SUPER_ADMIN 을 만드는 유일한 경로다.
 */
@Component
public class CreateAdminCommand implements ApplicationRunner {

    public static final String COMMAND = "create-admin";

    private final AdminMemberService adminMembers;
    private final PasswordResetService passwordReset;
    private final org.springframework.core.env.Environment environment;

    public CreateAdminCommand(AdminMemberService adminMembers, PasswordResetService passwordReset,
                              org.springframework.core.env.Environment environment) {
        this.adminMembers = adminMembers;
        this.passwordReset = passwordReset;
        this.environment = environment;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!args.getNonOptionArgs().contains(COMMAND)) {
            return;
        }
        String role = required(args, "role");
        if (args.containsOption("login-id")) {
            createLocalLoginIdAdmin(args, role);
            return;
        }
        String email = required(args, "email");
        String name = args.containsOption("name") ? required(args, "name") : "관리자";

        AdminMemberService.CommandResult result =
                adminMembers.createOrPromoteByCommand(email, name, MemberRole.valueOf(role));

        // 로그가 아니라 표준 출력이다. 로그는 수집·보관되지만 이 링크는 이 터미널에만 남아야 한다.
        if (result.created()) {
            System.out.println();
            System.out.println("관리자 계정을 만들었습니다 (" + role + ").");
            System.out.println("아래 링크에서 비밀번호를 정해 주세요. 30분 동안 한 번만 쓸 수 있습니다:");
            System.out.println(passwordReset.issueLink(result.member()));
            System.out.println();
        } else {
            System.out.println();
            System.out.println("기존 계정의 역할을 " + role + " 로 바꿨습니다. 비밀번호는 그대로입니다.");
            System.out.println("로그인돼 있던 세션은 끊었습니다. 다시 로그인하면 관리자 화면이 열립니다.");
            System.out.println();
        }
    }

    private void createLocalLoginIdAdmin(ApplicationArguments args, String role) {
        if (!environment.matchesProfiles("local")) {
            throw new IllegalStateException("--login-id · --temp-password 는 local 프로필에서만 쓸 수 있습니다.");
        }
        String loginId = required(args, "login-id");
        String temporary = required(args, "temp-password");
        String email = args.containsOption("email") ? required(args, "email") : null;
        String name = args.containsOption("name") ? required(args, "name") : "마스터 관리자";
        AdminMemberService.CommandResult result =
                adminMembers.createWithLoginId(loginId, email, name, MemberRole.valueOf(role), temporary);
        System.out.println();
        System.out.println((result.created() ? "관리자 계정을 만들었습니다" : "기존 계정을 바꿨습니다")
                + " (아이디 " + result.member().getLoginId() + ", " + role + ").");
        System.out.println("/admin/login 에서 로그인한 뒤 비밀번호를 바꿔야 관리자 화면이 열립니다.");
        System.out.println();
    }

    private static String required(ApplicationArguments args, String name) {
        List<String> values = args.getOptionValues(name);
        if (values == null || values.isEmpty() || values.getFirst().isBlank()) {
            throw new IllegalArgumentException("--" + name + " 값이 필요합니다.");
        }
        return values.getFirst().trim();
    }
}