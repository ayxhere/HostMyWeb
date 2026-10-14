# IMAYX Host v3

Android hosting control panel (`com.imayx.host`): static HTTP server, optional Node runtime, real `cloudflared` tunnels, multi-project management, file manager, code editor, backups, app lock.

## Build
- Gradle 8.9, AGP 8.7.3, Kotlin 2.0.21, Java 17, compileSdk/targetSdk 35, minSdk 24.
- CI: `.github/workflows/build.yml` (ubuntu-24.04) installs Gradle 8.9 directly, builds `assembleDebug`, uploads `app-debug.apk`.
- Local: `gradle assembleDebug` (no wrapper is committed).

## cloudflared on Android (read this)
- Android 10+ blocks executing binaries from app data. CI therefore bundles the official `cloudflared-linux-arm64` as `libcloudflared.so` in `jniLibs/arm64-v8a`, which runs from the native library dir.
- Fallbacks: in-app download (Cloudflare tab → Install), custom URL, or Import file. Failures are reported with a real reason (DNS / permissions / wrong arch).
- Go builds of cloudflared may fail DNS on Android (no `/etc/resolv.conf`). Use an Android-compatible build via Settings → custom URL if so.

## Features
Dashboard · Projects (SAF ZIP import/export, Zip-Slip safe) · Files · Editor · Cloudflare (quick/named tunnel, API token, DNS/routes, connection test) · Logs (redacted) · Backups · Settings · App Lock (PIN/biometric) · Preview (WebView).

## Security
Tokens are AES-GCM encrypted with the Android Keystore, redacted from every log, excluded from backups and project folders. Server binds to 127.0.0.1 only.

## Node
"Node runtime not installed" until you provide an Android-compatible ARM64 Node build (Settings → Node runtime). Official glibc Linux builds will not run.
