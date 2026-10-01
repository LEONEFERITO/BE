# LEONEFERITO API

THE MANLY 기성복 브랜드 LEONEFERITO 의 백엔드. Spring Boot 4.1 · Java 21 · PostgreSQL 18.

- 공개 API: 상품 목록·상세 (`/api/products`)
- 회원: 가입·로그인·세션·간편로그인·비밀번호 찾기 (`/api/auth`) — 세션 쿠키(DB 저장), CSRF 토큰
- 내 정보: 수정·비밀번호 변경·탈퇴 (`/api/me`)
- 관리자: 상품 등록·수정·공개, 이미지 업로드, 회원 관리 (`/api/admin/**`, ADMIN 권한)
- 관리자 지정·해제 (`PUT /api/admin/members/{id}/role`, SUPER_ADMIN 권한)

## 로컬 실행

```bash
docker compose up -d           # PostgreSQL 18 + Mailpit(메일 받는 곳)
./gradlew bootRun              # 기본 프로필 local, http://localhost:8080
./gradlew test                 # Testcontainers 가 PostgreSQL 을 띄운다 (Docker 필요)
```

포트 5432 를 다른 것이 쓰고 있으면 `DB_PORT=5442 docker compose up -d` 처럼 바꾸고
`DB_PORT=5442 ./gradlew bootRun` 으로 맞춘다.

비밀번호 찾기 메일은 실제로 나가지 않고 Mailpit 에 쌓인다: http://localhost:8025

관리자 만들기 — 서버 명령 `create-admin`:

```bash
./gradlew bootRun --args="create-admin --email=you@example.com --name=이름 --role=SUPER_ADMIN"
```

- 없는 이메일이면 계정을 만들고 **비밀번호 설정 링크**(30분, 한 번)를 터미널에 찍는다.
  명령이 비밀번호를 정하지 않는다 — 본인이 링크에서 정한다.
- 이미 있는 계정이면 역할만 바꾼다(비밀번호는 그대로). 그 사람의 로그인 세션은 끊긴다.
- `SUPER_ADMIN`(최고 관리자)은 **이 명령으로만** 만든다. 일반 관리자(`ADMIN`)는
  최고 관리자가 회원 관리 화면(`/admin/members`)에서 지정한다.
- 모든 생성·변경은 `member_admin_log` 에 남는다.

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

첫 관리자(최고 관리자)는 서버 명령으로 만든다. 찍힌 링크를 30분 안에 열어 비밀번호를 정한다:

```bash
docker compose -f docker-compose.prod.yml run --rm api \
  create-admin --email=ops@example.com --name=운영자 --role=SUPER_ADMIN
```

**손님 화면 연결 (중요)** — 프론트는 상품을 **빌드할 때** 이 API 에서 받는다. 그래서:

1. Vercel 프로젝트 환경변수 `NEXT_PUBLIC_API_BASE=https://api.leoneferito.com` (API 가 먼저 떠 있어야 빌드된다.
   API 가 응답하지 않으면 빌드가 **실패**하고 옛 배포가 그대로 남는다 — 가짜 상품이 올라가는 일은 없다).
2. Vercel → Settings → Git → Deploy Hooks 에서 훅을 만들고 `.env` 의 `FRONT_DEPLOY_HOOK_URL` 에 넣는다.
   관리자가 상품을 공개·비공개·수정하면 30초 모아서 한 번 다시 빌드한다 (손님 화면 반영 1~2분).

메일(비밀번호 찾기)을 쓰려면 `.env` 에 `SPRING_MAIL_*` 를 채운다 (`.env.example` 참고).
비어 있으면 메일만 안 나가고 사이트는 정상이다.

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
