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
#
# ── 서버 밖으로 한 벌 더 (2026-11-11 보안 강화) ─────────────────
# 서버 안 백업은 서버가 뚫리거나 망가지면 같이 사라진다. .env 에 아래 다섯 줄을 넣으면 매번 «암호화해서» 버킷에도 올린다.
# 넣지 않으면 이 단계는 건너뛴다(지금처럼 서버 안에만).
#   BACKUP_REMOTE=s3://버킷이름/leoneferito          Lightsail 객체 저장소(또는 S3) 버킷
#   BACKUP_PASSPHRASE=긴-무작위-문장                  암호화 열쇠. 잃으면 백업을 열 수 없다 — 서버 밖(비밀번호 관리자)에도 적어 둔다
#   BACKUP_AWS_ACCESS_KEY_ID=…                        이 버킷에만 쓰기 권한이 있는 키(Lightsail 버킷 → 액세스 키)
#   BACKUP_AWS_SECRET_ACCESS_KEY=…
#   BACKUP_AWS_REGION=ap-northeast-2
# 버킷에서 오래된 파일을 지우는 건 버킷의 «수명 주기 규칙»(예: 30일)으로 맡긴다 — 이 키에는 지우기 권한을 주지 않는다.
# 올리기는 aws-cli 컨테이너로 한다(서버에 따로 설치하지 않는다).
#
# 되살릴 때(암호 풀기):
#   openssl enc -d -aes-256-cbc -pbkdf2 -iter 200000 -in db-….dump.enc -out db.dump -pass pass:'열쇠'
#   (그 뒤 pg_restore — README 의 «백업에서 되살리기»)
# ─────────────────────────────────────────────────────────────
set -euo pipefail
cd "$(dirname "$0")/.."
# 덤프에는 회원 · 주문 정보가 다 들어 있다 — 만드는 파일은 주인만 읽게
umask 077

DEST="${BACKUP_DIR:-/opt/leoneferito/backups}"
KEEP_DAYS="${BACKUP_KEEP_DAYS:-14}"
STAMP="$(date +%F-%H%M)"
mkdir -p "$DEST"
chmod 700 "$DEST"

echo "$(date -Is) ▶ DB"
# DB 이름 · 사용자는 DB 컨테이너가 이미 갖고 있다(POSTGRES_*). .env 를 셸로 읽지 않는다 —
# 메일 발신자처럼 띄어쓰기 · 꺾쇠가 든 값에서 셸이 깨진다.
docker exec leoneferito-db sh -c 'pg_dump -U "$POSTGRES_USER" -Fc "$POSTGRES_DB"' > "$DEST/db-$STAMP.dump"

echo "$(date -Is) ▶ 업로드 이미지"
docker run --rm -v leoneferito-prod-media:/m:ro -v "$DEST":/out alpine \
  tar czf "/out/media-$STAMP.tgz" -C /m .

echo "$(date -Is) ▶ ${KEEP_DAYS}일 지난 백업 정리"
find "$DEST" -maxdepth 1 -type f \( -name 'db-*.dump' -o -name 'media-*.tgz' \) -mtime +"$KEEP_DAYS" -delete

# ── 서버 밖으로 (설정이 있을 때만) ──
# .env 를 셸로 읽지 않는다(위 DB 주석과 같은 이유) — 필요한 줄만 꺼낸다
env_value() { grep -E "^$1=" .env 2>/dev/null | tail -n 1 | cut -d= -f2- || true; }
REMOTE="$(env_value BACKUP_REMOTE)"
if [ -n "$REMOTE" ]; then
  PASSPHRASE="$(env_value BACKUP_PASSPHRASE)"
  if [ -z "$PASSPHRASE" ]; then
    echo "$(date -Is) ✖ BACKUP_REMOTE 는 있는데 BACKUP_PASSPHRASE 가 없다 — 암호화하지 않은 백업은 밖으로 보내지 않는다" >&2
    exit 1
  fi
  echo "$(date -Is) ▶ 암호화해서 버킷으로 ($REMOTE)"
  for f in "$DEST/db-$STAMP.dump" "$DEST/media-$STAMP.tgz"; do
    BACKUP_PASSPHRASE="$PASSPHRASE" openssl enc -aes-256-cbc -pbkdf2 -iter 200000 -salt       -in "$f" -out "$f.enc" -pass env:BACKUP_PASSPHRASE
  done
  docker run --rm     -e AWS_ACCESS_KEY_ID="$(env_value BACKUP_AWS_ACCESS_KEY_ID)"     -e AWS_SECRET_ACCESS_KEY="$(env_value BACKUP_AWS_SECRET_ACCESS_KEY)"     -e AWS_DEFAULT_REGION="$(env_value BACKUP_AWS_REGION)"     -v "$DEST":/b:ro amazon/aws-cli:2.22.35     s3 cp "/b/" "$REMOTE/$STAMP/" --recursive --exclude "*"       --include "db-$STAMP.dump.enc" --include "media-$STAMP.tgz.enc" --only-show-errors
  # 암호화한 사본은 올렸으니 서버에는 남기지 않는다(원본은 위 보관 기간대로)
  rm -f "$DEST/db-$STAMP.dump.enc" "$DEST/media-$STAMP.tgz.enc"
  echo "$(date -Is) ✔ 버킷에 올림"
fi

ls -lh "$DEST" | tail -n 4
echo "$(date -Is) ✔ 백업 끝"
