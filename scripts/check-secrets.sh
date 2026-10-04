#!/usr/bin/env bash
#
# 시크릿이 저장소에 들어가는 것을 막는다.
#
# 커밋 훅과 CI 가 **같은 스크립트**를 쓴다. 둘이 다르면 로컬은 통과하고 CI 만 막히거나
# 그 반대가 되어, 결국 아무도 신뢰하지 않는 검사가 된다.
#
#   ./scripts/check-secrets.sh --staged   커밋 직전 (스테이징된 파일만) — 훅이 쓴다
#   ./scripts/check-secrets.sh            전체 추적 파일 — CI 가 쓴다
#
# 종료 코드: 0 = 통과, 1 = 시크릿 의심 발견
#
# 오탐이 많으면 사람들이 --no-verify 로 꺼버린다. 그래서 "확실한 것"만 막고,
# 로컬 개발용으로 일부러 커밋한 값은 아래 ALLOWLIST 로 명시적으로 허용한다.

set -uo pipefail

MODE="${1:-all}"
FAILED=0

# ── 일부러 커밋한 로컬 개발 전용 값 ──────────────────────────
# 이 값들은 localhost 에서만 쓰이고 운영과 무관하다. 여기 적어두면 검사에서 제외된다.
# 새 값을 추가할 때는 "이게 운영에서도 쓰이나?" 를 먼저 자문한다.
ALLOWLIST=(
  'leoneferito_local_only'
)

# ── 검사 대상 파일 목록 ──────────────────────────────────────
if [ "$MODE" = "--staged" ]; then
  FILES=$(git diff --cached --name-only --diff-filter=ACM)
else
  FILES=$(git ls-files)
fi

if [ -z "$FILES" ]; then
  echo "검사할 파일이 없습니다."
  exit 0
fi

# 바이너리·잠금파일·빌드 산출물은 건너뛴다
FILES=$(echo "$FILES" | grep -vE '\.(jar|png|jpg|jpeg|gif|ico|webp|avif|woff2?|ttf|otf|pdf)$' \
                      | grep -vE '(^|/)(package-lock\.json|gradle-wrapper\.jar)$' \
                      | grep -vE '^(BE/build/|FE/\.next/|FE/out/|node_modules/)')

report() {
  echo ""
  echo "  ✗ $1"
  echo "    $2"
  FAILED=1
}

# ── 1. .env 파일 자체가 커밋되는 것을 막는다 ─────────────────
# 가장 흔한 사고다. .gitignore 가 있어도 git add -f 로 들어가는 경우가 있다.
ENV_FILES=$(echo "$FILES" | grep -E '(^|/)\.env' | grep -v '\.env\.example$' || true)
if [ -n "$ENV_FILES" ]; then
  report ".env 파일이 커밋 대상에 있습니다" "$(echo "$ENV_FILES" | tr '\n' ' ')"
  echo "    → .env 는 커밋하지 않습니다. .env.example 에 키 이름만 적으세요."
fi

# ── 2. 개인키 ────────────────────────────────────────────────
for f in $FILES; do
  [ -f "$f" ] || continue
  if grep -qE '^-----BEGIN [A-Z ]*PRIVATE KEY-----' "$f" 2>/dev/null; then
    report "개인키가 포함되어 있습니다" "$f"
  fi
done

# ── 3. AWS 액세스 키 ─────────────────────────────────────────
for f in $FILES; do
  [ -f "$f" ] || continue
  if grep -qE '\b(AKIA|ASIA)[0-9A-Z]{16}\b' "$f" 2>/dev/null; then
    report "AWS 액세스 키로 보이는 문자열이 있습니다" "$f"
  fi
done

# ── 4. 시크릿처럼 보이는 하드코딩된 값 ───────────────────────
# password/secret/token/api_key 등에 8자 이상 리터럴이 붙은 경우.
# ${ENV_VAR} 참조나 빈 값은 정상이므로 제외한다.
#
# 키워드가 이름 **중간**에 있는 경우까지 잡아야 한다.
#   aws_secret_access_key = "..."   ← secret 이 = 바로 앞이 아니다
#   spring.datasource.password=...  ← password 앞에 점 표기가 붙는다
# 그래서 키워드 앞뒤로 [A-Za-z0-9_.-]* 를 허용한다.
SECRET_KEY_RE='[A-Za-z0-9_.-]*(password|passwd|secret|token|api[_-]?key|apikey|credential|access[_-]?key)[A-Za-z0-9_.-]*[[:space:]]*[:=][[:space:]]*["'"'"']?[A-Za-z0-9/+_.=@!#%^&*?~$-]{8,}'

# ── 코드에서 값이 '리터럴이 아닌' 경우를 걸러낸다 ───────────
#
# 시크릿은 **따옴표 안의 값**일 때만 시크릿이다. 아래는 전부 코드이고 비밀이 아니다:
#   credentials: Credentials)                타입 표기
#   this.passwordEncoder = passwordEncoder;  변수 대입
#   boolean ok = passwordEncoder.matches(    메서드 호출
#   String token = JsonPath.read(            메서드 호출
#
# 판별 기준은 '값이 식별자이고 뒤에 코드 구두점( ; , ) ( )이 온다'이다.
# .env · yml · properties 는 값 뒤에 구두점이 없으므로 이 규칙에 걸리지 않는다
# — 즉 설정 파일의 진짜 시크릿은 그대로 잡힌다.
TYPE_ANNOTATION_RE=':[[:space:]]*[A-Z][A-Za-z0-9_]*(<[^>]*>)?[[:space:]]*[),;|&=]'
CODE_REFERENCE_RE='[:=][[:space:]]*[A-Za-z_][A-Za-z0-9_.]*[[:space:]]*[;,()]'

ALLOW_RE=$(IFS='|'; echo "${ALLOWLIST[*]}")
for f in $FILES; do
  [ -f "$f" ] || continue
  # 이 스크립트 자신은 건너뛴다 — 탐지 패턴 자체가 시크릿 키워드를 담고 있어 반드시 자기 자신을 잡는다.
  [ "$f" = "scripts/check-secrets.sh" ] && continue
  HITS=$(grep -nEi "$SECRET_KEY_RE" "$f" 2>/dev/null \
    | grep -vE '\$\{[A-Za-z_]+' \
    | grep -vE '[:=][[:space:]]*(""|'"''"'|$)' \
    | grep -vEi '(example|placeholder|changeme|your[_-]|TODO|xxx+)' \
    | grep -vE "$TYPE_ANNOTATION_RE" \
    | grep -vE "$CODE_REFERENCE_RE" \
    | grep -vE "$ALLOW_RE" || true)
  if [ -n "$HITS" ]; then
    report "하드코딩된 시크릿으로 보입니다" "$f"
    echo "$HITS" | head -5 | sed 's/^/      /'
    echo "      → 환경변수로 옮기세요: \${MY_SECRET}"
    echo "      → 로컬 전용 값이라 의도한 것이면 이 스크립트의 ALLOWLIST 에 추가하세요."
  fi
done

# ── 결과 ─────────────────────────────────────────────────────
echo ""
if [ "$FAILED" -eq 1 ]; then
  echo "시크릿 검사 실패 — 위 항목을 처리한 뒤 다시 시도하세요."
  exit 1
fi

echo "시크릿 검사 통과."
exit 0
