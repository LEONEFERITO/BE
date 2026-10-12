#!/usr/bin/env bash
# 손님 화면을 지금 다시 빌드한다 — BE 폴더 안에서.   bash deploy/rebuild-front.sh
# 빌더(front 컨테이너) 안에서 자기 자신을 부른다. 빌드는 1~3분, 끝나면 사이트가 한 번에 바뀐다.
# 진행 상황: docker compose -f docker-compose.prod.yml logs -f front
set -euo pipefail
cd "$(dirname "$0")/.."

docker compose -f docker-compose.prod.yml exec -T front node -e '
fetch("http://127.0.0.1:9000/rebuild?token=" + encodeURIComponent(process.env.REBUILD_TOKEN), { method: "POST" })
  .then((r) => { console.log(r.status === 202 ? "빌드 요청 접수" : "빌드 요청 거절 " + r.status); process.exit(r.status === 202 ? 0 : 1); })
  .catch((e) => { console.error("빌더에 닿지 않음:", e.message); process.exit(1); });
'
