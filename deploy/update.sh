#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────
# 코드 업데이트 — 서버에서 BE 폴더 안에서 돌린다.   bash deploy/update.sh
#
#   1. BE · FE 저장소를 최신으로 받는다
#   2. 바뀐 컨테이너만 다시 만든다 (API 는 새 것이 healthy 가 된 뒤 교체된다)
#      Caddyfile 이 바뀌었으면 Caddy 를 다시 시작한다 — 파일 하나를 붙여 둔(bind) 것이라 git 이 파일을
#      새로 쓰면 돌고 있는 Caddy 는 옛 파일을 계속 본다. 다시 시작해야 새 파일을 읽는다(1~2초 끊김)
#   3. 손님 화면을 다시 빌드한다 (FE 코드가 바뀌었을 수 있으니 항상)
#
# 스키마 변경은 API 가 뜰 때 Flyway 가 적용한다. 되돌리는 마이그레이션은 없다.
# ─────────────────────────────────────────────────────────────
set -euo pipefail
cd "$(dirname "$0")/.."

FE_DIR="${FRONT_SRC_DIR:-../FE}"

echo "▶ 코드 받기"
CADDY_BEFORE="$(git rev-parse HEAD:deploy/Caddyfile)"
git pull --ff-only
git -C "$FE_DIR" pull --ff-only

echo "▶ 컨테이너"
docker compose -f docker-compose.prod.yml up -d --build
if [ "$CADDY_BEFORE" != "$(git rev-parse HEAD:deploy/Caddyfile)" ]; then
  echo "▶ Caddy 설정이 바뀌어 다시 시작"
  docker compose -f docker-compose.prod.yml restart caddy
fi

echo "▶ 손님 화면 다시 빌드"
bash deploy/rebuild-front.sh

echo "✔ 끝. 진행은: docker compose -f docker-compose.prod.yml logs -f front"
