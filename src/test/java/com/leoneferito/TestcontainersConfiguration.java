package com.leoneferito;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {

	/**
	 * 테스트용 PostgreSQL 컨테이너.
	 *
	 * <p>이미지 태그는 {@code docker-compose.yml} 과 <b>반드시 같아야 한다.</b>
	 * Initializr 기본값인 {@code postgres:latest} 를 쓰면 테스트는 최신 메이저에서,
	 * 로컬 개발은 18 에서 돌아가다가 방언 차이로 "테스트는 통과했는데 로컬에선 깨지는" 상황이 생긴다.
	 *
	 * <p>{@code @ServiceConnection} 이 컨테이너의 주소·계정을 datasource 속성으로 자동 주입하므로
	 * 테스트에서 접속 정보를 따로 적을 필요가 없다.
	 */
	@Bean
	@ServiceConnection
	PostgreSQLContainer postgresContainer() {
		return new PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"));
	}

}
