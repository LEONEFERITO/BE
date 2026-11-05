package com.leoneferito;

import com.leoneferito.member.CreateAdminCommand;
import java.util.Arrays;
import java.util.stream.Stream;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;

@SpringBootApplication
// 매일 정리 작업(RetentionCleanup — 미첨부 반품 사진 · 지난 재설정 링크 · 오래된 관리자 접속 기록)
@org.springframework.scheduling.annotation.EnableScheduling
public class LeoneferitoApiApplication {

	public static void main(String[] args) {
		if (Arrays.asList(args).contains(CreateAdminCommand.COMMAND)) {
			/*
			 * 관리자 생성 명령. 앱 전체(세션 저장소·메일 포함)가 떠야 명령이 동작하므로 웹 서버까지 띄우되,
			 * 임의 포트(0)로 띄워서 이미 돌고 있는 서버와 부딪히지 않게 한다. 명령이 끝나면 내린다.
			 */
			// 개발 중(devtools)에는 main 이 이 인자 그대로 한 번 더 불린다. 두 번 붙이면 '0,0' 이 되어 기동이 실패한다.
			String[] withRandomPort = Arrays.asList(args).contains("--server.port=0")
					? args
					: Stream.concat(Arrays.stream(args), Stream.of("--server.port=0")).toArray(String[]::new);
			ConfigurableApplicationContext context =
					SpringApplication.run(LeoneferitoApiApplication.class, withRandomPort);
			System.exit(SpringApplication.exit(context));
		}
		SpringApplication.run(LeoneferitoApiApplication.class, args);
	}

}
