#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────
# 매일 백업 — DB 덤프와 업로드 이미지. BE 폴더 안에서, 또는 cron 으로.
#
#   bash deploy/backup.sh
#
# cron (매일 04:30, 서버 시간 = 한국 시간):
#   crontab -e  →  30 4 * * * cd /opt/leoneferito/BE && bash deploy/backup.sh >> /opt/leoneferito/backups/backup.log 2>&1
#
# 결과는 /opt/leoneferito/backups 에 쌓이고 14일 지난 것은 지운다.
# 이 백업은 서버 안에 있다 — 서버 자체가 사라지는 경우는 Lightsail 자동 스냅샷이 막는다.
# 둘 다 켜 둔다. 서버 밖(S3 등)으로 한 벌 더 보내는 것은 오픈 뒤에.
# ─────────────────────────────────────────────────────────────
set -euo pipefail
cd "$(dirname "$0")/.."

DEST="${BACKUP_DIR:-/opt/leoneferito/backups}"
KEEP_DAYS="${BACKUP_KEEP_DAYS:-14}"
STAMP="$(date +%F-%H%M)"
mkdir -p "$DEST"

echo "$(date -Is) ▶ DB"
# DB 이름 · 사용자는 DB 컨테이너가 이미 갖고 있다(POSTGRES_*). .env 를 셸로 읽지 않는다 —
# 메일 발신자처럼 띄어쓰기 · 꺾쇠가 든 값에서 셸이 깨진다.
docker exec leoneferito-db sh -c 'pg_dump -U "$POSTGRES_USER" -Fc "$POSTGRES_DB"' > "$DEST/db-$STAMP.dump"

echo "$(date -Is) ▶ 업로드 이미지"
docker run --rm -v leoneferito-prod-media:/m:ro -v "$DEST":/out alpine \
  tar czf "/out/media-$STAMP.tgz" -C /m .

echo "$(date -Is) ▶ ${KEEP_DAYS}일 지난 백업 정리"
find "$DEST" -maxdepth 1 -type f \( -name 'db-*.dump' -o -name 'media-*.tgz' \) -mtime +"$KEEP_DAYS" -delete

ls -lh "$DEST" | tail -n 4
echo "$(date -Is) ✔ 백업 끝"
