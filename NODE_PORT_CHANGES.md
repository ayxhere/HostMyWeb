# Embedded Node.js — AyX Group backend runs inside the app

The AyX Group backend (Node.js + SQLite) now runs **inside the IMAYX Host app process**
via an embedded Node.js runtime. **Termux / proot-Ubuntu is no longer needed.**

Ported from the proven AyX WhatsApp embedded-Node setup (same `libnode.so` + JNI pattern).

## What was added / changed

### New files
- `app/src/main/cpp/CMakeLists.txt` — builds `node_bridge` against the imported `libnode.so`.
  Skips gracefully (with a CMake warning) when `libnode.so` isn't present, so local
  builds without the CI bundling step still compile; the backend is then disabled at runtime.
- `app/src/main/cpp/node_bridge.cpp` — minimal JNI glue: `nativeSetenv`, `nativeStart`
  (forwards to `node::Start`; forwards node stdout/stderr to logcat as `AYX_NODE`).
- `app/src/main/java/com/imayx/host/node/NodeBridge.kt` — `object NodeBridge` with
  `available` flag (false when the native libs aren't bundled), `nativeSetenv`, `nativeStart`.
- `app/src/main/java/com/imayx/host/node/AyxBackendRuntime.kt` — owns the backend:
  extracts `assets/ayx-backend.zip` → `filesDir/ayx-backend` (re-extracts on app update
  via a `.version` marker = package `lastUpdateTime`); sets env (`PORT=3101`,
  `DB_PATH`, `UPLOAD_DIR`, `SESSION_SECRET`, …); starts `node src/server.js` once per
  OS process (AtomicBoolean guard); `runNodeScript()` for one-shot scripts
  (e.g. `scripts/create-admin.js`) with no guard; Zip-Slip guard on extraction.
- `app/src/main/assets/ayx-backend.zip` (2.1 MB) — backend bundle: `src/`, `db/`,
  `scripts/`, `package.json`, production `node_modules/` (87 pkgs). Excludes: `.env`,
  `data/`, `uploads/`, `tests/`, `docs`. Rebuild with:
  `cd <backend> && zip -qr <app>/src/main/assets/ayx-backend.zip src db scripts package.json node_modules -x 'node_modules/.package-lock.json'`
  (use a production `npm install --omit=dev --omit=optional` tree).

### Modified files
- `app/build.gradle.kts` — `ndkVersion 26.3.11579264`, `externalNativeBuild.cmake`
  (path `src/main/cpp/CMakeLists.txt`, version 3.22.1), `abiFilters += "arm64-v8a"`,
  `-DANDROID_STL=c++_shared`; `androidx.security:security-crypto:1.0.0`;
  `androidResources.noCompress += "zip"` (backend zip STORED, not deflated).
  `packaging.jniLibs.useLegacyPackaging` was already `true`, so `libnode.so` is
  extracted to `nativeLibraryDir` on install — no change needed there.
- `app/src/main/java/com/imayx/host/data/AppSettings.kt` — new `ayxBackendEnabled`
  toggle (default **true**).
- `app/src/main/java/com/imayx/host/ui/vm/SettingsViewModel.kt` — `ayxBackend` UI state + `setAyxBackend()`.
- `app/src/main/java/com/imayx/host/ui/screens/SettingsScreen.kt` — "AyX backend" switch
  ("Run the AyX Group API inside the app (port 3101) while hosting").
- `app/src/main/java/com/imayx/host/service/HostService.kt` — starts the embedded
  backend when hosting starts (inside the existing foreground service; no second
  notification). The backend lives as long as the service does.
- `app/src/main/java/com/imayx/host/ui/vm/ProjectsViewModel.kt` — "Create admin account"
  now runs through the embedded runtime (`AyxBackendRuntime.runNodeScript`) instead of
  the old external-Node `NodeScriptRunner`. Password still passed as a process arg only,
  never stored or logged.
- `.github/workflows/build.yml` — installs `ndk;26.3.11579264` + `cmake;3.22.1`;
  new step "Bundle nodejs-mobile libnode.so as native lib" (digidem releases,
  first non-lite `-android-*.zip`, copies `arm64-v8a/libnode.so` to
  `app/src/main/jniLibs/arm64-v8a/`), mirroring the cloudflared bundling step.

### Deliberately NOT changed
- R8 is disabled (`isMinifyEnabled = false`) — no ProGuard rules added. If R8 is ever
  enabled, keep `-keepclasseswithmembernames class com.imayx.host.node.NodeBridge { native <methods>; }`
  (noted in `NodeBridge.kt`).
- The old `NodeInstaller` / `NodeScriptRunner` (external Termux-style node) is untouched —
  project "Run script" features keep working as before.

## How the backend starts / stops

- **Start:** `HostService.onStartCommand` → if `settings.ayxBackendEnabled` →
  `AyxBackendRuntime.ensureStarted()` → extracts backend (first run / app update) →
  sets env → `node src/server.js` on a background thread, listening on `:3101`.
- **Stop:** the node thread is bound to the app process; stopping hosting (or the OS
  killing the service) ends it. There is no separate stop — the server is meant to
  live exactly as long as hosting does.
- **Health:** `curl http://127.0.0.1:3101/v1/health` on the device → `{"status":"ok",…}`.
  The existing `api.imayx.in → 127.0.0.1:3101` tunnel route works unchanged.

## Where secrets and data live

- `SESSION_SECRET`: generated once (SecureRandom, 32 bytes hex), stored in
  `EncryptedSharedPreferences` (`MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)`;
  note: `MasterKey` singular does **not** exist in security-crypto 1.0.0).
  Never logged, never in the APK.
- SQLite DB: `filesDir/ayx-backend/data/ayx.db` (via `DB_PATH`). Migrations run
  automatically on server boot. Deleting `filesDir/ayx-backend` resets everything
  (backend re-extracts, DB recreated, but the admin must be re-created).
- Uploads: `filesDir/ayx-backend/uploads/` (via `UPLOAD_DIR`).
- No `.env` is bundled; all config comes from `nativeSetenv` (which wins over dotenv).

## Node version requirement

digidem/nodejs-mobile currently ships **Node v24.x** for Android arm64 (verified
against the releases API at port time). The backend requires **Node ≥ 22.13**
(`node:sqlite` needs 22.5+). If digidem ever ships a major version the backend
doesn't support, pin the workflow's release lookup accordingly.

## USER ACTION / TEST (no compiler was available here — code hand-written and reviewed)

**2026-10-11 update — no CMake needed for local builds.** The SDK `cmake` binary
in on-device IDEs is frequently broken (`syntax error: unexpected ')'` —
it isn't executable on the device). So:
- `libnode_bridge.so` is now **prebuilt and committed** at
  `app/src/main/jniLibs/arm64-v8a/` (compiled with NDK r26d for arm64-v8a).
- `app/build.gradle.kts` only configures CMake when you pass `-PbuildNative`
  (CI does: `gradle assembleDebug -PbuildNative`). Plain `assembleDebug` in
  AndroidIDE skips CMake entirely — no NDK/CMake download needed locally.

1. **Gradle sync** — new dep: `androidx.security:security-crypto:1.0.0`.
2. **libnode.so for local builds** — CI places it automatically. For an AndroidIDE
   build, download `nodejs-mobile-android-<ver>.zip` (non-lite) from
   `github.com/digidem/nodejs-mobile/releases`, extract `arm64-v8a/libnode.so` into
   `app/src/main/jniLibs/arm64-v8a/`. Without it the app still builds and runs —
   the backend is disabled with a log warning.
3. **Build & install**, start hosting, then on-device:
   `curl http://127.0.0.1:3101/v1/health` → expect `{"status":"ok",…}`.
4. **Create admin**: Projects → "Create admin account…" (now uses the embedded
   runtime; no external Node needed).
5. **Admin login**: `https://imayx.in/admin/ayx-group/` → sign in (tunnel route
   `api.imayx.in → 127.0.0.1:3101` unchanged).
6. **Toggle**: Settings → "AyX backend" to disable the embedded server (hosting
   continues without the API).
7. Watch logcat `AYX_NODE` / the app's Logs (Node category) for backend output.
