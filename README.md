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

**AWS Lightsail 서버 한 대**(서울, Ubuntu 24.04, 메모리 4 GB)에 Docker 로 올린다. 구성은 `docker-compose.prod.yml` 하나다:

```
손님 ─ 443 ─► Caddy ─┬─► 손님 화면(정적 파일) ◄── front(빌더): FE 저장소를 next build
                     └─► API(8080, 내부) ─► PostgreSQL(내부)
```

- 인터넷에 열리는 건 Caddy 의 80 · 443 뿐이다. HTTPS 인증서는 Caddy 가 자동으로 받는다.
- 손님 화면(FE)은 정적 파일이다. **같은 서버의 빌더(`front`)** 가 FE 저장소를 빌드해 Caddy 에 넘긴다.
  관리자가 상품을 공개 · 수정하면 API 가 빌더를 불러 다시 빌드한다(30초 모아서 한 번, 반영 1~3분).
  전에는 Vercel 배포 훅이 이 일을 했다 — Vercel 은 오픈 전 미리보기로만 남는다.
- 서버 폴더: `/opt/leoneferito/BE` · `/opt/leoneferito/FE` (빌더는 BE 옆의 `../FE` 를 읽는다).

### 도메인 — 카페24 DNS 에 레코드만 추가

`leoneferito.kr` 은 카페24 에서 산 도메인이다. **네임서버는 바꾸지 않는다**(바꾸는 순간 기존 카페24 몰이 내려간다).
카페24 → 나의서비스관리 → 도메인 관리 → DNS 관리에서 A 레코드만 추가한다. 값은 Lightsail 고정 IP.

| 호스트 | 언제 | 무엇이 되나 |
|---|---|---|
| `api` | 지금 | `api.leoneferito.kr` — API |
| `new` | 지금 | `new.leoneferito.kr` — 오픈 전 미리보기(검색 차단) |
| `@`(루트) · `www` | **오픈 날** | 정식 주소. 기존 카페24 몰에서 이 서버로 넘어온다 |

로그인 쿠키는 화면과 API 가 같은 상위 도메인(`leoneferito.kr`)이라 붙는다. `*.vercel.app` 에서는 붙지 않는다 —
이건 설정이 아니라 설계다 (`SecurityConfig` 주석).

### 처음 올리기

**1. Lightsail 콘솔 (브라우저)**
- 인스턴스: 서울, Linux/Unix → OS 전용 Ubuntu 24.04, 4 GB 플랜, 자동 스냅샷 켜기.
- 네트워킹 → **고정 IP** 만들어 인스턴스에 붙이기.
- 인스턴스 → 네트워킹 → IPv4 방화벽: **HTTPS(443)** 추가 (22 · 80 은 기본으로 열려 있다).
- 카페24 DNS 에 `api` · `new` A 레코드 = 고정 IP (위 표).

**2. 서버 첫 설정** — Lightsail 의 브라우저 SSH 로 접속해서:

```bash
# deploy/setup-server.sh 내용을 붙여 넣어 파일을 만들거나, 저장소를 받은 뒤 실행한다
bash setup-server.sh        # 시간대 · 스왑 2GB · Docker · 자동 보안 업데이트 · /opt/leoneferito
exit                        # 다시 접속해야 docker 를 sudo 없이 쓴다
```

**3. 저장소 받기** — 비공개 저장소라 서버용 읽기 전용 키(GitHub Deploy key)를 저장소마다 하나씩 단다:

```bash
ssh-keygen -t ed25519 -f ~/.ssh/lf_be -N "" && ssh-keygen -t ed25519 -f ~/.ssh/lf_fe -N ""
cat ~/.ssh/lf_be.pub   # → GitHub BE 저장소 Settings → Deploy keys → Add (쓰기 권한 끔)
cat ~/.ssh/lf_fe.pub   # → GitHub FE 저장소 Settings → Deploy keys → Add (쓰기 권한 끔)
cat >> ~/.ssh/config <<'EOF'
Host github-be
  HostName github.com
  IdentityFile ~/.ssh/lf_be
Host github-fe
  HostName github.com
  IdentityFile ~/.ssh/lf_fe
EOF
cd /opt/leoneferito
git clone git@github-be:LEONEFERITO/BE.git
git clone git@github-fe:LEONEFERITO/FE.git
```

**4. 운영 값** — `BE/.env`:

```bash
cd /opt/leoneferito/BE
cp .env.example .env
nano .env      # "운영" 구간 주석을 풀고 채운다
```

꼭 채울 것: `SPRING_PROFILES_ACTIVE=prod` · `DB_*`(비밀번호는 `openssl rand -base64 32`) · `API_DOMAIN=api.leoneferito.kr` ·
`SITE_DOMAIN=new.leoneferito.kr` · `SITE_ADDRESSES=new.leoneferito.kr` · `FRONT_REBUILD_TOKEN`(`openssl rand -hex 24`) ·
`ORDER_SHIPPING_FEE_KRW`(비면 주문을 안 받는다) · 결제(`NICEPAY_CLIENT_ID` · `NICEPAY_SECRET_KEY` · `NICEPAY_API_BASE`) · 간편 로그인 · 메일 키.
`MEDIA_BASE_URL` · `FRONT_BASE_URL` · `CORS_ALLOWED_ORIGINS` · `FRONT_DEPLOY_HOOK_URL` 은 **적지 않는다** — compose 가 도메인으로 만든다.

**5. 띄우기**

```bash
docker compose -f docker-compose.prod.yml up -d --build      # 처음엔 이미지 빌드로 몇 분
docker compose -f docker-compose.prod.yml ps                 # api 가 healthy 가 될 때까지 1분쯤
docker compose -f docker-compose.prod.yml logs -f front      # "빌드 완료 → 릴리스 …" 가 나오면 화면 준비 끝
curl https://api.leoneferito.kr/actuator/health              # {"status":"UP"}
```

브라우저로 `https://new.leoneferito.kr` 을 연다. Flyway 가 기동 때 스키마(V1~)를 만든다. 따로 SQL 을 돌리지 않는다.

**6. 첫 관리자** — 서버 명령으로 만든다. 찍힌 링크를 30분 안에 열어 비밀번호를 정한다:

```bash
docker compose -f docker-compose.prod.yml run --rm api \
  create-admin --email=ops@example.com --name=운영자 --role=SUPER_ADMIN
```

**7. 간편 로그인 · 결제** — 카카오 · 네이버 개발자 콘솔에 운영 Redirect URI 를 추가한다:
`https://api.leoneferito.kr/login/oauth2/code/kakao` · `…/naver`. 사이트 도메인(카카오 › 플랫폼 › Web · 네이버 서비스 URL)에는
`https://new.leoneferito.kr` 을 넣는다. 키를 `.env` 에 넣고 `docker compose -f docker-compose.prod.yml up -d api` — 버튼은 화면을 다시 빌드하지 않아도 나온다.

결제는 **나이스페이**(2026-10-12, 포스타트로 시작). 대표님이 상점을 만들고 개발자를 멤버로 초대하면
start.nicepay.co.kr › 개발정보에서 키를 확인해 `.env` 에 넣는다. 테스트 상점 키 + `NICEPAY_API_BASE=https://sandbox-api.nicepay.co.kr`
로 끝까지 시험한 뒤, 심사가 끝나면 운영 상점 키 + `https://api.nicepay.co.kr` 로 바꾼다. 개발정보 › 웹훅에
`https://api.leoneferito.kr/api/payments/nicepay/webhook` 을 등록한다. 일반 계약으로 옮길 때도 이 키만 바꾼다(같은 API).
대표님용 신청 가이드: `.github/docs/guides/LEONEFERITO_나이스페이_포스타트_신청_가이드_대표님.pdf`

**8. 백업 예약**

```bash
crontab -e
# 매일 04:30 — DB 덤프 + 업로드 이미지, 14일 보관
30 4 * * * cd /opt/leoneferito/BE && bash deploy/backup.sh >> /opt/leoneferito/backups/backup.log 2>&1
```

### 오픈하는 날

1. 카페24 DNS: `@`(루트) · `www` A 레코드 값을 Lightsail 고정 IP 로 바꾼다.
2. `.env`: `SITE_DOMAIN=leoneferito.kr` · `SITE_ADDRESSES=leoneferito.kr, www.leoneferito.kr` · `SITE_ROBOTS=all`
3. FE `public/robots.txt` 를 허용으로 바꿔 푸시한다 (오픈 전에는 전부 막혀 있다).
4. 적용: `bash deploy/update.sh` — 컨테이너가 새 도메인으로 다시 뜨고 손님 화면이 다시 빌드된다.
5. 카카오 · 네이버 Redirect URI 는 API 주소라 바꿀 것이 없다. 결제사에 등록한 사이트 주소는 정식 주소로 바꾼다.
6. 며칠 지켜본 뒤 `deploy/Caddyfile` 의 `Strict-Transport-Security` 주석을 푼다.
7. 기존 카페24 쇼핑몰은 새 사이트가 뜬 것을 확인한 뒤 해지한다. **도메인은 해지하지 않는다.**

### 업데이트

```bash
cd /opt/leoneferito/BE && bash deploy/update.sh
```

BE · FE 를 받고, 바뀐 컨테이너만 다시 만들고, 손님 화면을 다시 빌드한다. 새 API 컨테이너가 healthy 가 될 때까지
옛 것이 받다가 교체된다. 스키마 변경은 새 컨테이너가 기동하며 Flyway 로 적용한다 — **되돌리는 마이그레이션은 없다.**
잘못됐으면 코드를 고쳐 다음 버전으로 앞으로 간다.

손님 화면만 다시 만들 때: `bash deploy/rebuild-front.sh`. 빌드가 실패하면 옛 화면이 그대로 남는다(최근 3개 릴리스 보관).

### 백업

`deploy/backup.sh` — DB 덤프와 업로드 이미지(`leoneferito-prod-media` 볼륨)를 `/opt/leoneferito/backups` 에, 14일 보관.
서버 자체가 사라지는 경우는 **Lightsail 자동 스냅샷**(매일)이 막는다. 둘 다 켜 둔다.

복구: `docker exec -i leoneferito-db sh -c 'pg_restore -U "$POSTGRES_USER" -d "$POSTGRES_DB" --clean' < backup.dump`

### 로그 · 상태

```bash
docker compose -f docker-compose.prod.yml ps
docker compose -f docker-compose.prod.yml logs -f api           # API
docker compose -f docker-compose.prod.yml logs -f front         # 손님 화면 빌드
docker compose -f docker-compose.prod.yml logs -f caddy         # 접속 · 인증서
curl -s https://api.leoneferito.kr/actuator/health/readiness
```

로그에 자격증명이 찍히지 않는다 — 기본 계정 자동 생성을 꺼 두었고(`application.yml`
`autoconfigure.exclude`), 오류 응답에 스택트레이스를 넣지 않는다. 빌더 훅 토큰도 로그에 찍지 않는다.

### 아직 안 한 것

- **알림**: 죽으면 `restart: unless-stopped` 가 다시 띄우지만 알려주지는 않는다. Lightsail 지표 경보(CPU · 상태 확인)와
  외부 업타임 체크(`/actuator/health`) 하나를 건다.
- **서버 밖 백업**: 백업 파일이 서버 안에 있다. 오픈 뒤 S3 로 한 벌 더 보낸다.
- **객체 스토리지(D7)**: 업로드 이미지가 서버 볼륨에 있다. 서버를 두 대로 늘리면 S3 계열로 옮겨야 한다 (`MediaStorage` 가 그 경계다).
- **CI 배포**: CI 는 빌드 · 테스트 · 이미지 빌드까지만 한다. 서버 배포는 `deploy/update.sh` 를 손으로 친다.

## 환경변수

| 키 | 어디서 | 뜻 |
|---|---|---|
| `SPRING_PROFILES_ACTIVE` | prod | `prod`. 없으면 `local`(로컬 DB 를 찾다가 실패) |
| `DB_HOST` `DB_PORT` `DB_NAME` `DB_USERNAME` `DB_PASSWORD` | 둘 다 | prod 에는 기본값이 없다 — 비면 기동 실패 |
| `DB_POOL_MAX` | prod | 커넥션 풀 상한 (기본 10) |
| `API_DOMAIN` | compose | API 주소 (`api.leoneferito.kr`). Caddy 가 인증서를 받는다 |
| `SITE_DOMAIN` · `SITE_ADDRESSES` | compose | 손님 화면 대표 주소 · 받을 주소들 (오픈 전 `new.leoneferito.kr`) |
| `SITE_ROBOTS` | compose | 검색 노출 헤더 (오픈 전 `noindex, nofollow`, 오픈 날 `all`) |
| `FRONT_REBUILD_TOKEN` | compose | API 가 빌더를 부를 때 쓰는 비밀 값 (16자 이상) |
| `CORS_ALLOWED_ORIGINS` | 로컬 | 프론트 출처 (기본 `http://localhost:3000`). 운영은 compose 가 `SITE_DOMAIN` 으로 만든다 |
| `MEDIA_BASE_URL` | 로컬 | 업로드 이미지 공개 주소 (기본 `/media`). 운영은 compose 가 `API_DOMAIN` 으로 만든다 |
| `MEDIA_STORAGE_DIR` | 둘 다 | 업로드 저장 폴더 (이미지 안에서는 `/var/lib/leoneferito/media`) |
| `SERVER_PORT` | 둘 다 | 기본 8080 |

비밀값은 `.env` 에만 둔다. 커밋 전 `scripts/check-secrets.sh` 가 훑는다.
