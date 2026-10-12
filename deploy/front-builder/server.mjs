// ─────────────────────────────────────────────────────────────
// 손님 화면(FE) 빌더 — Vercel 배포 훅을 대신한다 (2026-10-12, Lightsail 한 대 배포).
//
// 하는 일
//   1. POST /rebuild?token=… 를 받으면 FE 를 다시 빌드한다(next build → out/).
//   2. 빌드가 성공하면 /site/releases/<시각> 에 복사하고 /site/current 링크를 그쪽으로 바꾼다.
//      Caddy 는 /site/current 를 서빙한다 — 링크 바꾸기는 한 번의 rename 이라 손님은 반쯤 바뀐 사이트를 보지 않는다.
//   3. 실패하면 옛 릴리스를 그대로 둔다. 가짜 상품이 올라가는 일은 없다(lib/catalog.ts 가 API 실패 때 빌드를 멈춘다).
//
// 누가 부르나
//   API 의 FrontRebuildTrigger — 관리자가 상품을 공개 · 수정하면 30초 모아서 한 번 부른다(FRONT_DEPLOY_HOOK_URL).
//   배포 스크립트 deploy/rebuild-front.sh — FE 코드를 받은 뒤.
//
// 이 포트(9000)는 compose 내부 네트워크에만 있다. 바깥에 열지 않는다. 그래도 토큰을 확인한다.
// 의존성 없는 Node 스크립트다 — 이미지에 npm 패키지를 따로 넣지 않는다.
// ─────────────────────────────────────────────────────────────
import { spawn } from "node:child_process";
import { createHash, timingSafeEqual } from "node:crypto";
import fs from "node:fs";
import http from "node:http";
import path from "node:path";

const SRC = process.env.FRONT_SRC ?? "/src"; // FE 저장소(읽기 전용으로 붙인다)
const WORK = process.env.FRONT_WORK ?? "/work/app"; // 빌드 작업 폴더(node_modules · .next 캐시가 남는다)
const SITE = process.env.SITE_ROOT ?? "/site"; // Caddy 와 같이 쓰는 볼륨
// 비밀 값은 환경변수에서만 온다(.env 의 FRONT_REBUILD_TOKEN → compose 가 REBUILD_TOKEN 으로 넘긴다)
const EXPECTED = process.env.REBUILD_TOKEN ?? "";
const KEEP_RELEASES = Number(process.env.KEEP_RELEASES ?? 3);
const RETRY_FIRST_BUILD_MS = 60_000;

// 작업 폴더에 남겨 두는 것 — 지우면 매번 npm ci 와 처음 빌드를 다시 한다
const KEEP_IN_WORK = new Set(["node_modules", ".next"]);
// 원본에서 복사하지 않는 것
const SKIP_FROM_SRC = new Set(["node_modules", ".next", "out", ".git", ".env", ".env.local", ".env.production.local"]);

const state = { building: false, again: false, lastStartedAt: null, lastSuccessAt: null, lastRelease: null, lastError: null };

const log = (...a) => console.log(new Date().toISOString(), ...a);

if (!EXPECTED || EXPECTED.length < 16) {
  log("REBUILD_TOKEN 이 비었거나 너무 짧다(16자 이상). .env 의 FRONT_REBUILD_TOKEN 을 채운다.");
  process.exit(1);
}

function tokenOk(given) {
  const a = Buffer.from(String(given ?? ""));
  const b = Buffer.from(EXPECTED);
  return a.length === b.length && timingSafeEqual(a, b);
}

function run(cmd, args, opts = {}) {
  return new Promise((resolve, reject) => {
    const child = spawn(cmd, args, { stdio: "inherit", ...opts });
    child.on("error", reject);
    child.on("exit", (code) => (code === 0 ? resolve() : reject(new Error(`${cmd} ${args.join(" ")} → 종료 코드 ${code}`))));
  });
}

/** 원본(/src)을 작업 폴더로 맞춘다. 원본에서 지워진 파일도 지워지도록, 남겨 둘 것만 빼고 먼저 비운다. */
function syncSource() {
  fs.mkdirSync(WORK, { recursive: true });
  for (const name of fs.readdirSync(WORK)) {
    if (!KEEP_IN_WORK.has(name)) fs.rmSync(path.join(WORK, name), { recursive: true, force: true });
  }
  for (const name of fs.readdirSync(SRC)) {
    if (SKIP_FROM_SRC.has(name)) continue;
    fs.cpSync(path.join(SRC, name), path.join(WORK, name), { recursive: true });
  }
}

/** package-lock 이 바뀌었을 때만 npm ci — 매번 하면 빌드마다 1~2분이 더 든다. */
async function installIfNeeded() {
  const lock = fs.readFileSync(path.join(WORK, "package-lock.json"));
  const hash = createHash("sha256").update(lock).digest("hex");
  const mark = path.join(WORK, "node_modules", ".lock-hash");
  const prev = fs.existsSync(mark) ? fs.readFileSync(mark, "utf8").trim() : "";
  if (prev === hash) return;
  log("의존성 설치 (package-lock 이 바뀜)");
  await run("npm", ["ci", "--no-audit", "--no-fund"], { cwd: WORK });
  fs.writeFileSync(mark, hash);
}

/** out/ 을 새 릴리스로 옮기고 current 링크를 바꾼다. 오래된 릴리스는 KEEP_RELEASES 개만 남긴다. */
function publish() {
  const releases = path.join(SITE, "releases");
  fs.mkdirSync(releases, { recursive: true });
  const name = new Date().toISOString().replace(/[-:]/g, "").replace(/\..+$/, "");
  const target = path.join(releases, name);
  fs.cpSync(path.join(WORK, "out"), target, { recursive: true });

  // 상대 링크 — Caddy 컨테이너에서는 같은 볼륨이 다른 경로(/srv/site)에 붙는다
  const tmp = path.join(SITE, `.current-${process.pid}`);
  fs.rmSync(tmp, { force: true });
  fs.symlinkSync(path.join("releases", name), tmp);
  fs.renameSync(tmp, path.join(SITE, "current")); // 같은 파일시스템 안의 rename = 한 번에 바뀐다

  const all = fs.readdirSync(releases).sort();
  for (const old of all.slice(0, Math.max(0, all.length - KEEP_RELEASES))) {
    fs.rmSync(path.join(releases, old), { recursive: true, force: true });
  }
  return name;
}

async function build(reason) {
  if (state.building) {
    // 빌드 중에 또 부르면, 끝난 뒤 한 번만 더 돈다(그 사이 바뀐 내용까지 담기도록)
    state.again = true;
    log(`빌드 중 — 끝나면 다시 한 번 (${reason})`);
    return;
  }
  state.building = true;
  state.lastStartedAt = new Date().toISOString();
  log(`빌드 시작 (${reason})`);
  try {
    syncSource();
    await installIfNeeded();
    fs.rmSync(path.join(WORK, "out"), { recursive: true, force: true });
    await run("npx", ["next", "build"], {
      cwd: WORK,
      env: {
        ...process.env,
        NEXT_TELEMETRY_DISABLED: "1",
        // 4GB 서버에 API · DB 와 같이 산다. 빌드가 메모리를 다 먹지 않게 상한을 둔다.
        NODE_OPTIONS: `--max-old-space-size=${process.env.BUILD_MEMORY_MB ?? 1536}`,
      },
    });
    const release = publish();
    state.lastSuccessAt = new Date().toISOString();
    state.lastRelease = release;
    state.lastError = null;
    log(`빌드 완료 → 릴리스 ${release}`);
  } catch (e) {
    state.lastError = String(e?.message ?? e);
    log(`빌드 실패 — 옛 릴리스를 그대로 둔다: ${state.lastError}`);
  } finally {
    state.building = false;
    if (state.again) {
      state.again = false;
      setImmediate(() => build("빌드 중 들어온 요청"));
    }
  }
}

const server = http.createServer((req, res) => {
  const url = new URL(req.url ?? "/", "http://localhost");
  if (req.method === "POST" && url.pathname === "/rebuild") {
    if (!tokenOk(url.searchParams.get("token"))) {
      res.writeHead(403).end();
      return;
    }
    build("요청");
    res.writeHead(202, { "content-type": "application/json" }).end(JSON.stringify({ accepted: true }));
    return;
  }
  if (req.method === "GET" && url.pathname === "/status") {
    res.writeHead(200, { "content-type": "application/json" }).end(JSON.stringify(state));
    return;
  }
  res.writeHead(404).end();
});

server.listen(9000, () => log("빌더 대기 중 :9000"));

// 처음 뜰 때 서빙할 사이트가 없으면 바로 만든다. 실패하면(API 가 아직 안 떴다 등) 1분마다 다시 해 본다.
async function firstBuild() {
  if (fs.existsSync(path.join(SITE, "current"))) return;
  await build("첫 기동 — 사이트 없음");
  if (!fs.existsSync(path.join(SITE, "current"))) setTimeout(firstBuild, RETRY_FIRST_BUILD_MS);
}
firstBuild();

for (const sig of ["SIGTERM", "SIGINT"]) process.on(sig, () => server.close(() => process.exit(0)));
