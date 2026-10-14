package com.imayx.host.runtime

import android.content.Context
import com.imayx.host.data.LogBus
import com.imayx.host.data.LogCategory
import com.imayx.host.data.ProjectConfig
import com.imayx.host.data.ProjectStore
import com.imayx.host.security.SecretStore
import com.imayx.host.utils.PathUtil
import java.io.File

/**
 * Runs a one-shot `node <script> [args…]` inside a project's folder, with the project's
 * environment from [SecretStore] (same env the hosted Node app gets).
 *
 * Output streams to [LogBus] under [LogCategory.NODE]; the tail is handed to [onDone].
 * Arguments are never logged or stored — pass one-off secrets (like an admin password)
 * only as process args, never through any persisted field.
 */
class NodeScriptRunner(
    private val context: Context,
    private val store: ProjectStore,
    private val secrets: SecretStore,
    private val installer: NodeInstaller,
    private val registry: ProcessRegistry
) {
    @Volatile
    private var running: ManagedProcess? = null

    /** True while a script is running. Only one runs at a time. */
    fun isRunning(): Boolean = running != null

    /**
     * Starts the script. [onDone] is invoked once with the exit code and the last
     * ~20 output lines. Throws [IllegalStateException] when node is missing, another
     * script is running, or the script path is invalid/unsafe.
     */
    @Throws(IllegalStateException::class)
    fun run(
        project: ProjectConfig,
        scriptRelativePath: String,
        args: List<String>,
        onDone: (exitCode: Int, tail: String) -> Unit
    ) {
        if (running != null) throw IllegalStateException("Another script is already running")
        val nodeBin = installer.binary()
            ?: throw IllegalStateException("Node runtime not installed. Install it in Settings → Node Runtime.")
        val projectDir = store.projectDir(project.name)
        val script = PathUtil.resolveInside(projectDir, scriptRelativePath.trim())
            ?: throw IllegalStateException("Refusing unsafe script path: $scriptRelativePath")
        if (!script.isFile) throw IllegalStateException("Script not found in project: $scriptRelativePath")

        val home = File(context.filesDir, "node-home").also { it.mkdirs() }
        val env = LinkedHashMap<String, String>()
        env["HOME"] = home.absolutePath
        env["TMPDIR"] = context.cacheDir.absolutePath
        env["NODE_ENV"] = "production"
        env["HOST"] = "127.0.0.1"
        env["PORT"] = project.port.toString()
        env.putAll(secrets.getEnv(project.name))

        // Never log `args`: they may carry a one-off secret (e.g. an admin password).
        LogBus.i(LogCategory.NODE, "Running one-off script: node $scriptRelativePath")

        val cmdArgs = ArrayList<String>()
        cmdArgs.add(script.absolutePath)
        cmdArgs.addAll(args)
        val viaLinker = installer.probe()?.viaLinker == true
        val cmd = ExecUtil.command(nodeBin, cmdArgs, viaLinker)

        val tail = ArrayDeque<String>()
        val p = ManagedProcess(
            command = cmd,
            workDir = projectDir,
            env = env,
            registry = registry,
            registryMarker = "node-script",
            onLine = { line, isErr ->
                synchronized(tail) {
                    tail.addLast(line)
                    while (tail.size > 20) tail.removeFirst()
                }
                if (isErr) LogBus.w(LogCategory.NODE, line) else LogBus.i(LogCategory.NODE, line)
            },
            onExit = { code, _ ->
                running = null
                val out = synchronized(tail) { tail.joinToString("\n") }
                LogBus.i(LogCategory.NODE, "One-off script exited with code $code")
                try {
                    onDone(code, out)
                } catch (_: Exception) {
                }
            }
        )
        running = p
        try {
            p.start()
        } catch (e: Exception) {
            running = null
            throw IllegalStateException("Could not start node: ${e.message}")
        }
    }

    fun stop() {
        running?.stop()
        running = null
    }
}
