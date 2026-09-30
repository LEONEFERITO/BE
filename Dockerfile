# syntax=docker/dockerfile:1.7
# ─────────────────────────────────────────────────────────────
# LEONEFERITO API 이미지.
#
#   docker build -t leoneferito-api .
#   docker run --rm -p 8080:8080 --env-file .env leoneferito-api
#
# 두 단계다. 1단계에서 Gradle 로 jar 를 만들고, 2단계에는 **JRE 와 jar 만** 남긴다.
# JDK·Gradle·소스가 운영 이미지에 들어가면 이미지가 커지고 공격면도 넓어진다.
#
# 테스트는 여기서 돌리지 않는다(-x test). Testcontainers 가 Docker 를 필요로 해서
# 이미지 빌드 안에서는 돌 수 없고, 테스트는 CI 가 별도로 돈다.
# ─────────────────────────────────────────────────────────────

FROM eclipse-temurin:21-jdk AS build
WORKDIR /src

# 의존성 목록이 바뀌지 않으면 이 층은 캐시에서 온다. 소스만 바꿨을 때 매번 다시 받지 않는다.
COPY gradlew build.gradle settings.gradle ./
COPY gradle ./gradle
# Windows 에서 커밋된 gradlew 는 CRLF 이거나 실행 권한이 없을 수 있다.
RUN sed -i 's/\r$//' gradlew && chmod +x gradlew
RUN --mount=type=cache,target=/root/.gradle ./gradlew --no-daemon dependencies --quiet || true

COPY src ./src
RUN --mount=type=cache,target=/root/.gradle ./gradlew --no-daemon bootJar -x test

# 실행 가능 jar 를 "얇은 jar + lib/" 로 풀어 둔다. 의존성(lib/)이 소스보다 훨씬 덜 바뀌므로
# 층을 나누면 배포마다 옮기는 크기가 수 MB 로 준다.
RUN java -Djarmode=tools -jar build/libs/app.jar extract --destination /out


FROM eclipse-temurin:21-jre-alpine AS runtime

# root 로 띄우지 않는다. 컨테이너가 뚫려도 파일시스템의 나머지는 못 건드린다.
RUN addgroup -S app && adduser -S -G app app \
 && mkdir -p /var/lib/leoneferito/media \
 && chown -R app:app /var/lib/leoneferito

WORKDIR /app
COPY --from=build --chown=app:app /out/lib/ ./lib/
COPY --from=build --chown=app:app /out/app.jar ./app.jar

USER app

# 업로드 이미지가 쌓이는 곳. 컨테이너를 갈아끼워도 남아야 하므로 볼륨으로 잡는다.
VOLUME /var/lib/leoneferito/media

ENV SPRING_PROFILES_ACTIVE=prod \
    SERVER_PORT=8080 \
    MEDIA_STORAGE_DIR=/var/lib/leoneferito/media \
    TZ=Asia/Seoul \
    # 컨테이너 메모리의 75% 까지만 힙으로. 나머지는 메타스페이스·스레드·OS 몫이다.
    # OOM 이 나면 살려 두지 않고 죽인다 — 죽으면 오케스트레이터가 다시 띄운다.
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError -Djava.security.egd=file:/dev/./urandom"

EXPOSE 8080

# readiness: DB 까지 붙어야 UP 이다. 기동 중(start-period)에는 실패로 치지 않는다.
HEALTHCHECK --interval=15s --timeout=3s --start-period=60s --retries=3 \
  CMD wget -qO- http://127.0.0.1:8080/actuator/health/readiness | grep -q '"UP"' || exit 1

ENTRYPOINT ["java", "-jar", "app.jar"]
