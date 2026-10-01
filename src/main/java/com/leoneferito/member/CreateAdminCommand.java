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
 * </pre>
 *
 * 없는 이메일이면 계정을 만들고 비밀번호 설정 링크(30분, 한 번)를 찍는다.
 * 이미 있는 계정이면 역할만 올린다. 이 명령이 SUPER_ADMIN 을 만드는 유일한 경로다.
 */
@Component
public class CreateAdminCommand implements ApplicationRunner {

    public static final String COMMAND = "create-admin";

    private final AdminMemberService adminMembers;
    private final PasswordResetService passwordReset;

    public CreateAdminCommand(AdminMemberService adminMembers, PasswordResetService passwordReset) {
        this.adminMembers = adminMembers;
        this.passwordReset = passwordReset;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!args.getNonOptionArgs().contains(COMMAND)) {
            return;
        }
        String email = required(args, "email");
        String role = required(args, "role");
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

    private static String required(ApplicationArguments args, String name) {
        List<String> values = args.getOptionValues(name);
        if (values == null || values.isEmpty() || values.getFirst().isBlank()) {
            throw new IllegalArgumentException("--" + name + " 값이 필요합니다.");
        }
        return values.getFirst().trim();
    }
}