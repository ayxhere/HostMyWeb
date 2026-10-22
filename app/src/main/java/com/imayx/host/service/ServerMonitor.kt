package com.imayx.host.service

import android.content.Context
import com.imayx.host.ai.AiFixer
import com.imayx.host.ai.AiNotifier
import com.imayx.host.data.LogBus
import com.imayx.host.data.LogCategory
import com.imayx.host.data.ProcessState
import com.imayx.host.graph
import com.imayx.host.node.ServerRuntime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * Watches servers.log while hosting runs. When a server throws / fails to start / never opens its port:
 *   1. work out WHICH server (and file) it was,
 *   2. ask Groq to patch it (backup first),
 *   3. hard-restart the node process,
 *   4. check the port really came up, retry up to [MAX_ATTEMPTS] times,
 *   5. tell the user in a notification: where, what, what was fixed.
 * Idle cost: one tiny file read every 4 s.
 */
object ServerMonitor {
    private const val MAX_ATTEMPTS = 3
    private const val RETRY_GAP_MS = 45_000L
    private const val RESET_AFTER_MS = 30 * 60_000L

    private val errRx = Regex("""\[server-host\].*(failed to start|did NOT open|uncaught:|unhandled rejection|process\.exit\()""")

    suspend fun run(ctx: Context) {
        val g = ctx.graph
        var offset = ServerRuntime.logSize(ctx)
        val attempts = HashMap<String, Int>()
        val lastAttempt = HashMap<String, Long>()
        val lastInfo = HashMap<String, Long>()
        while (true) {
            delay(4000)
            try {
                val running = g.settings.hostingWanted && g.host.state.value.state == ProcessState.RUNNING
                if (!running) {
                    offset = ServerRuntime.logSize(ctx)
                    continue
                }
                val (text, newOff) = ServerRuntime.logSince(ctx, offset)
                offset = newOff
                if (text.isBlank()) continue
                val errLines = text.lineSequence().filter { errRx.containsMatchIn(it) }.toList()
                if (errLines.isEmpty()) continue

                val specs = g.host.currentSpecs()
                val byServer = LinkedHashMap<String, MutableList<String>>()
                for (line in errLines) {
                    val sp = attribute(line, specs) ?: continue
                    byServer.getOrPut(sp.name) { ArrayList() }.add(line)
                }
                for ((name, lines) in byServer) {
                    val sp = specs.firstOrNull { it.name == name } ?: continue
                    val now = System.currentTimeMillis()
                    if (now - (lastAttempt[name] ?: 0L) > RESET_AFTER_MS) attempts[name] = 0
                    val n = attempts[name] ?: 0
                    val issue = lines.last().substringAfter("[server-host] ").take(200)
                    LogBus.e(LogCategory.NODE, "Monitor: error in $name — $issue")

                    if (!g.settings.aiAutoFix || !AiFixer.isConfigured(g.secrets)) {
                        if (now - (lastInfo[name] ?: 0L) > 10 * 60_000L) {
                            lastInfo[name] = now
                            AiNotifier.post(
                                ctx, "Server error · $name",
                                "Issue: $issue\nAI auto-fix is off or has no AI key (Settings → AI)."
                            )
                        }
                        continue
                    }
                    if (n >= MAX_ATTEMPTS) {
                        if (now - (lastInfo[name] ?: 0L) > 10 * 60_000L) {
                            lastInfo[name] = now
                            AiNotifier.post(ctx, "AI gave up · $name", "Still failing after $MAX_ATTEMPTS fixes.\nIssue: $issue\nCheck Logs → Node, or restore a backup.")
                        }
                        continue
                    }
                    if (now - (lastAttempt[name] ?: 0L) < RETRY_GAP_MS) continue

                    attempts[name] = n + 1
                    lastAttempt[name] = now
                    val res = AiFixer.maybeAutoFix(
                        ctx, g.settings, g.secrets, g.backups, g.projectStore, sp.project,
                        "runtime error ($issue)", sp,
                        logOverride = lines.joinToString("\n") + "\n" + ServerRuntime.serverLogTail(ctx),
                        cooldownMs = RETRY_GAP_MS
                    )
                    if (res != null && res.ok && res.editsApplied > 0) {
                        LogBus.i(LogCategory.NODE, "Monitor: patched $name — restarting node servers")
                        g.host.restartServerRuntime()
                        delay(20_000)
                        val up = portAccepts(sp.port)
                        offset = ServerRuntime.logSize(ctx)
                        AiNotifier.post(
                            ctx,
                            if (up) "Back online · $name" else "Still down · $name",
                            if (up) "Restarted after AI fix. ${sp.name} is listening on :${sp.port} (attempt ${n + 1}/$MAX_ATTEMPTS)."
                            else "Restarted, but :${sp.port} is not open yet (attempt ${n + 1}/$MAX_ATTEMPTS). Will retry."
                        )
                        if (up) attempts[name] = 0
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                LogBus.w(LogCategory.SYSTEM, "Monitor error: ${e.message}")
            }
        }
    }

    /** Which server does this log line belong to? Name first, then stack-trace path, then "the one whose port is dead". */
    private fun attribute(line: String, specs: List<ServerRuntime.ServerSpec>): ServerRuntime.ServerSpec? {
        if (specs.isEmpty()) return null
        specs.firstOrNull { line.contains("] ${it.name} ") || line.contains(" ${it.name} ") }?.let { return it }
        for (sp in specs.sortedByDescending { it.dir.path.length }) {
            val p = try { sp.dir.canonicalPath } catch (_: Exception) { sp.dir.path }
            if (line.contains("$p/")) return sp
        }
        if (specs.size == 1) return specs[0]
        return specs.firstOrNull { !portAccepts(it.port) }
    }

    private fun portAccepts(port: Int): Boolean = try {
        java.net.Socket().use { s ->
            s.connect(java.net.InetSocketAddress("127.0.0.1", port), 1500)
            true
        }
    } catch (_: Exception) {
        false
    }
}
