# PentAGI Integration — HostMyWeb v3.1

PentAGI ([github.com/vxcontrol/pentagi](https://github.com/vxcontrol/pentagi)) is
integrated into HostMyWeb (`com.imayx.host`) as a **separately managed, remote**
AI security platform with its own tab, project entry, configuration and logs.
No existing HostMyWeb feature, runtime, route, auth or UI behaviour was changed:
the integration is additive (one new enum value, one new screen, one new
package, one new repo-root bundle).

## Architecture

```
┌──────────────────────────── Android phone ─────────────────────────────┐
│ HostMyWeb                                                               │
│  PentAGI tab (PentagiScreen + PentagiViewModel)                         │
│        │                                                                │
│  PentagiManager ── StateFlow health / busy / logs                       │
│        │                                                                │
│  PentagiClient (HttpURLConnection, Bearer token from Keystore)          │
│        │  GET  /v1/health /v1/status /v1/logs /v1/config-status         │
│        │  POST /v1/actions/{start|stop|restart}   (enum-built path)     │
└────────┼────────────────────────────────────────────────────────────────┘
         │  private channel only: Tailscale / SSH tunnel / CF Tunnel+Access
┌────────▼──────────────────── Linux host (VPS / home server) ───────────┐
│ pentagi-remote/manager/server.js  (Node ≥18, zero deps, binds 127.0.0.1)│
│   - timing-safe Bearer auth on every /v1/* route                        │
│   - execFile('docker', [fixed argv], shell:false) — no shell, ever      │
│   - log redaction with real .env secret values + 200 KB cap             │
│        │                                                                │
│ docker compose (pentagi-remote/docker-compose.yml)                      │
│   pentagi  vxcontrol/pentagi:latest   127.0.0.1:8443, docker.sock       │
│   pgvector vxcontrol/pgvector:latest  Postgres + pgvector, healthcheck  │
│   scraper  vxcontrol/scraper:latest   internal only                     │
│   volumes: pentagi-data, pentagi-ssl, pentagi-postgres-data             │
└─────────────────────────────────────────────────────────────────────────┘
```

## Why PentAGI cannot run locally on Android

PentAGI is not a Node/Python app HostMyWeb could host with its existing
STATIC / NODE / SERVER runtimes:

- It is a Go service that **spawns Docker sandbox containers** for every agent
  task (mounts `/var/run/docker.sock`, containers request `NET_ADMIN`/`NET_RAW`).
  Android has no Docker daemon, and a Termux/Ubuntu proot cannot provide one
  (no kernel namespaces/cgroups control, no privileged socket).
- It requires **PostgreSQL + pgvector** and a companion web scraper service.
- Its official distribution is a Docker Compose stack with x86-64/ARM64 Linux
  images — there is no Android build.

So the only honest modes are:

| Mode | Meaning |
|---|---|
| `NOT_CONFIGURED` | No manager URL/token yet — the tab shows setup guidance. |
| `REMOTE` | Manager configured — all actions go to the Linux host. |
| `LOCAL_UNSUPPORTED` | Explicit state used in copy/docs: local execution is impossible; the app never pretends otherwise. |

Consequences in code: `RuntimeType.PENTAGI` exists (so the project entry
serialises like any other), but `RuntimeManager.forType()/create()` throw
`IllegalArgumentException("PENTAGI projects are managed remotely via the
PentAGI tab, not a local runtime")`, and `HostController` fails a PENTAGI start
early with a user-facing message pointing at the PentAGI tab (both the primary
and the multi-start/extra paths).

## What was integrated (Android side)

New package `app/src/main/java/com/imayx/host/pentagi/`:

- **`PentagiModels.kt`** — pure Kotlin, no Android imports: `PentagiDeployMode`,
  `PentagiAction` (allowlist), `PentagiLlmProvider` (maps to official env names
  `OPEN_AI_KEY`, `ANTHROPIC_API_KEY`, `GEMINI_API_KEY`, `DEEPSEEK_API_KEY`,
  `OLLAMA_SERVER_URL`, `LLM_SERVER_KEY`), `PentagiHealth`, and
  `PentagiValidator` (URL/port/tail/key validation; action paths built only
  from the enum).
- **`PentagiClient.kt`** — `HttpURLConnection` only (no new dependencies),
  5 s/15 s timeouts, 256 KB response cap, Bearer token from `SecretStore`,
  typed results (nothing throws to the UI), distinct "TLS error" reporting for
  the direct PentAGI probe (default platform trust — PentAGI's default
  self-signed cert will surface as a TLS error until it sits behind real TLS).
- **`PentagiManager.kt`** — `StateFlow`s for health/busy/error/logs, actions,
  log fetching, and lazy creation of the `pentagi` project entry
  (`ProjectConfig(name="pentagi", runtime=PENTAGI, port=8443)`) when the tab
  opens. All events log to the new `LogCategory.PENTAGI` (auto-redacted).

Wiring: `AppGraph.pentagi`, `Screen.PentAGI` (Security icon) + route in
`AppRoot`, `PentagiViewModel`, `PentagiScreen` (status card, setup checklist,
Start/Stop/Restart with confirm dialog, Open Dashboard via guarded
`ACTION_VIEW`, write-only secret config form, remote logs), new settings
(`pentagiEnabled/RemoteUrl/ManagerUrl/LlmProvider/PublicUrl`), new secret keys
(`KEY_PENTAGI_MANAGER_TOKEN`, `KEY_PENTAGI_API_TOKEN`, `KEY_PENTAGI_LLM_KEY`),
and a `PENTAGI` pill in the Projects list. Version bumped to
versionCode 4 / versionName "3.1".

## Configuration reference

| Where | Keys |
|---|---|
| App settings (non-secret) | `pentagi_enabled`, `pentagi_manager_url`, `pentagi_remote_url`, `pentagi_public_url`, `pentagi_llm_provider` |
| App secrets (Keystore AES-GCM, write-only in UI) | `pentagi_manager_token`, `pentagi_api_token`, `pentagi_llm_key` |
| Remote `.env` (see `pentagi-remote/.env.example`) | Official names: `OPEN_AI_KEY`, `ANTHROPIC_API_KEY`, `GEMINI_API_KEY`, `DEEPSEEK_API_KEY`, `BEDROCK_*`, `OLLAMA_SERVER_URL/MODEL`, `LLM_SERVER_URL/KEY/MODEL`, `DATABASE_URL`, `COOKIE_SIGNING_SALT`, `PUBLIC_URL`, `CORS_ORIGINS`, `PENTAGI_POSTGRES_USER/PASSWORD/DB`, … At least one LLM provider is required. |
| Manager env | `MANAGER_TOKEN` (required, ≥24 chars), `MANAGER_PORT` (8787), `MANAGER_BIND` (127.0.0.1), `PENTAGI_DIR`, `PENTAGI_URL` (`https://127.0.0.1:8443`) |

PentAGI's default login is `admin@pentagi.com` / `admin` — **change it
immediately** after first start. Persistent data lives in the named Docker
volumes on the remote host; nothing PentAGI-related is stored on the phone
except the encrypted tokens above.

## Security model

- Management endpoints exist only on the remote manager; every route requires
  the Bearer token (constant-time compare) and the manager binds localhost by
  design — reach it via Tailscale/SSH/Cloudflare Tunnel, never publicly.
- No shell anywhere: the app builds action paths from an enum; the manager runs
  `execFile('docker', […constants…], { shell: false })`, ignores POST bodies,
  and validates the one variable argument (log tail, integer 1–500).
- Secrets are server-side (remote `.env`) or Keystore-encrypted (phone);
  they are never in frontend code, never logged (`SecretStore` auto-registers
  values with the log `Redactor`; the manager redacts `.env` values from logs
  it returns), and `GET /v1/config-status` returns key **names only**.
- The PentAGI dashboard is never exposed by this integration; opening it uses
  the user-configured, validated URL in the phone's browser.
- HostMyWeb's existing app lock still gates the app; config fields for secrets
  are write-only (saved values are never read back into the UI).

## Setup (summary)

Full steps in `pentagi-remote/README.md`: install Docker on a Linux host →
`cp .env.example .env` + fill keys → `docker compose up -d` → run the manager
(`MANAGER_TOKEN=… node manager/server.js`, or the systemd unit in that README)
→ make it privately reachable → fill the HostMyWeb PentAGI tab configuration →
enable the integration → Refresh / Start.

## Limitations

- No local mode exists or is planned (see "Why" above) — Android/Termux proot
  was checked against PentAGI's requirements and cannot satisfy them.
- The phone-side PentAGI reachability probe uses default TLS trust, so a
  stock self-signed PentAGI cert reports "TLS error" there even while the
  manager's server-side probe (localhost, verification off — commented in
  `server.js`) reports reachable. Put PentAGI behind real TLS to clear it.
- Actions are coarse (whole-stack up/stop/restart), matching
  `docker compose` semantics; per-service control is not exposed.
- PentAGI upgrades are `docker compose pull` on the host; the app does not
  manage image versions.

## Verification results (actual, run in the build VM — not code-reading)

Toolchain: Temurin JRE 17.0.20.1 + kotlinc-jvm 2.0.21 (from
`~/workspace/hostmyweb-scantest/toolchain`), Node v24.20.0, Python 3 + PyYAML.

**1. Pure-Kotlin validator harness** — `PentagiModels.kt` compiled standalone
with a 52-assertion `Main.kt` harness (valid/invalid URLs incl.
`http://x;rm -rf /`, userinfo, whitespace, fragment, `$()`/backtick injection;
port bounds 1024–65535; tail bounds 1–500 + clamping; provider→env-name
mapping; enum-only action paths; key/token rules):

```
RESULT: pass=52 fail=0
```

**2. Node manager** — `node --check server.js` → OK. Run against a fake
`docker` stub on PATH (argv logged; canned `ps`/`logs` output containing a
fake secret from a test `.env`):

| Test | Result |
|---|---|
| No token → `GET /v1/status` | **401** |
| Wrong token | **401** |
| `GET /v1/health` | **200** `{"ok":true,"docker":true,"compose":true}` |
| `GET /v1/status` | **200** `composeState:"running"`, services pentagi/pgvector = running (pentagiReachable:false — expected, nothing listened on the test probe port) |
| `POST /v1/actions/start` | **200**; stub argv exactly `compose up -d` |
| `POST /v1/actions/evil` | **400**; stub NOT called (argv log 3 lines before and after) |
| `POST /v1/actions/restart` with body `{"action":"stop; rm -rf /"}` | **200**; stub shows only `compose restart` (body ignored) |
| `GET /v1/logs?tail=99999` | **400** `tail must be an integer between 1 and 500` |
| `GET /v1/logs?tail=50` | **200**; fake secret replaced — body contains `key=•••`, never the secret |
| `GET /v1/config-status` | **200** `{"envKeysSet":["OPEN_AI_KEY","PUBLIC_URL","PENTAGI_POSTGRES_PASSWORD"]}` — names only, no values |
| Unknown route | **404** |
| Startup with `MANAGER_TOKEN=short` | exits 1: `FATAL: MANAGER_TOKEN must be set and at least 24 characters long.` |

**3. Compose YAML** — `yaml.safe_load` OK: services `pentagi, pgvector,
scraper`; volumes `pentagi-data, pentagi-ssl, pentagi-postgres-data,
pentagi-ollama`; pentagi image `vxcontrol/pentagi:latest` bound
`${PENTAGI_LISTEN_IP:-127.0.0.1}:${PENTAGI_LISTEN_PORT:-8443}:8443`; pgvector
healthcheck present.

**4. Audits over the final tree** — no real secrets anywhere (only empty
placeholders/defaults in `.env.example`); no `shell: true`; the only process
execution in the manager is `execFile('docker', …)` with `shell: false`;
untouched per constraints: `ServerRuntime.kt`, `StaticServer.kt`,
`TunnelController`, `projects/*`, `ai/*`, all existing screens except the
`AppRoot`/`Navigation` wiring, the one-line `PENTAGI` pill branch in
`ProjectsScreen.kt`, and the version bump.

**Not verified here (stated plainly):** the Android app itself was **not**
compiled — this VM has no Android SDK/Gradle toolchain for the app, so the
Compose UI files (`PentagiScreen`, `PentagiViewModel`, wiring edits) carry the
usual risk of a small compile fix on first CI/device build. The pure-Kotlin
core (`PentagiModels.kt`) *is* compile- and test-proven above; the manager and
compose bundle are run-proven above. No real PentAGI instance was started
(no Docker host available in this environment), so end-to-end agent behaviour
is unverified.
