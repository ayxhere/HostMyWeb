package com.imayx.host.node
// Build trigger: wish v2 (cors, js-unzip) + multi-start

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import com.imayx.host.data.LogBus
import com.imayx.host.data.LogCategory
import java.io.File
import java.io.FileOutputStream
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipInputStream

/**
 * Owns the embedded AyX Group backend (Node.js + SQLite, running inside the app
 * process via [NodeBridge] — no Termux, no external Node binary needed).
 *
 * - The backend is bundled as `assets/ayx-backend.zip` and extracted on first run
 *   (or after an app update) to `filesDir/ayx-backend`.
 * - The long-lived API server (`node src/server.js`, port 3101) is started exactly
 *   once per OS process via [ensureStarted], guarded by an [AtomicBoolean].
 * - NOTE: wish-service integration was reverted (2026-10-13) — launcher.js caused
 *   startup crashes. Wish will be re-added after root cause is fixed.
 * - CRITICAL: `node::Start` can only be called ONCE per process — a second call
 *   trips a Node assertion and SIGABRTs the whole app. There is therefore NO
 *   API for running one-shot node scripts: all local operations (e.g. admin
 *   bootstrap) go through the token-guarded `/v1/local/` HTTP endpoints on the
 *   already-running server instead (see [bootstrapAdmin]).
 * - SESSION_SECRET and LOCAL_OP_TOKEN are generated once (SecureRandom, 32 bytes
 *   hex each) and kept in EncryptedSharedPreferences; the SQLite DB lives at
 *   `filesDir/ayx-backend/data/ayx.db`. None are ever logged.
 */
object AyxBackendRuntime {
    const val PORT = 3101
    private const val PREFS_FILE = "ayx_backend"
    private const val KEY_SESSION_SECRET = "session_secret"
    private const val KEY_LOCAL_OP_TOKEN = "local_op_token"

    private val startAttempted = AtomicBoolean(false)
    private val serverAlive = AtomicBoolean(false)
    @Volatile private var backendDir: File? = null

    /** Starts the API server if it isn't running. Safe to call repeatedly. */
    fun ensureStarted(ctx: Context) {
        if (!NodeBridge.available) {
            LogBus.w(LogCategory.NODE, "Embedded node not bundled (libnode.so missing) — AyX backend disabled")
            return
        }
        // node::Start can only be called ONCE per OS process — a second call trips
        // a Node assertion and SIGABRTs the whole app (the C++ bridge also refuses
        // it). So this is one-shot: if node exits, we log and stay down until the
        // app process restarts. Never reset this flag to "retry".
        if (!startAttempted.compareAndSet(false, true)) {
            LogBus.w(LogCategory.NODE, "Backend start already attempted in this process — not retrying")
            return
        }
        val dir = prepare(ctx) ?: return
        backendDir = dir
        applyEnv(ctx, dir)
        Thread({
            LogBus.i(LogCategory.NODE, "starting AyX backend + wish: ${dir.absolutePath}/launcher.mjs")
            serverAlive.set(true)
            try {
                val rc = NodeBridge.nativeStart(arrayOf("node", "launcher.mjs"), dir.absolutePath)
                if (rc == -99) {
                    LogBus.e(LogCategory.NODE, "Backend start refused: node already started in this process")
                } else {
                    LogBus.w(LogCategory.NODE, "AyX backend exited rc=$rc — see node.log tail: ${nodeLogTail(ctx).takeLast(500)}")
                }
            } catch (e: Throwable) {
                LogBus.e(LogCategory.NODE, "AyX backend failed to start: ${e.message}")
            } finally {
                serverAlive.set(false)
            }
        }, "ayx-backend").start()
    }

    /**
     * Creates the FIRST AyX backend admin account via the token-guarded
     * `POST /v1/local/admin/bootstrap` endpoint on the already-running server.
     * This MUST go over HTTP: starting a second node process in this OS process
     * would trip Node's one-start-per-process assertion and crash the app.
     * [onDone] is invoked once with the outcome. The password is sent only in
     * the request body and is never stored or logged.
     */
    fun bootstrapAdmin(
        ctx: Context,
        username: String,
        password: String,
        onDone: (ok: Boolean, message: String) -> Unit
    ) {
        Thread({
            try {
                // Don't gate on isServerRunning(): just try the loopback HTTP call.
                // If node is up, this succeeds even if our liveness flag is stale;
                // if it's down we get a fast connection-refused.
                val url = java.net.URL("http://127.0.0.1:$PORT/v1/local/admin/bootstrap")
                val conn = (url.openConnection() as java.net.HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 15000
                    readTimeout = 15000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("x-local-token", localOpToken(ctx))
                }
                val body = org.json.JSONObject()
                    .put("username", username)
                    .put("password", password)
                    .toString()
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                val resp = try {
                    conn.inputStream.bufferedReader().readText()
                } catch (_: Exception) {
                    conn.errorStream?.bufferedReader()?.readText().orEmpty()
                }
                when (code) {
                    201 -> onDone(true, "Admin \"$username\" created.")
                    409 -> onDone(false, "An admin account already exists.")
                    403 -> onDone(false, "Not authorized.")
                    else -> onDone(false, "Server returned $code: ${resp.take(200)}")
                }
            } catch (e: java.net.ConnectException) {
                val tail = nodeLogTail(ctx).takeLast(800)
                onDone(false, "Backend is not running — start hosting first, then create the admin account.\n\nNode log:\n$tail")
            } catch (e: Exception) {
                onDone(false, "Could not reach the backend: ${e.message}")
            }
        }, "ayx-admin-bootstrap").start()
    }

    /** True while the API server thread is up. */
    fun isServerRunning(): Boolean = serverAlive.get()

    /** Absolute path of the extracted backend dir, or null if not prepared yet. */
    fun dir(): File? = backendDir

    /** Last ~2KB of the embedded node's log (node.log), for on-device diagnostics. */
    fun nodeLogTail(ctx: Context): String {
        return try {
            val f = File(File(ctx.filesDir, "ayx-backend"), "node.log")
            if (!f.exists()) return "(no node log yet)"
            val bytes = f.readBytes()
            val tail = if (bytes.size > 2048) bytes.copyOfRange(bytes.size - 2048, bytes.size) else bytes
            tail.toString(Charsets.UTF_8)
        } catch (e: Exception) {
            "(could not read node log: ${e.message})"
        }
    }

    // ------------------------------------------------------------------ internals

    private fun prepare(ctx: Context): File? {
        return try {
            val dir = File(ctx.filesDir, "ayx-backend")
            extractIfNeeded(ctx, dir)
            File(dir, "data").mkdirs()
            File(dir, "uploads").mkdirs()
            extractWishIfNeeded(ctx)
            writeLauncherIfNeeded(dir)
            dir
        } catch (e: Exception) {
            LogBus.e(LogCategory.NODE, "Backend prepare failed: ${e.message}")
            null
        }
    }

    private fun writeLauncherIfNeeded(backendDir: File) {
        // Launcher runs wish (CJS) + AyX backend (ESM) in the single Node process.
        // CRITICAL: backend package.json has "type":"module", so it MUST be loaded
        // via import(), not require(). Using .mjs extension forces ESM mode.
        val launcher = File(backendDir, "launcher.mjs")
        // Delete old .js launcher if present
        File(backendDir, "launcher.js").delete()
        val content = """
            |// Auto-generated by IMAYX Host — do not edit.
            |// Starts wish service (3102, CJS) + AyX backend (3101, ESM) in one Node process.
            |import path from 'path';
            |import { fileURLToPath, pathToFileURL } from 'url';
            |import { createRequire } from 'module';
            |
            |const __filename = fileURLToPath(import.meta.url);
            |const __dirname = path.dirname(__filename);
            |const require = createRequire(import.meta.url);
            |
            |// Prevent backend's process.exit(1) from killing wish
            |process.exit = (() => {
            |  const orig = process.exit.bind(process);
            |  return (code) => { console.error('[launcher] process.exit(' + code + ') blocked'); };
            |})();
            |
            |process.on('uncaughtException', (e) => console.error('[launcher] uncaught:', e?.message));
            |process.on('unhandledRejection', (e) => console.error('[launcher] unhandled:', e?.message));
            |
            |process.env.WISH_PORT = process.env.WISH_PORT || '3102';
            |process.env.WISH_BASE_URL = process.env.WISH_BASE_URL || 'https://wish.imayx.in';
            |
            |// Wish is CommonJS — use require
            |try {
            |  require(path.join(__dirname, '..', 'wish-service', 'src', 'server.js'));
            |  console.log('[launcher] wish service started');
            |} catch (e) {
            |  console.error('[launcher] wish failed:', e.message);
            |}
            |
            |// Backend is ESM ("type":"module") — MUST use import(), not require()
            |try {
            |  await import(pathToFileURL(path.join(__dirname, 'src', 'server.js')).href);
            |  console.log('[launcher] AyX backend started');
            |} catch (e) {
            |  console.error('[launcher] backend failed:', e.message);
            |}
        """.trimMargin()
        if (!launcher.exists() || launcher.readText() != content) {
            launcher.writeText(content)
            LogBus.i(LogCategory.NODE, "launcher.mjs written (ESM-aware)")
        }
    }

    private fun applyEnv(ctx: Context, dir: File) {
        val cache = File(ctx.cacheDir, "ayx-node-cache").apply { mkdirs() }
        NodeBridge.nativeSetenv("TMPDIR", ctx.cacheDir.absolutePath)
        NodeBridge.nativeSetenv("HOME", ctx.filesDir.absolutePath)
        NodeBridge.nativeSetenv("NODE_COMPILE_CACHE", cache.absolutePath)
        // V8 sizes old-space from total RAM; cap it so the OS doesn't OOM-kill the app.
        NodeBridge.nativeSetenv("NODE_OPTIONS", "--max-old-space-size-percentage=50")
        NodeBridge.nativeSetenv("NODE_ENV", "production")
        NodeBridge.nativeSetenv("PORT", PORT.toString())
        NodeBridge.nativeSetenv("DB_PATH", File(dir, "data/ayx.db").absolutePath)
        NodeBridge.nativeSetenv("UPLOAD_DIR", File(dir, "uploads").absolutePath)
        NodeBridge.nativeSetenv("SESSION_SECRET", sessionSecret(ctx))
        NodeBridge.nativeSetenv("LOCAL_OP_TOKEN", localOpToken(ctx))
        NodeBridge.nativeSetenv("API_PUBLIC_BASE", "https://api.imayx.in")
        NodeBridge.nativeSetenv("WISH_PORT", "3102")
        NodeBridge.nativeSetenv("WISH_BASE_URL", "https://wish.imayx.in")
    }

    private fun sessionSecret(ctx: Context): String =
        getOrCreateHexSecret(ctx, KEY_SESSION_SECRET)

    private fun localOpToken(ctx: Context): String =
        getOrCreateHexSecret(ctx, KEY_LOCAL_OP_TOKEN)

    private fun getOrCreateHexSecret(ctx: Context, key: String): String {
        val alias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
        val prefs = EncryptedSharedPreferences.create(
            PREFS_FILE,
            alias,
            ctx.applicationContext,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
        prefs.getString(key, null)?.let { return it }
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        val hex = bytes.joinToString("") { "%02x".format(it) }
        prefs.edit().putString(key, hex).apply()
        return hex
    }

    private fun extractWishIfNeeded(ctx: Context) {
        val dir = File(ctx.filesDir, "wish-service")
        val marker = File(dir, ".version")
        val current = ctx.packageManager.getPackageInfo(ctx.packageName, 0).lastUpdateTime.toString()
        if (marker.exists() && marker.readText() == current) return
        LogBus.i(LogCategory.NODE, "extracting wish-service.zip (v=$current)")
        dir.deleteRecursively()
        dir.mkdirs()
        ctx.assets.open("wish-service.zip").use { raw ->
            ZipInputStream(raw).use { zip ->
                var e = zip.nextEntry
                val buf = ByteArray(64 * 1024)
                while (e != null) {
                    val out = File(dir, e.name)
                    require(out.canonicalPath.startsWith(dir.canonicalPath + File.separator)) {
                        "Refusing zip entry outside wish dir: ${e.name}"
                    }
                    if (e.isDirectory) { out.mkdirs() } else {
                        out.parentFile?.mkdirs()
                        FileOutputStream(out).use { fos ->
                            var n = zip.read(buf)
                            while (n >= 0) { fos.write(buf, 0, n); n = zip.read(buf) }
                        }
                    }
                    zip.closeEntry(); e = zip.nextEntry
                }
            }
        }
        File(dir, "storage").mkdirs()
        marker.writeText(current)
        LogBus.i(LogCategory.NODE, "wish-service extraction done")
    }

    private fun extractIfNeeded(ctx: Context, dir: File) {
        val marker = File(dir, ".version")
        val current = ctx.packageManager.getPackageInfo(ctx.packageName, 0).lastUpdateTime.toString()
        if (marker.exists() && marker.readText() == current) return

        LogBus.i(LogCategory.NODE, "extracting ayx-backend.zip (v=$current)")
        dir.deleteRecursively()
        dir.mkdirs()
        ctx.assets.open("ayx-backend.zip").use { raw ->
            ZipInputStream(raw).use { zip ->
                var e = zip.nextEntry
                val buf = ByteArray(64 * 1024)
                while (e != null) {
                    // Zip Slip guard: entries must stay inside dir.
                    val out = File(dir, e.name)
                    require(out.canonicalPath.startsWith(dir.canonicalPath + File.separator)) {
                        "Refusing zip entry outside backend dir: ${e.name}"
                    }
                    if (e.isDirectory) {
                        out.mkdirs()
                    } else {
                        out.parentFile?.mkdirs()
                        FileOutputStream(out).use { fos ->
                            var n = zip.read(buf)
                            while (n >= 0) {
                                fos.write(buf, 0, n)
                                n = zip.read(buf)
                            }
                        }
                    }
                    zip.closeEntry()
                    e = zip.nextEntry
                }
            }
        }
        marker.writeText(current)
        LogBus.i(LogCategory.NODE, "backend extraction done")
    }
}
