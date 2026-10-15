# SERVER projects — one-tap Node.js servers on the built-in runtime

## What it is
A third project runtime next to STATIC and NODE:

- **STATIC** — serves files with the in-app HTTP server.
- **NODE** — runs `node <entry>` as a child process; needs an executable ARM64
  node binary installed via Settings → Node Runtime (the bundled `libnode.so`
  is a shared library and *cannot* be executed as a child process).
- **SERVER** (new) — runs Node.js server code on the **built-in** Node.js
  (`libnode.so`, already bundled by CI) with **one tap, no downloads**.
  All SERVER projects start together in a single Node process.

## Why a separate OS process
`node::Start` may run exactly once per OS process (a second call SIGABRTs the
app). The main process already spends its one call on the AyX backend + wish
launcher, so SERVER projects run in a dedicated `:node_server` process
(`ServerHostService`, foreground service with its own low-importance
notification). Inside it, one Node instance imports every server via a
generated `launcher.mjs` — idle CPU stays ~0%.

## How a server starts
1. `HostController` collects the SERVER projects being started (primary or
   multi-start extras) and calls `ServerRuntime.startServers()`.
2. `ServerRuntime` writes `filesDir/node-servers/servers.json`
   (`[{name, dir, entry, port, env}]`) + `launcher.mjs`, then starts
   `ServerHostService`.
3. The `:node_server` process sets process env (`TMPDIR`, `HOME`,
   `NODE_OPTIONS=--max-old-space-size-percentage=50`) and calls
   `NodeBridge.nativeStart(["node", "launcher.mjs"], serversDir)` — once.
4. `launcher.mjs` starts servers **strictly one at a time** and only imports the
   next server after the current one's port accepts connections (20s timeout).
   This is load-bearing: `process.env` is process-global, so a server that reads
   `PORT` lazily (e.g. inside an async `main()`) would otherwise see the *next*
   server's port and die with EADDRINUSE. Verified with a live two-server test.
5. Per-server try/catch + a `process.exit` guard mean one bad server never kills
   the others. Node stdout/stderr also goes to `servers.log` (auto-trimmed).

## Environment & secrets
- `SESSION_SECRET`: auto-generated per project (32-byte hex, Keystore-backed)
  when the user didn't provide one — backends that require it boot with zero setup.
- `DB_PATH` / `UPLOAD_DIR`: default to absolute paths inside the project dir.
- Everything in Project → Settings → **Environment secrets** (Keystore-encrypted)
  is merged on top and wins over the defaults.
- **Import quarantine:** `.env`, `mail-key.json`, `*.key.json`, `*.pem` at the
  ZIP root are *deleted on import* and reported — real keys must live in
  Environment secrets, never in an importable/sharable ZIP.

## Import auto-detection
`ProjectManager.analyzeZipForServer()` (pre-extraction, from ZIP entry names) and
`detectServerEntry()` (post-extraction, real files) turn a backend ZIP into a
SERVER project automatically:
entry resolution = `package.json` `scripts.start` (`node <file>`) → `main` →
`server.js`, `src/server.js`, `index.js`, `src/index.js`, `app.js`, `src/app.js`, `main.js`.
The import dialog shows the detected type/entry and warns when `node_modules`
is missing (the server *will* crash without its dependencies — fail-fast message
at start time too, via `ServerRuntime.checkPrereqs`).

## Primary port (the 502 fix)
The Cloudflare tunnel always points at one port. The primary (active) project now
**always binds `settings.primaryPort`** (default 3000) — secondary projects in a
multi-start keep their own ports, and `nextFreePort()` never hands out the
primary port. So re-importing projects can never break the tunnel again.
Change it in Settings → Primary port (restart hosting to apply).

## Files
- New: `node/ServerRuntime.kt`, `service/ServerHostService.kt`
  (+ `AndroidManifest.xml` service entry, `android:process=":node_server"`).
- Modified: `data/Models.kt` (`RuntimeType.SERVER`), `data/AppSettings.kt`
  (`primaryPort`), `projects/ZipTools.kt` (`ZipInfo.entryNames`),
  `projects/ProjectManager.kt` (detection, quarantine, `ImportResult`),
  `domain/HostController.kt` (primary port, server orchestration),
  `runtime/RuntimeManager.kt` (NODE preflight now explains the `.so` situation),
  `ui/vm/ProjectsViewModel.kt`, `ui/vm/SettingsViewModel.kt`,
  `ui/screens/ProjectsScreen.kt`, `ui/screens/SettingsScreen.kt`,
  `HostApp.kt` (graph wiring).

## Test checklist (on device, AndroidIDE build)
1. Import a backend ZIP (with `node_modules`) → dialog shows
   "Server (Node.js) — auto-detected", entry, node_modules status.
2. Import a ZIP containing `mail-key.json` → warning shown, file NOT imported.
3. Start Hosting on the server project → "Node servers" notification appears;
   Logs (Node) show `[server-host] <name> running on :<port>`.
4. `curl http://127.0.0.1:<port>/…` answers; Dashboard CPU stays low at idle.
5. Multi-start: site + server → both ONLINE, site on primary port (3000),
   server on its own port; imayx.in loads (tunnel → :3000).
6. Stop one server → others keep running; stop primary → everything stops.
7. Reboot with "Start on boot" → servers come back (START_STICKY + manifest).
