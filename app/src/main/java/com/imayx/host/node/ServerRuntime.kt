package com.imayx.host.node

import android.content.Context
import androidx.core.content.ContextCompat
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import com.imayx.host.data.LogBus
import com.imayx.host.data.LogCategory
import com.imayx.host.service.ServerHostService
import java.io.File
import java.security.SecureRandom

/**
 * Runs SERVER projects ([com.imayx.host.data.RuntimeType.SERVER]) on the **embedded**
 * Node.js runtime (digidem libnode.so) inside a dedicated `:node_server` OS process.
 *
 * Why a separate process: `node::Start` can be called exactly once per OS process
 * (a second call trips a Node assertion and SIGABRTs the app). The main process
 * already uses its one call for the AyX backend + wish launcher, so user servers
 * get their own process — and inside it, one Node instance runs **all** server
 * projects together via a generated `launcher.mjs` (one tap starts everything).
 *
 * - Each server gets its own `PORT`, `HOST=127.0.0.1`, `NODE_ENV=production`,
 *   plus per-project env from the app's encrypted env store (SecretStore) —
 *   secrets never live in the project folder or the imported ZIP.
 * - `SESSION_SECRET` is auto-generated per project (Keystore-backed) when the
 *   user didn't provide one, so backends that require it boot with zero setup.
 * - `DB_PATH` / `UPLOAD_DIR` default to absolute paths inside the project dir.
 * - One failing server never takes the others down (per-server try/catch +
 *   a `process.exit` guard in the launcher).
 * - CPU at idle is ~0%: a single Node event loop, no polling anywhere.
 */
object ServerRuntime {

    private const val PREFS_FILE = "server_runtime"
    private const val KEY_SESSION_SECRET_PREFIX = "session_secret:"

    /** A server project to run, with its fully-resolved environment. */
    data class ServerSpec(
        val name: String,
        val dir: File,
        val entry: String,
        val port: Int,
        val env: Map<String, String>
    )

    fun isAvailable(): Boolean = NodeBridge.available

    fun serversDir(ctx: Context): File = File(ctx.filesDir, "node-servers")

    /**
     * Fail-fast checks before starting. Returns a user-facing error, or null when OK.
     * Catches the two mistakes that waste the most time: a wrong entry file and a
     * ZIP imported without its `node_modules`.
     */
    fun checkPrereqs(spec: ServerSpec): String? {
        if (!spec.dir.isDirectory) return "${spec.name}: project folder is missing"
        val entry = File(spec.dir, spec.entry)
        if (!isSafeRelative(spec.entry) || !entry.isFile) {
            return "${spec.name}: entry file \"${spec.entry}\" not found — fix it in Project → Settings"
        }
        if (File(spec.dir, "package.json").isFile && !File(spec.dir, "node_modules").isDirectory) {
            return "${spec.name}: node_modules is missing — the server cannot start without its " +
                "npm dependencies. Re-export the ZIP with node_modules (production install) and re-import."
        }
        return null
    }

    private fun isSafeRelative(p: String): Boolean =
        p.isNotBlank() && !p.startsWith("/") && !p.split('/').contains("..") && '\u0000' !in p

    /**
     * Builds the fully-resolved env for one server project:
     * secure per-project env (SecretStore) wins, then auto-generated/provided defaults.
     */
    fun buildEnv(ctx: Context, projectName: String, dir: File, secretEnv: Map<String, String>): Map<String, String> {
        val env = LinkedHashMap<String, String>()
        env["SESSION_SECRET"] = secretEnv["SESSION_SECRET"] ?: getOrCreateSessionSecret(ctx, projectName)
        env["DB_PATH"] = secretEnv["DB_PATH"] ?: File(dir, "data/ayx.db").absolutePath
        env["UPLOAD_DIR"] = secretEnv["UPLOAD_DIR"] ?: File(dir, "uploads").absolutePath
        // User-provided secrets override every default above (except nothing — all overridable).
        for ((k, v) in secretEnv) env[k] = v
        return env
    }

    private fun getOrCreateSessionSecret(ctx: Context, projectName: String): String {
        val alias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
        val prefs = EncryptedSharedPreferences.create(
            PREFS_FILE, alias, ctx.applicationContext,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
        val key = KEY_SESSION_SECRET_PREFIX + projectName
        prefs.getString(key, null)?.let { return it }
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        val hex = bytes.joinToString("") { "%02x".format(it) }
        prefs.edit().putString(key, hex).apply()
        return hex
    }

    /**
     * Writes `servers.json` + `launcher.mjs` for [specs] and (re)starts the
     * `:node_server` process. One call = "start everything together".
     * Returns a user-facing error, or null on success.
     */
    fun startServers(ctx: Context, specs: List<ServerSpec>): String? {
        if (!isAvailable()) {
            return "Embedded Node.js is not bundled in this build (libnode.so missing) — " +
                "server projects cannot start."
        }
        for (s in specs) {
            val err = checkPrereqs(s)
            if (err != null) return err
            File(s.dir, "data").mkdirs()
            File(s.dir, "uploads").mkdirs()
        }
        return try {
            val dir = serversDir(ctx).also { it.mkdirs() }
            writeManifest(dir, specs)
            writeLauncher(dir)
            ServerHostService.sync(ctx.applicationContext)
            LogBus.i(
                LogCategory.NODE,
                "Server runtime: starting ${specs.size} server(s): " +
                    specs.joinToString(", ") { "${it.name}(:${it.port})" }
            )
            null
        } catch (e: Exception) {
            "Could not start server runtime: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    /** Stops the `:node_server` process (its manifest is removed first). */
    fun stopServers(ctx: Context) {
        try {
            File(serversDir(ctx), "servers.json").delete()
        } catch (_: Exception) {
        }
        try {
            ServerHostService.stop(ctx.applicationContext)
        } catch (_: Exception) {
        }
        LogBus.i(LogCategory.NODE, "Server runtime stopped")
    }

    /** Names of the servers the `:node_server` process was last asked to run. */
    fun wantedServers(ctx: Context): List<String> {
        return try {
            val f = File(serversDir(ctx), "servers.json")
            if (!f.isFile) return emptyList()
            val json = org.json.JSONObject(f.readText())
            val arr = json.optJSONArray("servers") ?: return emptyList()
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("name")?.ifBlank { null } }
        } catch (_: Exception) {
            emptyList()
        }
    }

    // ------------------------------------------------------------------ internals

    private fun writeManifest(dir: File, specs: List<ServerSpec>) {
        val arr = org.json.JSONArray()
        for (s in specs) {
            val env = org.json.JSONObject()
            for ((k, v) in s.env) env.put(k, v)
            arr.put(
                org.json.JSONObject()
                    .put("name", s.name)
                    .put("dir", s.dir.absolutePath)
                    .put("entry", s.entry)
                    .put("port", s.port)
                    .put("env", env)
            )
        }
        val manifest = org.json.JSONObject().put("servers", arr).toString(1)
        val f = File(dir, "servers.json")
        // The manifest carries secrets (project env) — keep it app-private.
        if (f.exists()) f.delete()
        f.writeText(manifest)
    }

    private fun writeLauncher(dir: File) {
        val launcher = File(dir, "launcher.mjs")
        val content = """
            |// Auto-generated by IMAYX Host — do not edit.
            |// Runs every SERVER project in this single Node process (node::Start runs once per OS process).
            |import { readFileSync, appendFileSync, statSync, writeFileSync } from 'node:fs';
            |import path from 'node:path';
            |import { pathToFileURL } from 'node:url';
            |
            |const manifest = JSON.parse(readFileSync(new URL('./servers.json', import.meta.url), 'utf8'));
            |const LOG_FILE = new URL('./servers.log', import.meta.url);
            |
            |function log(line) {
            |  console.log(line);
            |  try {
            |    appendFileSync(LOG_FILE, new Date().toISOString() + ' ' + line + '\n');
            |    // Keep the log small: when it passes ~256KB, keep the newest half.
            |    const st = statSync(LOG_FILE);
            |    if (st.size > 262144) {
            |      const text = readFileSync(LOG_FILE, 'utf8');
            |      writeFileSync(LOG_FILE, text.slice(Math.floor(text.length / 2)));
            |    }
            |  } catch {}
            |}
            |
            |// One bad server must never kill the others.
            |process.exit = (() => {
            |  const orig = process.exit.bind(process);
            |  return (code) => { log('[server-host] process.exit(' + code + ') blocked'); };
            |})();
            |process.on('uncaughtException', (e) => log('[server-host] uncaught: ' + (e?.message || e)));
            |process.on('unhandledRejection', (e) => log('[server-host] unhandled rejection: ' + (e?.message || e)));
            |
            |// process.env is process-global, so servers are started strictly one at a time and
            |// the next import only happens after the current server's port accepts connections.
            |// (Servers that read PORT lazily after startup would otherwise see the next server's port.)
            |async function waitForPort(port, timeoutMs) {
            |  const net = await import('node:net');
            |  const start = Date.now();
            |  while (Date.now() - start < timeoutMs) {
            |    const ok = await new Promise((resolve) => {
            |      const s = net.createConnection({ host: '127.0.0.1', port }, () => { s.destroy(); resolve(true); });
            |      s.on('error', () => resolve(false));
            |      s.setTimeout(600, () => { try { s.destroy(); } catch {} resolve(false); });
            |    });
            |    if (ok) return true;
            |    await new Promise((r) => setTimeout(r, 250));
            |  }
            |  return false;
            |}
            |
            |for (const s of manifest.servers || []) {
            |  try {
            |    process.env.PORT = String(s.port);
            |    process.env.HOST = '127.0.0.1';
            |    process.env.NODE_ENV = 'production';
            |    for (const k of Object.keys(s.env || {})) process.env[k] = String(s.env[k]);
            |    try { process.chdir(s.dir); } catch (e) { log('[server-host] chdir failed for ' + s.name + ': ' + e.message); }
            |    await import(pathToFileURL(path.join(s.dir, s.entry)).href);
            |    log('[server-host] ' + s.name + ' imported — waiting for :' + s.port);
            |    const up = await waitForPort(s.port, 20000);
            |    log(up
            |      ? '[server-host] ' + s.name + ' running on :' + s.port
            |      : '[server-host] ' + s.name + ' did NOT open :' + s.port + ' within 20s');
            |  } catch (e) {
            |    log('[server-host] ' + s.name + ' failed to start: ' + (e?.stack || e?.message || e));
            |  }
            |}
            |
            |// Keep the process alive even if every server failed (so logs stay readable).
            |setInterval(() => {}, 1 << 30);
        """.trimMargin()
        if (!launcher.exists() || launcher.readText() != content) {
            launcher.writeText(content)
        }
    }

    /** Last ~2KB of the server runtime log, for on-device diagnostics. */
    fun serverLogTail(ctx: Context): String {
        return try {
            val f = File(serversDir(ctx), "servers.log")
            if (!f.exists()) return "(no server log yet)"
            val bytes = f.readBytes()
            val tail = if (bytes.size > 2048) bytes.copyOfRange(bytes.size - 2048, bytes.size) else bytes
            tail.toString(Charsets.UTF_8)
        } catch (e: Exception) {
            "(could not read server log: ${e.message})"
        }
    }

    /** Process-level env for the `:node_server` process (called there, before nativeStart). */
    fun applyNodeEnv(ctx: Context) {
        val cache = File(ctx.cacheDir, "node-server-cache").apply { mkdirs() }
        NodeBridge.nativeSetenv("TMPDIR", ctx.cacheDir.absolutePath)
        NodeBridge.nativeSetenv("HOME", ctx.filesDir.absolutePath)
        NodeBridge.nativeSetenv("NODE_COMPILE_CACHE", cache.absolutePath)
        // Cap V8 old-space so the OS never OOM-kills the app because of a server.
        NodeBridge.nativeSetenv("NODE_OPTIONS", "--max-old-space-size-percentage=50")
    }
}
