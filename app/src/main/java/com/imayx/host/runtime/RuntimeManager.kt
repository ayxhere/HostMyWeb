package com.imayx.host.runtime

import android.content.Context
import com.imayx.host.data.LogBus
import com.imayx.host.data.LogCategory
import com.imayx.host.data.ProcessState
import com.imayx.host.data.ProjectConfig
import com.imayx.host.data.ProjectStore
import com.imayx.host.data.RuntimeType
import com.imayx.host.security.SecretStore
import com.imayx.host.server.StaticServer
import com.imayx.host.utils.PathUtil
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.net.BindException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

class RuntimeStartException(message: String) : Exception(message)

/** A project runtime: how a project is actually served/executed. */
interface ProjectRuntime {
    val type: RuntimeType
    val state: StateFlow<ProcessState>
    val error: StateFlow<String?>

    /** Null if usable, otherwise a user-facing reason (e.g. "Node runtime not installed"). */
    fun unavailableReason(): String?

    /** Blocking. Throws [RuntimeStartException] with a precise message. */
    fun start(project: ProjectConfig)

    fun stop()

    /** Pids of processes this runtime owns (for the resource monitor). */
    fun pids(): List<Int>

    /** Called when the runtime dies unexpectedly (not via [stop]). */
    var onCrash: (() -> Unit)?
}

internal fun isPortFree(port: Int): Boolean {
    return try {
        ServerSocket().use { s ->
            s.reuseAddress = true
            s.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port))
        }
        true
    } catch (_: Exception) {
        false
    }
}

internal fun canConnect(port: Int, timeoutMs: Int = 400): Boolean {
    return try {
        Socket().use { s ->
            s.connect(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), timeoutMs)
        }
        true
    } catch (_: Exception) {
        false
    }
}

class StaticRuntime(private val store: ProjectStore) : ProjectRuntime {
    override val type = RuntimeType.STATIC
    private val _state = MutableStateFlow(ProcessState.STOPPED)
    override val state: StateFlow<ProcessState> = _state
    private val _error = MutableStateFlow<String?>(null)
    override val error: StateFlow<String?> = _error
    override var onCrash: (() -> Unit)? = null

    @Volatile
    private var server: StaticServer? = null

    override fun unavailableReason(): String? = null

    override fun start(project: ProjectConfig) {
        stop()
        _error.value = null
        _state.value = ProcessState.STARTING
        val root = store.serveRoot(project)
        if (!root.isDirectory) {
            fail("Project folder not found: ${project.name}")
        }
        if (!isPortFree(project.port)) {
            fail("Port ${project.port} is already in use. Pick another port in the project settings.")
        }
        val s = StaticServer(root, project.port, project.spa) { line ->
            LogBus.i(LogCategory.SERVER, line)
        }
        try {
            s.start()
        } catch (e: BindException) {
            fail("Port ${project.port} is already in use (${e.message})")
        } catch (e: Exception) {
            fail("Could not start local server: ${e.message}")
        }
        server = s
        _state.value = ProcessState.RUNNING
        LogBus.i(LogCategory.SERVER, "Static server listening on 127.0.0.1:${project.port} (root=${root.name}, spa=${project.spa})")
        // Watchdog: if the accept loop dies unexpectedly, report it.
        val watcher = Thread({
            var active = true
            while (active) {
                try {
                    Thread.sleep(2000)
                } catch (_: InterruptedException) {
                    active = false
                }
                if (active && server !== s) {
                    active = false
                }
                if (active && !s.isRunning()) {
                    active = false
                    server = null
                    _error.value = "Local server stopped unexpectedly"
                    _state.value = ProcessState.FAILED
                    LogBus.e(LogCategory.SERVER, "Local server stopped unexpectedly")
                    onCrash?.invoke()
                }
            }
        }, "ix-static-watch")
        watcher.isDaemon = true
        watcher.start()
    }

    private fun fail(message: String): Nothing {
        _error.value = message
        _state.value = ProcessState.FAILED
        LogBus.e(LogCategory.SERVER, message)
        throw RuntimeStartException(message)
    }

    override fun stop() {
        val s = server
        server = null
        if (s != null) {
            s.stop()
            LogBus.i(LogCategory.SERVER, "Static server stopped")
        }
        if (_state.value != ProcessState.FAILED) _state.value = ProcessState.STOPPED
    }

    override fun pids(): List<Int> = emptyList()

    fun requestCount(): Long = server?.requestCount?.get() ?: 0L
}

class NodeRuntime(
    private val context: Context,
    private val store: ProjectStore,
    private val secrets: SecretStore,
    private val installer: NodeInstaller,
    private val registry: ProcessRegistry
) : ProjectRuntime {
    override val type = RuntimeType.NODE
    private val _state = MutableStateFlow(ProcessState.STOPPED)
    override val state: StateFlow<ProcessState> = _state
    private val _error = MutableStateFlow<String?>(null)
    override val error: StateFlow<String?> = _error
    override var onCrash: (() -> Unit)? = null

    @Volatile
    private var proc: ManagedProcess? = null
    private val lastLines = ArrayDeque<String>()

    override fun unavailableReason(): String? {
        val bin = installer.binary()
            ?: return "Node runtime not installed"
        // The bundled libnode.so is a shared library, not an executable — Android
        // cannot run it as a child process. Only a real executable node binary
        // works for NODE projects; for the built-in runtime use a Server project.
        if (bin.name.endsWith(".so")) {
            return "The built-in node library can't run NODE projects as a child process — " +
                "change this project to the Server runtime (built-in Node.js, one tap) " +
                "or install an executable ARM64 node binary in Settings → Node Runtime."
        }
        val probe = installer.probe()
        return if (probe?.ok == true) null
        else "Node runtime is installed but cannot run here: ${probe?.error ?: "unknown error"}"
    }

    override fun start(project: ProjectConfig) {
        stop()
        _error.value = null
        _state.value = ProcessState.STARTING
        val nodeBin = installer.binary()
            ?: fail("Node runtime not installed. Install an Android-compatible ARM64 Node.js build in Settings → Node Runtime.")
        val projectDir = store.projectDir(project.name)
        val entry = PathUtil.resolveInside(projectDir, project.entryFile)
        if (entry == null || !entry.isFile) {
            fail("Entry file \"${project.entryFile}\" not found in project ${project.name}")
        }
        if (!isPortFree(project.port)) {
            fail("Port ${project.port} is already in use. Pick another port in the project settings.")
        }
        val home = File(context.filesDir, "node-home").also { it.mkdirs() }
        val env = LinkedHashMap<String, String>()
        env["HOME"] = home.absolutePath
        env["TMPDIR"] = context.cacheDir.absolutePath
        env["NODE_ENV"] = "production"
        env["HOST"] = "127.0.0.1"
        env["PORT"] = project.port.toString()
        env.putAll(secrets.getEnv(project.name))

        val args = ArrayList<String>()
        args.add(entry!!.absolutePath)
        project.nodeArgs.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.forEach { args.add(it) }

        synchronized(lastLines) { lastLines.clear() }
        val probeInfo = installer.probe()
        val viaLinker = probeInfo?.viaLinker == true
        val cmd = ExecUtil.command(nodeBin, args, viaLinker)
        LogBus.i(LogCategory.NODE, "Starting node ${project.entryFile} on port ${project.port}")

        val p = ManagedProcess(
            command = cmd,
            workDir = projectDir,
            env = env,
            registry = registry,
            registryMarker = nodeBin.name,
            onLine = { line, isErr ->
                synchronized(lastLines) {
                    lastLines.addLast(line)
                    while (lastLines.size > 12) lastLines.removeFirst()
                }
                if (isErr) LogBus.w(LogCategory.NODE, line) else LogBus.i(LogCategory.NODE, line)
            },
            onExit = { code, requested ->
                if (proc != null) {
                    proc = null
                }
                if (requested) {
                    if (_state.value != ProcessState.FAILED) _state.value = ProcessState.STOPPED
                    LogBus.i(LogCategory.NODE, "Node process stopped")
                } else {
                    val tail = synchronized(lastLines) { lastLines.takeLast(3).joinToString(" | ") }
                    val msg = "Node process exited with code $code" + if (tail.isNotBlank()) ": $tail" else ""
                    _error.value = msg
                    _state.value = ProcessState.FAILED
                    LogBus.e(LogCategory.NODE, msg)
                    onCrash?.invoke()
                }
            }
        )
        try {
            p.start()
        } catch (e: Exception) {
            val info = ElfInfo.read(nodeBin)
            fail(ExecUtil.explain(e.message, info))
        }
        proc = p

        // Wait until the app accepts connections, the process dies, or we time out.
        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline) {
            if (!p.isAlive()) {
                val msg = _error.value ?: "Node process exited during startup"
                fail(msg)
            }
            if (canConnect(project.port)) {
                _state.value = ProcessState.RUNNING
                LogBus.i(LogCategory.NODE, "Node app is accepting connections on 127.0.0.1:${project.port}")
                return
            }
            try {
                Thread.sleep(250)
            } catch (_: InterruptedException) {
                break
            }
        }
        p.stop()
        fail("Node app did not open port ${project.port} within 20s (make it listen on process.env.PORT)")
    }

    private fun fail(message: String): Nothing {
        _error.value = message
        _state.value = ProcessState.FAILED
        LogBus.e(LogCategory.NODE, message)
        throw RuntimeStartException(message)
    }

    override fun stop() {
        val p = proc
        proc = null
        p?.stop()
        if (_state.value != ProcessState.FAILED) _state.value = ProcessState.STOPPED
    }

    override fun pids(): List<Int> {
        val pid = proc?.pid ?: return emptyList()
        return listOf(pid) + ProcessUtil.descendants(pid)
    }
}

/** Chooses the runtime implementation for a project and exposes the active one. */
class RuntimeManager(
    private val context: Context,
    private val store: ProjectStore,
    private val secrets: SecretStore,
    val nodeInstaller: NodeInstaller,
    private val registry: ProcessRegistry
) {
    val static = StaticRuntime(store)
    val node = NodeRuntime(context, store, secrets, nodeInstaller, registry)

    /** One-shot `node <script>` runner for NODE projects (admin creation, migrations, …). */
    val scriptRunner = NodeScriptRunner(context, store, secrets, nodeInstaller, registry)

    fun forType(type: RuntimeType): ProjectRuntime = when (type) {
        RuntimeType.STATIC -> static
        RuntimeType.NODE -> node
        // SERVER projects run on the embedded node (:node_server process) via
        // ServerRuntime — they never go through a ProjectRuntime instance.
        RuntimeType.SERVER -> throw IllegalArgumentException("SERVER projects are managed by ServerRuntime, not a ProjectRuntime")
    }

    /** A brand-new runtime instance, so several projects can run side by side (multi-start). */
    fun create(type: RuntimeType): ProjectRuntime = when (type) {
        RuntimeType.STATIC -> StaticRuntime(store)
        RuntimeType.NODE -> NodeRuntime(context, store, secrets, nodeInstaller, registry)
        RuntimeType.SERVER -> throw IllegalArgumentException("SERVER projects are managed by ServerRuntime, not a ProjectRuntime")
    }
}
