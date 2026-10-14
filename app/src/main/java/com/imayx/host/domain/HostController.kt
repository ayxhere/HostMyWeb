package com.imayx.host.domain

import com.imayx.host.cloudflare.TunnelController
import com.imayx.host.data.AppSettings
import com.imayx.host.data.HostState
import com.imayx.host.data.LogBus
import com.imayx.host.data.LogCategory
import com.imayx.host.data.ProcessState
import com.imayx.host.data.ProjectConfig
import com.imayx.host.data.ProjectStore
import com.imayx.host.data.TunnelMode
import com.imayx.host.runtime.ProjectRuntime
import com.imayx.host.runtime.RuntimeManager
import com.imayx.host.runtime.RuntimeStartException
import com.imayx.host.security.SecretStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Orchestrates one hosted project at a time:
 *  1. save/load project configuration  2. start local runtime  3. validate token (named tunnel)
 *  4. start cloudflared  5. monitor  6. optional bounded auto-restart (never an infinite loop).
 */
class HostController(
    private val settings: AppSettings,
    private val store: ProjectStore,
    private val runtimes: RuntimeManager,
    private val tunnel: TunnelController,
    private val secrets: SecretStore
) {
    private val _state = MutableStateFlow(HostState())
    val state: StateFlow<HostState> = _state

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    @Volatile
    private var activeRuntime: ProjectRuntime? = null

    @Volatile
    private var activeConfig: ProjectConfig? = null

    @Volatile
    private var userStopped = true

    /** Projects running alongside the primary one (multi-start). Guarded by [extras] itself. */
    private val extras = LinkedHashMap<String, Pair<ProjectConfig, ProjectRuntime>>()

    private val runtimeRestarts = ArrayList<Long>()
    private val tunnelRestarts = ArrayList<Long>()

    private companion object {
        const val MAX_RESTARTS = 3
        const val WINDOW_MS = 10 * 60 * 1000L
    }

    init {
        runtimes.static.onCrash = { onRuntimeCrash() }
        runtimes.node.onCrash = { onRuntimeCrash() }
        tunnel.onCrash = { onTunnelCrash() }
    }

    fun currentProject(): String? = activeConfig?.name

    fun pids(): List<Int> =
        (activeRuntime?.pids() ?: emptyList()) +
            synchronized(extras) { extras.values.map { it.second } }.flatMap { it.pids() } +
            tunnel.pids()

    /**
     * Starts several projects at once. The first one that comes up becomes the primary project
     * (it owns the Cloudflare tunnel; a named tunnel routes by hostname so it serves all of them),
     * the others run next to it. Whatever was hosted before is replaced.
     */
    fun startMany(projectNames: List<String>) {
        scope.launch { mutex.withLock { doStartMany(projectNames) } }
    }

    /** Stops one project. Stopping the primary project stops everything. */
    fun stopOne(projectName: String) {
        scope.launch {
            mutex.withLock {
                if (activeConfig?.name == projectName) {
                    doStop()
                    return@withLock
                }
                val entry = synchronized(extras) { extras.remove(projectName) } ?: return@withLock
                entry.second.onCrash = null
                try {
                    entry.second.stop()
                } catch (_: Exception) {
                }
                LogBus.i(LogCategory.SYSTEM, "Stopped $projectName")
                publishExtras()
            }
        }
    }

    private fun publishExtras() {
        val names = synchronized(extras) { extras.keys.toList() }
        _state.value = _state.value.copy(extras = names)
    }

    private fun doStartMany(projectNames: List<String>) {
        val cfgs = projectNames.distinct().mapNotNull { store.get(it) }
        if (cfgs.size <= 1) {
            doStart(cfgs.firstOrNull()?.name ?: projectNames.firstOrNull())
            return
        }
        val ordered = cfgs.sortedByDescending { it.cloudflareEnabled }
        var primaryUp = false
        for (cfg in ordered) {
            if (!primaryUp) {
                doStart(cfg.name)
                primaryUp = _state.value.state == ProcessState.RUNNING
            } else {
                startExtra(cfg)
            }
        }
        publishExtras()
    }

    private fun startExtra(cfg: ProjectConfig) {
        val rt = runtimes.create(cfg.runtime)
        val unavailable = rt.unavailableReason()
        if (unavailable != null) {
            LogBus.e(LogCategory.SYSTEM, "Could not start ${cfg.name}: $unavailable")
            return
        }
        rt.onCrash = {
            synchronized(extras) { extras.remove(cfg.name) }
            publishExtras()
            LogBus.e(LogCategory.SYSTEM, "${cfg.name} stopped unexpectedly")
        }
        try {
            rt.start(cfg)
            synchronized(extras) { extras[cfg.name] = cfg to rt }
            LogBus.i(LogCategory.SYSTEM, "Started ${cfg.name} (${cfg.runtime}) on port ${cfg.port}")
        } catch (e: Exception) {
            LogBus.e(LogCategory.SYSTEM, "Could not start ${cfg.name}: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun stopExtras() {
        val list = synchronized(extras) {
            val l = extras.values.map { it.second }
            extras.clear()
            l
        }
        for (rt in list) {
            rt.onCrash = null
            try {
                rt.stop()
            } catch (_: Exception) {
            }
        }
    }

    /** Starts (or restarts, if already running) hosting for [projectName] (default: the active project). */
    fun start(projectName: String? = null) {
        scope.launch { mutex.withLock { doStart(projectName) } }
    }

    fun stop() {
        scope.launch { mutex.withLock { doStop() } }
    }

    fun restart() {
        start(activeConfig?.name ?: settings.activeProject.ifBlank { null })
    }

    /** Chooses the project to start after a reboot: the first with autoStart, else the active one. */
    fun bootProject(): String? =
        store.list().firstOrNull { it.autoStart }?.name ?: settings.activeProject.ifBlank { null }

    private fun teardown() {
        stopExtras()
        tunnel.stop()
        activeRuntime?.stop()
        activeRuntime = null
    }

    private fun doStart(projectName: String?) {
        val name = projectName ?: settings.activeProject.ifBlank { null }
        val cfg = name?.let { store.get(it) }
        if (cfg == null) {
            val msg = "No project selected. Create or import a project first."
            _state.value = HostState(state = ProcessState.FAILED, error = msg)
            LogBus.e(LogCategory.SYSTEM, msg)
            settings.hostingWanted = false
            return
        }
        // Restart semantics: tear down whatever is running without flashing STOPPED.
        teardown()
        userStopped = false
        runtimeRestarts.clear()
        tunnelRestarts.clear()
        settings.hostingWanted = true
        settings.activeProject = cfg.name
        activeConfig = cfg
        _state.value = HostState(ProcessState.STARTING, cfg.name, cfg.runtime, cfg.port)
        LogBus.i(LogCategory.SYSTEM, "Start Host: project=${cfg.name} runtime=${cfg.runtime} port=${cfg.port}")

        val rt = runtimes.forType(cfg.runtime)
        val unavailable = rt.unavailableReason()
        if (unavailable != null) {
            failStart(cfg, unavailable)
            return
        }
        try {
            rt.start(cfg)
        } catch (e: RuntimeStartException) {
            failStart(cfg, e.message ?: "Runtime failed to start")
            return
        } catch (e: Exception) {
            failStart(cfg, e.message ?: e.javaClass.simpleName)
            return
        }
        activeRuntime = rt
        _state.value = HostState(ProcessState.RUNNING, cfg.name, cfg.runtime, cfg.port, startedAt = System.currentTimeMillis())

        if (cfg.cloudflareEnabled) {
            startTunnel(cfg)
        } else {
            LogBus.i(LogCategory.CLOUDFLARE, "Cloudflare disabled for this project; serving locally only")
        }
    }

    private fun startTunnel(cfg: ProjectConfig) {
        val mode = if (settings.quickTunnel) TunnelMode.QUICK else TunnelMode.NAMED
        val token = if (mode == TunnelMode.NAMED) secrets.get(SecretStore.KEY_TUNNEL_TOKEN) else null
        // Errors are recorded in TunnelController.state (and logged); local hosting keeps running.
        tunnel.start(mode, cfg.port, token)
    }

    private fun failStart(cfg: ProjectConfig, message: String) {
        teardown()
        _state.value = HostState(ProcessState.FAILED, cfg.name, cfg.runtime, cfg.port, error = message)
        settings.hostingWanted = false
        LogBus.e(LogCategory.SYSTEM, "Start failed: $message")
    }

    private fun doStop() {
        userStopped = true
        settings.hostingWanted = false
        val cfg = activeConfig
        teardown()
        _state.value = HostState(
            state = ProcessState.STOPPED,
            project = cfg?.name ?: settings.activeProject.ifBlank { null },
            runtime = cfg?.runtime ?: _state.value.runtime,
            port = cfg?.port ?: _state.value.port
        )
        LogBus.i(LogCategory.SYSTEM, "Host stopped")
    }

    private fun allow(history: ArrayList<Long>): Boolean {
        val now = System.currentTimeMillis()
        history.removeAll { now - it > WINDOW_MS }
        if (history.size >= MAX_RESTARTS) return false
        history.add(now)
        return true
    }

    private fun onRuntimeCrash() {
        if (userStopped) return
        val cfg = activeConfig ?: return
        val rt = activeRuntime ?: return
        val why = rt.error.value ?: "Runtime stopped unexpectedly"
        scope.launch {
            mutex.withLock {
                if (userStopped) return@withLock
                if (cfg.autoRestart && allow(runtimeRestarts)) {
                    val attempt = runtimeRestarts.size
                    _state.value = _state.value.copy(
                        state = ProcessState.STARTING,
                        error = "Crashed: $why. Restarting (attempt $attempt of $MAX_RESTARTS)…"
                    )
                    LogBus.w(LogCategory.SYSTEM, "Auto-restart $attempt/$MAX_RESTARTS for ${cfg.name}")
                    delay(2000L * attempt)
                    if (userStopped) return@withLock
                    try {
                        rt.start(cfg)
                        _state.value = _state.value.copy(
                            state = ProcessState.RUNNING,
                            error = null,
                            startedAt = System.currentTimeMillis(),
                            restarts = _state.value.restarts + 1
                        )
                    } catch (e: Exception) {
                        _state.value = _state.value.copy(
                            state = ProcessState.FAILED,
                            error = e.message ?: "Restart failed"
                        )
                        settings.hostingWanted = false
                        tunnel.stop()
                    }
                } else {
                    val giveUp = if (cfg.autoRestart) " Auto-restart stopped after $MAX_RESTARTS attempts in 10 minutes." else ""
                    _state.value = _state.value.copy(state = ProcessState.FAILED, error = why + giveUp)
                    settings.hostingWanted = false
                    tunnel.stop()
                }
            }
        }
    }

    private fun onTunnelCrash() {
        if (userStopped) return
        val cfg = activeConfig ?: return
        if (!cfg.autoRestart) return
        scope.launch {
            mutex.withLock {
                if (userStopped) return@withLock
                if (allow(tunnelRestarts)) {
                    val attempt = tunnelRestarts.size
                    LogBus.w(LogCategory.CLOUDFLARE, "Auto-restarting tunnel ($attempt/$MAX_RESTARTS)")
                    delay(3000L * attempt)
                    if (userStopped) return@withLock
                    startTunnel(cfg)
                } else {
                    LogBus.e(LogCategory.CLOUDFLARE, "Tunnel auto-restart stopped after $MAX_RESTARTS attempts in 10 minutes")
                }
            }
        }
    }
}
