package com.imayx.host.ai

import com.imayx.host.data.AppSettings
import com.imayx.host.data.LogBus
import com.imayx.host.data.LogCategory
import com.imayx.host.node.ServerRuntime
import com.imayx.host.security.SecretStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Groq-powered auto-fixer for hosted SERVER (Node.js) projects.
 *
 * Two entry points:
 *  - Manual: user taps "AI Fix" on a server project card.
 *  - Automatic: [maybeAutoFix] is called when a server fails to start / crashes,
 *    and only runs when the user enabled "AI auto-fix" in Settings (plus a
 *    per-project cooldown so a broken project can't loop forever).
 *
 * Safety rules:
 *  - Only files INSIDE the project's own directory are ever touched
 *    (path traversal is rejected).
 *  - A full backup ZIP is created before any edit (restorable from Settings → Backups).
 *  - At most [MAX_EDITS] files and [MAX_FILE_BYTES] per file per run.
 *  - The Groq API key lives in the Keystore-backed [SecretStore], never in logs
 *    (it is registered with the log redactor).
 */
object AiFixer {
    private const val GROQ_URL = "https://api.groq.com/openai/v1/chat/completions"
    private const val MAX_EDITS = 10
    private const val MAX_FILE_BYTES = 200 * 1024
    private const val MAX_LOG_CHARS = 6000
    private const val MAX_TREE_FILES = 80
    /** Minimum gap between two automatic fixes of the same project. */
    private const val AUTO_COOLDOWN_MS = 10 * 60 * 1000L

    data class FixResult(
        val ok: Boolean,
        val summary: String,
        val editsApplied: Int,
        val backupName: String?
    )

    fun isConfigured(secrets: SecretStore): Boolean =
        !secrets.get(SecretStore.KEY_GROQ_API_KEY).isNullOrBlank()

    fun lastAutoFix(settings: AppSettings, project: String): Long =
        settings.prefsLong("aifix_last_$project", 0L)

    private fun markAutoFix(settings: AppSettings, project: String) =
        settings.putLong("aifix_last_$project", System.currentTimeMillis())

    /**
     * Automatic entry point. Returns null when auto-fix is disabled / not
     * configured / cooling down — the caller then just logs the original error.
     */
    suspend fun maybeAutoFix(
        ctx: android.content.Context,
        settings: AppSettings,
        secrets: SecretStore,
        backups: com.imayx.host.backup.BackupManager,
        projectStore: com.imayx.host.data.ProjectStore,
        projectName: String,
        reason: String
    ): FixResult? {
        if (!settings.aiAutoFix) return null
        if (!isConfigured(secrets)) {
            LogBus.w(LogCategory.NODE, "AI auto-fix skipped for $projectName: no Groq API key in Settings")
            return null
        }
        val since = System.currentTimeMillis() - lastAutoFix(settings, projectName)
        if (since < AUTO_COOLDOWN_MS) {
            LogBus.w(LogCategory.NODE, "AI auto-fix cooling down for $projectName (${(AUTO_COOLDOWN_MS - since) / 1000}s left)")
            return null
        }
        markAutoFix(settings, projectName)
        LogBus.i(LogCategory.NODE, "AI auto-fix triggered for $projectName: $reason")
        return fixProject(ctx, settings, secrets, backups, projectStore, projectName, reason)
    }

    /** Manual entry point (user tapped "AI Fix"). */
    suspend fun fixProject(
        ctx: android.content.Context,
        settings: AppSettings,
        secrets: SecretStore,
        backups: com.imayx.host.backup.BackupManager,
        projectStore: com.imayx.host.data.ProjectStore,
        projectName: String,
        reason: String,
        onProgress: (String) -> Unit = {}
    ): FixResult = withContext(Dispatchers.IO) {
        val key = secrets.get(SecretStore.KEY_GROQ_API_KEY)
        if (key.isNullOrBlank()) {
            return@withContext FixResult(false, "No Groq API key. Add one in Settings → AI auto-fix.", 0, null)
        }
        val dir = projectStore.projectDir(projectName)
        if (!dir.isDirectory) {
            return@withContext FixResult(false, "Project directory not found.", 0, null)
        }
        try {
            onProgress("Reading project…")
            val tree = fileTree(dir)
            val pkg = readTextCapped(File(dir, "package.json"), 3000)
            val entry = detectEntry(dir, pkg)
            val entrySrc = entry?.let { readTextCapped(File(dir, it), 12000) } ?: "(entry not found)"
            val logs = ServerRuntime.serverLogTail(ctx).takeLast(MAX_LOG_CHARS)

            onProgress("Asking Groq…")
            val reply = chat(
                key, settings.aiModel,
                SYSTEM_PROMPT,
                buildUserMessage(projectName, reason, tree, pkg, entry, entrySrc, logs)
            )
            val parsed = parseReply(reply)

            if (parsed.edits.isEmpty()) {
                val summary = "Groq analysis: ${parsed.analysis.take(500)} (no file edits suggested)"
                LogBus.i(LogCategory.NODE, "AI fix for $projectName: $summary")
                return@withContext FixResult(true, summary, 0, null)
            }

            onProgress("Backing up…")
            val backup = try {
                backups.create(projectName)
            } catch (e: Exception) {
                return@withContext FixResult(false, "Backup failed, refusing to edit: ${e.message}", 0, null)
            }

            onProgress("Applying ${parsed.edits.size} edit(s)…")
            var applied = 0
            for (e in parsed.edits) {
                val target = safeTarget(dir, e.path)
                if (target == null) {
                    LogBus.w(LogCategory.NODE, "AI fix rejected unsafe path: ${e.path}")
                    continue
                }
                if (e.content.length > MAX_FILE_BYTES) {
                    LogBus.w(LogCategory.NODE, "AI fix rejected oversized file: ${e.path}")
                    continue
                }
                target.parentFile?.mkdirs()
                target.writeText(e.content)
                applied++
                LogBus.i(LogCategory.NODE, "AI fix wrote ${e.path} (${e.content.length} chars)")
            }

            val summary = buildString {
                append("Applied $applied/${parsed.edits.size} edit(s). ")
                append("Analysis: ${parsed.analysis.take(400)}")
                if (parsed.notes.isNotBlank()) append(" Notes: ${parsed.notes.take(300)}")
            }
            LogBus.i(LogCategory.NODE, "AI fix for $projectName done: $summary (backup ${backup.file.name})")
            FixResult(true, summary, applied, backup.file.name)
        } catch (e: Exception) {
            val msg = "AI fix failed: ${e.message ?: e.javaClass.simpleName}"
            LogBus.e(LogCategory.NODE, "AI fix for $projectName: $msg")
            FixResult(false, msg, 0, null)
        }
    }

    // ------------------------------------------------------------------ prompt

    private val SYSTEM_PROMPT = """
You are an expert Node.js backend engineer fixing a server that runs ON AN ANDROID PHONE inside the IMAYX Host app, using the phone's embedded Node.js runtime (Node 24, no npm at runtime — node_modules is pre-bundled inside the project).

Environment facts:
- The server is started by a generated launcher that sets PORT, HOST (127.0.0.1), DB_PATH, UPLOAD_DIR and SESSION_SECRET env vars, then imports the entry file. Never hardcode a port; always use process.env.PORT.
- package.json may declare "type": "module" — then use ESM `import`, never `require` (a .cjs file may use require via createRequire).
- There is no network install step: DO NOT add new npm dependencies. Only use packages already in node_modules.
- SQLite is via the node:sqlite built-in. The database file lives at process.env.DB_PATH.
- Keep changes minimal: fix the reported error, don't redesign, don't rename files, don't change the public API.

You receive: the project file tree, package.json, the entry file, and the tail of the runtime log showing the error.

Reply with STRICT JSON only, no markdown, no code fences, matching this schema:
{
  "analysis": "one-paragraph root cause",
  "edits": [ { "path": "relative/path.js", "content": "FULL new file content" } ],
  "restart": true,
  "notes": "anything the user should know"
}
Rules for edits: paths are relative to the project root (never absolute, never containing ..). "content" must be the COMPLETE new file, not a diff. If no edit can fix it, return an empty edits array and explain in analysis.
""".trimIndent()

    private fun buildUserMessage(
        project: String, reason: String, tree: String, pkg: String,
        entry: String?, entrySrc: String, logs: String
    ): String = """
Project: $project
Trigger: $reason

File tree (node_modules omitted, it is pre-bundled):
$tree

package.json:
$pkg

Entry file (${entry ?: "unknown"}):
$entrySrc

Runtime log tail (the error):
$logs
""".trimIndent()

    // ------------------------------------------------------------------ groq

    private data class ParsedReply(val analysis: String, val edits: List<Edit>, val notes: String)
    private data class Edit(val path: String, val content: String)

    private fun chat(apiKey: String, model: String, system: String, user: String): String {
        val body = JSONObject()
            .put("model", model)
            .put("temperature", 0.2)
            .put("max_tokens", 8000)
            .put("response_format", JSONObject().put("type", "json_object"))
            .put(
                "messages", JSONArray()
                    .put(JSONObject().put("role", "system").put("content", system))
                    .put(JSONObject().put("role", "user").put("content", user))
            )
        val url = URL(GROQ_URL)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 30_000
            readTimeout = 120_000
            doOutput = true
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Content-Type", "application/json")
        }
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.readText() ?: ""
            if (code !in 200..299) throw IllegalStateException("Groq HTTP $code: ${text.take(300)}")
            val content = JSONObject(text)
                .getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").getString("content")
            return content
        } finally {
            conn.disconnect()
        }
    }

    private fun parseReply(raw: String): ParsedReply {
        // Tolerate stray text around the JSON.
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        val json = if (start >= 0 && end > start) raw.substring(start, end + 1) else raw
        val o = JSONObject(json)
        val edits = mutableListOf<Edit>()
        val arr = o.optJSONArray("edits") ?: JSONArray()
        for (i in 0 until minOf(arr.length(), MAX_EDITS)) {
            val e = arr.optJSONObject(i) ?: continue
            val path = e.optString("path", "").trim()
            val content = e.optString("content", "")
            if (path.isNotEmpty() && content.isNotEmpty()) edits.add(Edit(path, content))
        }
        return ParsedReply(
            analysis = o.optString("analysis", "no analysis given"),
            edits = edits,
            notes = o.optString("notes", "")
        )
    }

    // ------------------------------------------------------------------ files

    private fun fileTree(dir: File): String {
        val out = StringBuilder()
        var count = 0
        fun walk(f: File, depth: Int) {
            if (count >= MAX_TREE_FILES || depth > 4) return
            val name = f.name
            if (name == "node_modules" || name == ".git") {
                out.append("  ".repeat(depth)).append(name).append("/ (omitted)\n")
                count++
                return
            }
            out.append("  ".repeat(depth)).append(name).append(if (f.isDirectory) "/" else "").append('\n')
            count++
            if (f.isDirectory) {
                f.listFiles()?.sortedBy { it.name }?.forEach { walk(it, depth + 1) }
            }
        }
        dir.listFiles()?.sortedBy { it.name }?.forEach { walk(it, 0) }
        out.append("node_modules present: ${File(dir, "node_modules").isDirectory}\n")
        return out.toString()
    }

    private fun readTextCapped(f: File, max: Int): String {
        if (!f.isFile) return "(missing)"
        val t = f.readText()
        return if (t.length > max) t.take(max) + "\n…(truncated)" else t
    }

    private fun detectEntry(dir: File, pkgJson: String): String? {
        return try {
            val pkg = JSONObject(pkgJson)
            val start = pkg.optJSONObject("scripts")?.optString("start")
            if (!start.isNullOrBlank()) {
                val m = Regex("""(?:node\s+)?([^\s;"']+\.m?js)""").find(start)
                if (m != null) return m.groupValues[1]
            }
            val main = pkg.optString("main")
            if (main.isNotBlank() && File(dir, main).isFile) return main
            null
        } catch (_: Exception) {
            null
        } ?: listOf("server.js", "src/server.js", "index.js", "src/index.js", "app.js", "src/app.js", "main.js")
            .firstOrNull { File(dir, it).isFile }
    }

    /** Resolve an AI-proposed path safely inside the project dir; null = rejected. */
    private fun safeTarget(dir: File, path: String): File? {
        if (path.isBlank() || path.startsWith("/") || path.contains("..")) return null
        val clean = path.trim().trimStart('/')
        if (clean.isBlank() || clean.contains("..")) return null
        val base = dir.canonicalFile
        val target = File(base, clean).canonicalFile
        return if (target.path.startsWith(base.path + File.separator) || target.path == base.path) target else null
    }
}
