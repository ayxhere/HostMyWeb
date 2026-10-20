# v4 — multi-server ZIP, auto ports/subdomains, AI auto-fix + notifications, VPN reconnect

## 1. Multi-server ZIP
- `projects/ServerScanner.kt` (new): finds every Node server in a ZIP/project (root, `api/`, `hello/`, ... depth 3).
  Skips node_modules/.git/public/assets/etc. Sub-folders need package.json or a `listen()` call.
- Each server gets: name = folder name, **own free port** (3200+, never 3101/3102/primary), subdomain = folder name.
  Root server (or web/www/main/site/app folder, else first) = **primary** -> binds the tunnel port (3000).
- Project card shows `Server 1 / Server 2 ...` with name, port, host, entry file. Menu -> "Rescan servers".
- Import dialog lists all servers found. `.env` / `*.key.json` / `*.pem` removed from root AND sub-folders.
- node_modules may live in the project root (shared) — checked up the folder tree.

## 2. Auto Cloudflare routes (`cloudflare/RouteSync.kt`)
After servers start (named tunnel + API token): `api/` -> `api.<domain>` ingress `http://127.0.0.1:<port>` + proxied CNAME.
Other routes (apex imayx.in) untouched. Domain = project domain, else Settings default domain. Toggle: Settings -> Auto routes.

## 3. Groq AI auto-fix (full loop)
- `service/ServerMonitor.kt`: reads servers.log every 4 s; finds error -> which server/file -> Groq patch (backup first)
  -> **real restart** of node process -> checks the port -> up to 3 attempts.
- `AiFixer`: works on the failing server's folder, also sends files named in the stack trace, retries 429/5xx, fallback model.
- `ai/AiNotifier.kt`: notification = Where (file:line) / Issue / Fix / Edited files / Backup.
- Bug fixed: old code called `startServers` again after a fix but node runs once per process, so code was never reloaded.
  New `ServerRuntime.restartServers()` kills the `:node_server` process, waits, starts again.

## 4. VPN / network reconnect (`cloudflare/NetworkWatcher.kt`)
- Default-network + VPN callbacks -> wait 3 s -> restart cloudflared once (HTTP/2 when VPN on, QUIC when off), fresh edge IPs.
- Health loop: tunnel down > 60 s -> restart (max 5 / 10 min, not for token/install errors).
- cloudflared now gets `--edge-ip-version 4` and (only if the binary supports it) `--dns-resolver-addrs 1.1.1.1/8.8.8.8`.
- Best fix still: VPN app -> split tunneling -> exclude IMAYX Host.

## Known limits
- All servers share one node process: `process.env.PORT` read lazily at request time shows the LAST server's port
  (listen() at startup is fine). `process.chdir` ends in the last server's folder.
- A restart after an AI fix restarts all servers.
- Not compiled here (no Kotlin/Android SDK in this sandbox): build in AndroidIDE and paste any error.

## v4.1
- Scanner: more entry names (src/main.js, *.mjs, bin/www, api.js), `nodemon`/`bun` start scripts, workspace roots,
  depth 4, and a `.listen()` fallback for folders with package.json.
- A single detected server is now also a `Server 1` entry (legacy projects show in the list).
- AI read: `AiFixer.detectServers()` — Groq reads the tree + package.json files and names the servers.
  Runs automatically when a ZIP has package.json but no server was found, on Rescan with no result,
  and from Project settings -> "Detect servers (AI)". Needs a Groq key.
- Project settings dialog: per-server enable switch, port, subdomain (duplicate checks).
