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
 * Multi-provider AI auto-fixer for hosted SERVER (Node.js) projects.
 *
 * Providers: Groq, Google Gemini and OpenRouter (see [AiProvider]) — all speak
 * the OpenAI chat format, so one client serves all three. Every call fails over
 * automatically: the active provider first (its selected model, then its other
 * default models), then the remaining providers the same way.
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
 *  - The API keys live in the Keystore-backed [SecretStore], never in logs
 *    (they are registered with the log redactor).
 */
object AiFixer {
    private const val MAX_EDITS = 10
    private const val FIX_MAX_TOKENS = 8000
    private const val TEST_MAX_TOKENS = 16
    private const val TEST_SYSTEM = "You are a connectivity test inside an Android app. Reply with strict JSON only: {\"ok\":true}"
    private const val MAX_FILE_BYTES = 200 * 1024
    private const val MAX_LOG_CHARS = 6000
    /** File-tree budget for AI prompts. Was 80 — a real bundle (site + backend +
     *  otp ≈ 103 entries) silently lost its tail (whole server folders) at 80,
     *  so the AI "read" a project that was missing servers. 1000 entries ≈ 30KB
     *  of prompt — affordable, and truncation is now MARKED in the tree itself. */
    private const val MAX_TREE_FILES = 1000
    /** Minimum gap between two automatic fixes of the same project. */
    private const val AUTO_COOLDOWN_MS = 10 * 60 * 1000L

    data class FixResult(
        val ok: Boolean,
        val summary: String,
        val editsApplied: Int,
        val backupName: String?,
        /** Short "where": file:line (or entry) the error pointed at. */
        val where: String = "",
        val files: List<String> = emptyList(),
        /** "Provider · model" that produced the reply ("" when no call was made). */
        val via: String = ""
    )

    /** Providers that currently have an API key saved. */
    fun configuredProviders(secrets: SecretStore): List<AiProvider> =
        AiProvider.entries.filter { !secrets.get(it.secretKeyName).isNullOrBlank() }

    fun isConfigured(secrets: SecretStore): Boolean = configuredProviders(secrets).isNotEmpty()

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
        reason: String,
        spec: ServerRuntime.ServerSpec? = null,
        logOverride: String? = null,
        cooldownMs: Long = AUTO_COOLDOWN_MS
    ): FixResult? {
        if (!settings.aiAutoFix) return null
        if (!isConfigured(secrets)) {
            LogBus.w(LogCategory.NODE, "AI auto-fix skipped for $projectName: no AI API key in Settings")
            return null
        }
        val cdKey = spec?.name ?: projectName
        val since = System.currentTimeMillis() - lastAutoFix(settings, cdKey)
        if (since < cooldownMs) {
            LogBus.w(LogCategory.NODE, "AI auto-fix cooling down for $cdKey (${(cooldownMs - since) / 1000}s left)")
            return null
        }
        markAutoFix(settings, cdKey)
        LogBus.i(LogCategory.NODE, "AI auto-fix triggered for $cdKey: ${reason.take(200)}")
        return fixProject(ctx, settings, secrets, backups, projectStore, projectName, reason, spec = spec, logOverride = logOverride)
    }

    /** Manual entry point (user tapped "AI Fix") and the worker behind auto-fix. */
    suspend fun fixProject(
        ctx: android.content.Context,
        settings: AppSettings,
        secrets: SecretStore,
        backups: com.imayx.host.backup.BackupManager,
        projectStore: com.imayx.host.data.ProjectStore,
        projectName: String,
        reason: String,
        onProgress: (String) -> Unit = {},
        spec: ServerRuntime.ServerSpec? = null,
        logOverride: String? = null
    ): FixResult = withContext(Dispatchers.IO) {
        val label = spec?.name ?: projectName
        if (!isConfigured(secrets)) {
            return@withContext FixResult(false, "No AI API key. Add one in Settings → AI (Groq, Google Gemini or OpenRouter).", 0, null)
        }
        val projectDir = projectStore.projectDir(projectName)
        if (!projectDir.isDirectory) {
            return@withContext FixResult(false, "Project directory not found.", 0, null)
        }
        // Multi-server: everything (tree, entry, edits) is relative to THIS server's folder.
        val dir = spec?.dir?.takeIf { it.isDirectory } ?: projectDir
        try {
            onProgress("Reading project…")
            val tree = fileTree(dir)
            val pkg = readTextCapped(File(dir, "package.json"), 3000)
            val entry = spec?.entry ?: detectEntry(dir, pkg)
            val entrySrc = entry?.let { readTextCapped(File(dir, it), 12000) } ?: "(entry not found)"
            val logs = (logOverride ?: ServerRuntime.serverLogTail(ctx)).takeLast(MAX_LOG_CHARS)

            // Files the stack trace points at (the bug is rarely in the entry file).
            val refs = stackRefs(logs, dir)
            val extra = StringBuilder()
            for ((rel, _) in refs.filter { it.first != entry }.take(3)) {
                extra.append("\n--- ").append(rel).append(" ---\n").append(readTextCapped(File(dir, rel), 8000)).append('\n')
            }
            val where = refs.firstOrNull()?.let { "${it.first}:${it.second}" } ?: (entry ?: "unknown")
            val issue = firstErrorLine(logs)

            onProgress("Asking AI…")
            val outcome = chat(
                settings, secrets,
                SYSTEM_PROMPT,
                buildUserMessage(label, reason, tree, pkg, entry, entrySrc, logs) +
                    (if (extra.isNotEmpty()) "\nOther files referenced by the error:$extra" else "")
            )
            val via = "${outcome.provider.label} · ${outcome.model}"
            val parsed = parseReply(outcome.reply)

            if (parsed.edits.isEmpty()) {
                val summary = "${outcome.provider.label} analysis: ${parsed.analysis.take(500)} (no file edits suggested)"
                LogBus.i(LogCategory.NODE, "AI fix for $label: $summary")
                val r = FixResult(true, summary, 0, null, where, via = via)
                postResult(ctx, label, where, issue, r)
                return@withContext r
            }

            onProgress("Backing up…")
            val backup = try {
                backups.create(projectName)
            } catch (e: Exception) {
                return@withContext FixResult(false, "Backup failed, refusing to edit: ${e.message}", 0, null, where)
            }

            onProgress("Applying ${parsed.edits.size} edit(s)…")
            var applied = 0
            val written = ArrayList<String>()
            for (e in parsed.edits) {
                val target = safeTarget(dir, e.path)
                if (target == null) {
                    LogBus.w(LogCategory.NODE, "AI fix rejected unsafe path: ${e.path}")
                    continue
                }
                if (e.path.trim().trimStart('/').startsWith("node_modules")) {
                    LogBus.w(LogCategory.NODE, "AI fix refused to edit node_modules: ${e.path}")
                    continue
                }
                if (e.content.length > MAX_FILE_BYTES) {
                    LogBus.w(LogCategory.NODE, "AI fix rejected oversized file: ${e.path}")
                    continue
                }
                target.parentFile?.mkdirs()
                target.writeText(e.content)
                applied++
                written.add(e.path)
                LogBus.i(LogCategory.NODE, "AI fix wrote ${e.path} (${e.content.length} chars)")
            }

            val summary = buildString {
                append("Applied $applied/${parsed.edits.size} edit(s). ")
                append("Analysis: ${parsed.analysis.take(400)}")
                if (parsed.notes.isNotBlank()) append(" Notes: ${parsed.notes.take(300)}")
            }
            LogBus.i(LogCategory.NODE, "AI fix for $label done: $summary (backup ${backup.file.name})")
            val r = FixResult(true, summary, applied, backup.file.name, where, written, via)
            postResult(ctx, label, where, issue, r)
            r
        } catch (e: Exception) {
            val msg = e.message ?: e.javaClass.simpleName
            LogBus.e(LogCategory.NODE, "AI fix for $label: $msg")
            val r = FixResult(false, msg, 0, null)
            postResult(ctx, label, "", firstErrorLine(logOverride ?: ""), r)
            r
        }
    }

    /** Notification: where the problem was, what it was, what the AI changed. */
    private fun postResult(ctx: android.content.Context, label: String, where: String, issue: String, r: FixResult) {
        val title = when {
            !r.ok -> "AI fix failed · $label"
            r.editsApplied > 0 -> "AI fixed · $label"
            else -> "AI checked · $label"
        }
        val text = buildString {
            if (where.isNotBlank()) append("Where: ").append(where).append('\n')
            if (issue.isNotBlank()) append("Issue: ").append(issue.take(220)).append('\n')
            if (r.via.isNotBlank()) append("Via: ").append(r.via).append('\n')
            append(if (r.ok) "Fix: " else "Result: ").append(r.summary.take(420))
            if (r.files.isNotEmpty()) append("\nEdited: ").append(r.files.joinToString())
            if (r.backupName != null) append("\nBackup: ").append(r.backupName)
        }
        AiNotifier.post(ctx, title, text)
    }

    private val stackRx = Regex("""(/[^\s:()|'"]+\.(?:m?js|cjs|json)):(\d+)""")

    /** (relative path, line) pairs from the log that live inside [dir] (never node_modules). */
    private fun stackRefs(logs: String, dir: File): List<Pair<String, Int>> {
        val base = try { dir.canonicalPath } catch (_: Exception) { return emptyList() }
        val seen = LinkedHashMap<String, Int>()
        for (m in stackRx.findAll(logs)) {
            val path = m.groupValues[1]
            if (path.contains("/node_modules/") || !path.startsWith("$base/")) continue
            val rel = path.removePrefix("$base/")
            if (File(dir, rel).isFile) seen.putIfAbsent(rel, m.groupValues[2].toIntOrNull() ?: 0)
        }
        return seen.map { it.key to it.value }
    }

    private fun firstErrorLine(logs: String): String =
        logs.lineSequence().lastOrNull {
            val l = it.lowercase()
            l.contains("error") || l.contains("uncaught") || l.contains("failed to start") || l.contains("did not open")
        }?.substringAfter("[server-host] ")?.take(300).orEmpty()

    /**
     * "AI read" detection: when the scanner finds no server (unusual layout), the AI reads the file tree and
     * every package.json and says which folders are servers. Answers are validated against real files.
     */
    suspend fun detectServers(
        ctx: android.content.Context,
        settings: AppSettings,
        secrets: SecretStore,
        root: File
    ): List<com.imayx.host.projects.ServerScanner.Found> = withContext(Dispatchers.IO) {
        if (!isConfigured(secrets) || !root.isDirectory) return@withContext emptyList()
        try {
            val tree = fileTree(root)
            val pk = StringBuilder()
            var n = 0
            root.walkTopDown().maxDepth(4)
                .onEnter { it.name != "node_modules" && it.name != ".git" }
                .filter { it.isFile && it.name == "package.json" }
                .forEach {
                    if (n++ < 8) pk.append("--- ").append(it.relativeTo(root).path).append(" ---\n")
                        .append(readTextCapped(it, 1500)).append('\n')
                }
            val sys = """
You inspect a ZIP of Node.js code and list every independent HTTP server inside it.
Each server is one folder with one entry file (plain JavaScript run with `node`, .js/.mjs/.cjs only).
Reply STRICT JSON only: {"servers":[{"dir":"relative/folder or empty string for the root","entry":"file relative to dir"}],"note":"short"}
Never include node_modules, tests, frontends without a server, or build output. Paths must exist in the tree and never contain "..".
""".trimIndent()
            val reply = chat(settings, secrets, sys, "File tree:\n$tree\n\npackage.json files:\n$pk").reply
            val st = reply.indexOf('{'); val en = reply.lastIndexOf('}')
            val arr = JSONObject(if (st >= 0 && en > st) reply.substring(st, en + 1) else reply).optJSONArray("servers") ?: JSONArray()
            val out = ArrayList<com.imayx.host.projects.ServerScanner.Found>()
            for (i in 0 until minOf(arr.length(), 12)) {
                val o = arr.optJSONObject(i) ?: continue
                val dir = o.optString("dir", "").trim().trim('/')
                val entry = o.optString("entry", "").trim().trimStart('/')
                if (dir.contains("..") || entry.contains("..") || entry.isBlank()) continue
                if (!(entry.endsWith(".js") || entry.endsWith(".mjs") || entry.endsWith(".cjs"))) continue
                if (!File(if (dir.isEmpty()) root else File(root, dir), entry).isFile) continue
                if (out.none { it.dir == dir }) out.add(com.imayx.host.projects.ServerScanner.Found(dir, entry))
            }
            LogBus.i(LogCategory.NODE, "AI detect: ${out.size} server(s) " + out.joinToString { "${it.dir.ifEmpty { "(root)" }}/${it.entry}" })
            out
        } catch (e: Exception) {
            LogBus.e(LogCategory.NODE, "AI detect failed: ${e.message}")
            emptyList()
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
- The project may contain several servers; you only see ONE server folder. Paths in "edits" are relative to that server folder.
- Several servers run in one process: never call process.exit(), never bind a fixed port, never change process.env.PORT globally after startup (read it once at the top).

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

    // ------------------------------------------------------------------ providers

    private data class ParsedReply(val analysis: String, val edits: List<Edit>, val notes: String)
    private data class Edit(val path: String, val content: String)

    /** One (provider, model, key) attempt, in failover order. */
    private data class Candidate(val provider: AiProvider, val model: String, val key: String)

    private data class ChatOutcome(val reply: String, val provider: AiProvider, val model: String)

    data class TestResult(
        val ok: Boolean,
        val provider: AiProvider,
        /** The model that answered (and was auto-selected); null when every model failed. */
        val model: String?,
        val latencyMs: Long,
        val message: String
    )

    /** Failover order: the active provider first, then the rest in enum order (Groq → Google → OpenRouter). */
    fun failoverOrder(settings: AppSettings): List<AiProvider> {
        val active = AiProvider.byId(settings.aiActiveProvider)
        return listOf(active) + AiProvider.entries.filter { it != active }
    }

    /**
     * Attempts in order: for each provider in failover order (skipping ones with
     * no saved key), the user's selected model first, then the provider's other
     * default models.
     */
    private fun candidates(settings: AppSettings, secrets: SecretStore): List<Candidate> {
        val out = ArrayList<Candidate>()
        for (p in failoverOrder(settings)) {
            val key = secrets.get(p.secretKeyName)?.takeIf { it.isNotBlank() } ?: continue
            val models = (listOf(settings.aiModelFor(p.id, p.defaultModels.first())) + p.defaultModels).distinct()
            for (m in models) out.add(Candidate(p, m, key))
        }
        return out
    }

    /**
     * One chat call with automatic failover across providers and models. The first
     * success wins and says which provider+model produced it. A 401/403 marks the
     * whole provider dead for this call (bad key — its other models would fail the
     * same way). When every candidate fails, the thrown error lists each attempt
     * briefly so the user can see why nothing answered.
     */
    private fun chat(settings: AppSettings, secrets: SecretStore, system: String, user: String): ChatOutcome {
        val cands = candidates(settings, secrets)
        if (cands.isEmpty()) throw IllegalStateException("No AI API key configured. Add one in Settings → AI.")
        val deadProviders = HashSet<AiProvider>()
        val attempts = ArrayList<String>()
        var last: Exception? = null
        for (cand in cands) {
            if (cand.provider in deadProviders) continue
            try {
                val reply = chatOnce(
                    cand.provider, cand.key, cand.model, system, user,
                    FIX_MAX_TOKENS, jsonMode = cand.provider == AiProvider.GROQ
                )
                LogBus.i(LogCategory.NODE, "AI reply via ${cand.provider.label} / ${cand.model}")
                return ChatOutcome(reply, cand.provider, cand.model)
            } catch (e: Exception) {
                last = e
                val msg = e.message ?: e.javaClass.simpleName
                attempts.add("${cand.provider.label}/${cand.model}: ${msg.take(140)}")
                if (msg.contains("HTTP 401") || msg.contains("HTTP 403")) deadProviders.add(cand.provider)
                LogBus.w(LogCategory.NODE, "AI attempt failed (${cand.provider.label}/${cand.model}): ${msg.take(200)}")
                if (msg.contains("HTTP 429") || msg.contains("HTTP 5")) {
                    try { Thread.sleep(1200) } catch (_: InterruptedException) { break }
                }
            }
        }
        throw IllegalStateException("All AI providers failed. " + attempts.joinToString(" · ").take(700), last)
    }

    /**
     * Settings "Test" for one provider: a minimal ping against its selected model,
     * then its other default models. The first model that answers is SAVED as that
     * provider's selected model (auto-select) and reported with its latency.
     */
    suspend fun testProvider(settings: AppSettings, secrets: SecretStore, provider: AiProvider): TestResult =
        withContext(Dispatchers.IO) {
            val key = secrets.get(provider.secretKeyName)
            if (key.isNullOrBlank()) {
                return@withContext TestResult(false, provider, null, 0, "No ${provider.label} API key saved.")
            }
            val models = (listOf(settings.aiModelFor(provider.id, provider.defaultModels.first())) + provider.defaultModels).distinct()
            var lastMsg = "unknown error"
            for (m in models) {
                val t0 = System.currentTimeMillis()
                try {
                    chatOnce(provider, key, m, TEST_SYSTEM, "ping", TEST_MAX_TOKENS, jsonMode = provider == AiProvider.GROQ)
                    val dt = System.currentTimeMillis() - t0
                    settings.setAiModelFor(provider.id, m)
                    LogBus.i(LogCategory.NODE, "AI test OK: ${provider.label} / $m (${dt}ms) — auto-selected")
                    return@withContext TestResult(
                        true, provider, m, dt,
                        "${provider.label} OK — $m answered in ${dt}ms. Working model auto-selected."
                    )
                } catch (e: Exception) {
                    lastMsg = (e.message ?: e.javaClass.simpleName).take(220)
                    LogBus.w(LogCategory.NODE, "AI test failed (${provider.label}/$m): ${lastMsg.take(200)}")
                }
            }
            TestResult(false, provider, null, 0, "${provider.label}: every model failed. Last error: $lastMsg")
        }

    /**
     * A single OpenAI-style chat/completions call. [jsonMode] (response_format
     * json_object) is only requested from Groq — it was part of the original Groq
     * integration; the other providers get the same strict-JSON system prompt
     * without it, and [parseReply] tolerates stray text around the JSON.
     */
    private fun chatOnce(
        provider: AiProvider, apiKey: String, model: String,
        system: String, user: String, maxTokens: Int, jsonMode: Boolean
    ): String {
        val body = JSONObject()
            .put("model", model)
            .put("temperature", 0.2)
            .put("max_tokens", maxTokens)
        if (jsonMode) body.put("response_format", JSONObject().put("type", "json_object"))
        body.put(
            "messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", system))
                .put(JSONObject().put("role", "user").put("content", user))
        )
        val conn = (URL(provider.chatUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 30_000
            readTimeout = 120_000
            doOutput = true
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Content-Type", "application/json")
            if (provider == AiProvider.OPENROUTER) {
                // Optional app attribution headers OpenRouter asks for.
                setRequestProperty("HTTP-Referer", "https://imayx.in")
                setRequestProperty("X-Title", "IMAYX Host")
            }
        }
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.readText() ?: ""
            if (code !in 200..299) throw IllegalStateException("${provider.label} HTTP $code: ${text.take(300)}")
            val content = JSONObject(text)
                .getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").optString("content", "")
            if (content.isBlank()) throw IllegalStateException("${provider.label} returned an empty reply")
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
        if (count >= MAX_TREE_FILES) out.append("... (file tree truncated at $MAX_TREE_FILES entries)\n")
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
