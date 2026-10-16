#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────
# 운영 설정 점검 — 서버의 BE 폴더에서.   bash deploy/check-settings.sh
#
# .env 에 빠진 값, 백업 예약, 건강 상태를 한눈에 본다. 비밀값(비밀번호 · 키)은 "있음/없음" 만 보이고 값은 찍지 않는다.
# 2026-10-16 오픈 준비 점검용 — README "배포" 와 짝.
# ─────────────────────────────────────────────────────────────
set -uo pipefail
cd "$(dirname "$0")/.."

ok()   { printf '  \033[32m✔\033[0m %s\n' "$1"; }
warn() { printf '  \033[33m!\033[0m %s\n' "$1"; }
bad()  { printf '  \033[31m✘\033[0m %s\n' "$1"; }

val() { grep -E "^$1=" .env 2>/dev/null | head -1 | cut -d= -f2- ; }
has() { [ -n "$(val "$1")" ]; }

[ -f .env ] || { bad ".env 가 없다 — cp .env.example .env 뒤 채운다"; exit 1; }

echo "▶ 메일 (SMTP)"
if has SPRING_MAIL_HOST && has SPRING_MAIL_USERNAME && has SPRING_MAIL_PASSWORD; then
  ok "SMTP 설정 있음 — $(val SPRING_MAIL_HOST):$(val SPRING_MAIL_PORT) (비밀번호 ***)"
else
  bad "SMTP 비어 있음 — 주문 · 비밀번호 찾기 메일이 나가지 않는다 (SPRING_MAIL_HOST/USERNAME/PASSWORD)"
fi
has MAIL_FROM && ok "발신 주소: $(val MAIL_FROM)" || warn "MAIL_FROM 없음 — 기본값 no-reply@leoneferito.kr"

echo "▶ 관리자 알림"
has ORDER_NOTIFY_EMAILS && ok "주문 알림 받는 곳: $(val ORDER_NOTIFY_EMAILS)" \
  || bad "ORDER_NOTIFY_EMAILS 비어 있음 — 새 주문 · 취소 · 반품 알림이 아무에게도 가지 않는다"

echo "▶ 나이스페이"
base="$(val NICEPAY_API_BASE)"
case "$base" in
  https://api.nicepay.co.kr*) ok "운영 주소 ($base)";;
  "") warn "NICEPAY_API_BASE 없음 — 기본값(샌드박스)으로 돈다. 운영이면 https://api.nicepay.co.kr";;
  *sandbox*) bad "샌드박스 주소 ($base) — 운영이면 https://api.nicepay.co.kr";;
  *) warn "알 수 없는 주소 ($base)";;
esac
has NICEPAY_CLIENT_ID && has NICEPAY_SECRET_KEY && ok "키 있음 (***)" || bad "NICEPAY_CLIENT_ID / NICEPAY_SECRET_KEY 비어 있음"

echo "▶ 백업"
if crontab -l 2>/dev/null | grep -q "deploy/backup.sh"; then
  ok "cron 등록됨: $(crontab -l | grep deploy/backup.sh | head -1)"
  last="$(ls -t /opt/leoneferito/backups/db-*.dump 2>/dev/null | head -1)"
  [ -n "$last" ] && ok "최근 DB 백업: $last ($(du -h "$last" | cut -f1))" || warn "아직 백업 파일이 없다 — 첫 실행 전이거나 실패. bash deploy/backup.sh 로 한 번 돌려 본다"
else
  bad "cron 미등록 — crontab -e 에 추가:  30 4 * * * cd /opt/leoneferito/BE && bash deploy/backup.sh >> /opt/leoneferito/backups/backup.log 2>&1"
fi

echo "▶ 건강 상태"
domain="$(val API_DOMAIN)"
code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 "https://${domain:-api.leoneferito.kr}/actuator/health/readiness")"
[ "$code" = "200" ] && ok "https://${domain:-api.leoneferito.kr}/actuator/health/readiness → 200 (UptimeRobot 에 이 주소를 등록)" \
  || bad "readiness 응답 $code"

echo "▶ 메일 도메인 DNS (발신 주소의 도메인)"
from_domain="$(val MAIL_FROM | sed -n 's/.*@\([A-Za-z0-9.-]*\).*/\1/p')"
from_domain="${from_domain:-leoneferito.kr}"
if command -v dig >/dev/null; then
  spf="$(dig +short TXT "$from_domain" | grep -i 'v=spf1' | head -1)"
  # 카페24는 *.도메인 와일드카드가 있어 _dmarc 가 "leoneferito.kr." 로 답할 수 있다 — v=DMARC1 이 있어야 진짜 레코드다
  dmarc="$(dig +short TXT "_dmarc.$from_domain" | grep -i 'v=DMARC1' | head -1)"
  [ -n "$spf" ] && ok "SPF: $spf" || bad "SPF 없음 — $from_domain TXT 에 v=spf1 … 추가"
  [ -n "$dmarc" ] && ok "DMARC: $dmarc" || bad "DMARC 없음 — _dmarc.$from_domain TXT 추가"
  warn "DKIM 은 발송 서비스(SES 등)가 주는 CNAME 3개가 DNS 에 있는지 그 콘솔에서 확인"
else
  warn "dig 가 없어 DNS 는 건너뜀 (sudo apt install -y dnsutils)"
fi
