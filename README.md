# LEONEFERITO API

THE MANLY 기성복 브랜드 LEONEFERITO 의 백엔드. Spring Boot 4.1 · Java 21 · PostgreSQL 18.

- 공개 API: 상품 목록·상세 (`/api/products`)
- 회원: 가입·로그인·세션 (`/api/auth`) — 세션 쿠키, CSRF 토큰
- 관리자: 상품 등록·수정·공개, 이미지 업로드 (`/api/admin/**`, ADMIN 권한)

## 로컬 실행

```bash
docker compose up -d           # PostgreSQL 18
./gradlew bootRun              # 기본 프로필 local, http://localhost:8080
./gradlew test                 # Testcontainers 가 PostgreSQL 을 띄운다 (Docker 필요)
```

포트 5432 를 다른 것이 쓰고 있으면 `DB_PORT=5442 docker compose up -d` 처럼 바꾸고
`DB_PORT=5442 ./gradlew bootRun` 으로 맞춘다.

관리자 만들기: 화면(`/signup`)에서 가입한 뒤 권한만 올린다.

```bash
docker exec leoneferito-db psql -U leoneferito -d leoneferito \
  -c "UPDATE member SET role='ADMIN' WHERE email='you@example.com';"
```

다시 로그인하면 관리자 화면(`/admin/products`)이 열린다.

## 배포

VM 한 대에 Docker 로 올린다. 구성은 `docker-compose.prod.yml` 하나다:
**Caddy(80/443, HTTPS 자동) → API(8080, 내부) → PostgreSQL(내부)**.

### 미리 정해져야 하는 것

| 항목 | 예 | 상태 |
|---|---|---|
| API 도메인 | `api.leoneferito.com` | TODO(고객확인) |
| 프론트 도메인 | `leoneferito.com` | TODO(고객확인) — Vercel 에 연결 |
| 서버 | Ubuntu 22.04+, 2 vCPU / 4 GB, Docker 설치 | 미정 |

프론트와 API 가 **같은 상위 도메인**이어야 로그인이 된다. 세션 쿠키가 `SameSite=Lax` 라
`leoneferito.com` 에서 `api.leoneferito.com` 으로는 붙지만, `*.vercel.app` 에서는 붙지 않는다.
이건 설정이 아니라 설계다 (`SecurityConfig` 주석).

### 처음 올리기

```bash
# 1. 서버에서
git clone https://github.com/LEONEFERITO/BE.git && cd BE
cp .env.example .env
nano .env        # "운영" 구간의 주석을 풀고 값을 채운다. DB_PASSWORD 는 openssl rand -base64 32

# 2. DNS: API_DOMAIN 의 A 레코드 → 이 서버 IP. 방화벽 80, 443 열기.

# 3. 띄우기 (이미지 빌드 포함, 처음엔 몇 분)
docker compose -f docker-compose.prod.yml up -d --build

# 4. 확인
docker compose -f docker-compose.prod.yml ps          # api 가 healthy 가 될 때까지 1분쯤
curl https://api.leoneferito.com/actuator/health      # {"status":"UP"}
```

Flyway 가 기동 때 스키마(V1~)를 만든다. 따로 SQL 을 돌리지 않는다.

관리자 계정은 로컬과 같다 — 프론트에서 가입한 뒤 DB 에서 권한을 올린다:

```bash
docker exec leoneferito-db psql -U "$DB_USERNAME" -d "$DB_NAME" \
  -c "UPDATE member SET role='ADMIN' WHERE email='...';"
```

### 업데이트

```bash
git pull
docker compose -f docker-compose.prod.yml up -d --build api
```

새 컨테이너가 뜨고 readiness 가 UP 이 될 때까지 옛 것이 받다가 교체된다. 스키마 변경이
있으면 새 컨테이너가 기동하며 Flyway 로 적용한다 — **되돌리는 마이그레이션은 없다.**
잘못됐으면 코드를 고쳐 다음 버전(V9…)으로 앞으로 간다.

### 백업

업로드 이미지(`leoneferito-prod-media` 볼륨)와 DB 두 가지다. 매일 한 번, 서버 밖으로.

```bash
# DB
docker exec leoneferito-db pg_dump -U "$DB_USERNAME" -Fc "$DB_NAME" > backup-$(date +%F).dump
# 이미지
docker run --rm -v leoneferito-prod-media:/m -v "$PWD":/out alpine tar czf /out/media-$(date +%F).tgz -C /m .
```

복구는 `pg_restore -U "$DB_USERNAME" -d "$DB_NAME" --clean backup.dump`.

### 로그 · 상태

```bash
docker compose -f docker-compose.prod.yml logs -f api
docker compose -f docker-compose.prod.yml ps
curl -s https://api.leoneferito.com/actuator/health/readiness
```

로그에 자격증명이 찍히지 않는다 — 기본 계정 자동 생성을 꺼 두었고(`application.yml`
`autoconfigure.exclude`), 오류 응답에 스택트레이스를 넣지 않는다.

### 아직 안 한 것

- **객체 스토리지(D7)**: 업로드 이미지가 서버 볼륨에 있다. 서버를 옮기거나 두 대로 늘리면
  S3 계열로 옮겨야 한다 (`MediaStorage` 가 그 경계다).
- **모니터링·알림**: 죽으면 `restart: unless-stopped` 가 다시 띄우지만 알려주지는 않는다.
  외부 업타임 체크(health 주소)를 하나 걸어 두면 된다.
- **CI 배포**: CI 는 빌드·테스트·이미지 빌드까지만 한다. 서버 배포는 위 명령을 손으로 친다.
  자동화는 서버와 도메인이 정해진 뒤에.

## 환경변수

| 키 | 어디서 | 뜻 |
|---|---|---|
| `SPRING_PROFILES_ACTIVE` | prod | `prod`. 없으면 `local`(로컬 DB 를 찾다가 실패) |
| `DB_HOST` `DB_PORT` `DB_NAME` `DB_USERNAME` `DB_PASSWORD` | 둘 다 | prod 에는 기본값이 없다 — 비면 기동 실패 |
| `DB_POOL_MAX` | prod | 커넥션 풀 상한 (기본 10) |
| `API_DOMAIN` | compose | Caddy 가 인증서를 받을 도메인 |
| `CORS_ALLOWED_ORIGINS` | 둘 다 | 프론트 출처, 쉼표 구분 (기본 `http://localhost:3000`) |
| `MEDIA_BASE_URL` | 둘 다 | 업로드 이미지 공개 주소 (기본 `/media`) |
| `MEDIA_STORAGE_DIR` | 둘 다 | 업로드 저장 폴더 (이미지 안에서는 `/var/lib/leoneferito/media`) |
| `SERVER_PORT` | 둘 다 | 기본 8080 |

비밀값은 `.env` 에만 둔다. 커밋 전 `scripts/check-secrets.sh` 가 훑는다.
