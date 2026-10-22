package com.imayx.host.domain

import android.app.ActivityManager
import android.content.Context
import com.imayx.host.cloudflare.TunnelController
import com.imayx.host.data.AppSettings
import com.imayx.host.data.HostState
import com.imayx.host.data.LogBus
import com.imayx.host.data.LogCategory
import com.imayx.host.data.ProcessState
import com.imayx.host.data.ProjectConfig
import com.imayx.host.data.ProjectStore
import com.imayx.host.data.RuntimeType
import com.imayx.host.data.TunnelMode
import com.imayx.host.node.ServerRuntime
import com.imayx.host.runtime.ProjectRuntime
import com.imayx.host.runtime.RuntimeManager
import com.imayx.host.runtime.RuntimeStartException
import com.imayx.host.security.SecretStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
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
    private val appContext: Context,
    private val settings: AppSettings,
    private val store: ProjectStore,
    private val runtimes: RuntimeManager,
    private val tunnel: TunnelController,
    private val secrets: SecretStore,
    private val backups: com.imayx.host.backup.BackupManager,
    private val cloudflareApi: com.imayx.host.cloudflare.CloudflareApi
) {
    private val _state = MutableStateFlow(HostState())
    val state: StateFlow<HostState> = _state

    /** One-line notices for the UI (e.g. a project of a multi-start could not start). */
    private val _notices = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val notices: SharedFlow<String> = _notices

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

    /**
     * SERVER projects currently running on the embedded node (`:node_server`
     * process). Guarded by itself. They are (re)started together through
     * [ServerRuntime] — one tap runs them all.
     */
    private val serverProjects = LinkedHashMap<String, ProjectConfig>()

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
            serverProcessPids() +
            tunnel.pids()

    /** Pids of the `:node_server` process (own processes are visible to us). */
    private fun serverProcessPids(): List<Int> {
        return try {
            val am = appContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            am.runningAppProcesses
                ?.filter { it.processName == appContext.packageName + ":node_server" }
                ?.map { it.pid }
                ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** The port a project effectively listens on: always its OWN configured port
     *  (for a SERVER project, its main server's port — the two are kept in sync). */
    fun effectivePort(cfg: ProjectConfig, isPrimary: Boolean): Int =
        cfg.servers.firstOrNull { it.primary }?.port ?: cfg.port

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
                val removedServer = synchronized(serverProjects) { serverProjects.remove(projectName) }
                if (removedServer != null) {
                    refreshServerRuntime()
                    LogBus.i(LogCategory.SYSTEM, "Stopped server $projectName")
                    publishExtras()
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
        val names = synchronized(extras) { extras.keys.toList() } +
            synchronized(serverProjects) { serverProjects.keys.toList() }
        _state.value = _state.value.copy(extras = names)
    }

    /**
     * (Re)builds the `:node_server` manifest from [serverProjects] and starts it,
     * or stops the server process when nothing is left. One call restarts them all
     * together — this is the "one tap runs every server" path.
     * Returns a user-facing error when the servers could not be (re)started.
     */
    private fun refreshServerRuntime(restart: Boolean = false): String? {
        val specs = currentSpecs()
        if (specs.isEmpty()) {
            ServerRuntime.stopServers(appContext)
            return null
        }
        val err = if (restart) ServerRuntime.restartServers(appContext, specs) else ServerRuntime.startServers(appContext, specs)
        if (err != null) {
            LogBus.e(LogCategory.NODE, err)
            _notices.tryEmit(err)
            // Prerequisite failure (missing entry / node_modules): let the AI fixer try once.
            scope.launch {
                for (sp in specs) {
                    val res = com.imayx.host.ai.AiFixer.maybeAutoFix(
                        appContext, settings, secrets, backups, store, sp.project,
                        "server failed to start: $err", sp
                    )
                    if (res != null && res.ok && res.editsApplied > 0) {
                        LogBus.i(LogCategory.NODE, "AI auto-fix patched ${sp.name} — restarting server runtime")
                        refreshServerRuntime(restart = true)
                        break
                    }
                }
            }
        } else {
            syncRoutes(specs)
        }
        return err
    }

    /** Specs for every SERVER project currently hosted (multi-server projects expand to one spec per server). */
    fun currentSpecs(): List<ServerRuntime.ServerSpec> {
        val cfgs = synchronized(serverProjects) { serverProjects.values.toList() }
        val all = cfgs.flatMap { specsFor(it) }
        // Two servers must never share a port (EADDRINUSE would kill one). Move later duplicates.
        val used = HashSet<Int>()
        val out = ArrayList<ServerRuntime.ServerSpec>()
        for (sp in all) {
            var port = sp.port
            if (!used.add(port)) {
                val taken = used + all.map { it.port } + setOf(3101, 3102, settings.primaryPort)
                var p = maxOf(settings.defaultPort, 3200)
                while (p in taken) p++
                LogBus.w(LogCategory.NODE, "Port $port already used — ${sp.name} moved to $p")
                used.add(p)
                port = p
            }
            out.add(if (port == sp.port) sp else sp.copy(port = port))
        }
        return out
    }

    private fun specsFor(cfg: ProjectConfig): List<ServerRuntime.ServerSpec> {
        val root = store.projectDir(cfg.name)
        val secretEnv = secrets.getEnv(cfg.name)
        if (cfg.servers.isEmpty()) {
            val env = ServerRuntime.buildEnv(appContext, cfg.name, root, secretEnv)
            return listOf(ServerRuntime.ServerSpec(cfg.name, root, cfg.entryFile, cfg.port, env))
        }
        val multi = cfg.servers.count { it.enabled } > 1
        return cfg.servers.filter { it.enabled }.map { sv ->
            val dir = if (sv.dir.isEmpty()) root else java.io.File(root, sv.dir)
            val env = ServerRuntime.buildEnv(appContext, cfg.name, dir, secretEnv) + mapOf("SERVER_NAME" to sv.name)
            // Every server binds its OWN saved port — including the main server of
            // the hosted project. The tunnel targets the main server's port
            // (= the project port, kept in sync), never a global override.
            val port = sv.port
            ServerRuntime.ServerSpec(
                name = if (multi) "${cfg.name}/${sv.name}" else cfg.name,
                dir = dir, entry = sv.entry, port = port, env = env,
                project = cfg.name, serverName = sv.name, projectRoot = root,
                subdomain = if (sv.primary) "" else sv.subdomain
            )
        }
    }

    /** Hard restart of the whole node server process (used after an AI fix). */
    fun restartServerRuntime(): String? = refreshServerRuntime(restart = true)

    /**
     * Cloudflare auto-routing: every server with a subdomain (api/ -> api.<domain>) gets an ingress
     * route to its own 127.0.0.1:<port> + a proxied CNAME. Only for NAMED tunnels with an API token.
     * Runs in the background; never blocks or fails hosting.
     */
    private fun syncRoutes(specs: List<ServerRuntime.ServerSpec>) {
        if (settings.quickTunnel || !settings.autoRoutes) return
        val wanted = ArrayList<Pair<String, Int>>()
        for (sp in specs) {
            val sub = sp.subdomain
            if (sub.isNullOrBlank()) continue
            val base = (store.get(sp.project)?.domain?.ifBlank { null } ?: settings.defaultDomain).trim().lowercase()
            if (base.isBlank()) continue
            wanted.add("$sub.$base" to sp.port)
        }
        if (wanted.isEmpty()) return
        scope.launch {
            val report = try {
                com.imayx.host.cloudflare.RouteSync.sync(cloudflareApi, secrets, wanted)
            } catch (e: Exception) {
                "Route sync failed: ${e.message}"
            }
            LogBus.i(LogCategory.CLOUDFLARE, report)
            _notices.tryEmit(report.lineSequence().first())
            com.imayx.host.ai.AiNotifier.post(appContext, "Routes updated", report, id = 7001)
        }
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

    /**
     * Start-time self-heal: a project that HAS server definitions but is still
     * configured for the NODE runtime cannot start when there is no usable
     * executable node binary (the bundled libnode.so is a library, not an
     * executable) — yet it can run perfectly well on the built-in Server
     * runtime. Switch it (persisted) instead of failing the start. Projects
     * without servers, and devices with a working executable node, are
     * untouched: NODE stays respected there.
     */
    private fun healRuntime(cfg: ProjectConfig): ProjectConfig {
        if (cfg.runtime != RuntimeType.NODE) return cfg
        if (cfg.servers.isEmpty()) return cfg
        if (hasUsableNodeBinary()) return cfg
        val healed = cfg.copy(runtime = RuntimeType.SERVER)
        store.save(healed)
        LogBus.i(LogCategory.SYSTEM, "Project '${cfg.name}' has servers — switched NODE → Server runtime (built-in Node)")
        return healed
    }

    /** Mirrors NodeRuntime.unavailableReason(): no binary, the bundled .so, or a failed probe = unusable. */
    private fun hasUsableNodeBinary(): Boolean {
        val installer = runtimes.nodeInstaller
        val bin = installer.binary() ?: return false
        if (bin.name.endsWith(".so")) return false
        return installer.probe()?.ok == true
    }

    private fun startExtra(rawCfg: ProjectConfig) {
        // Self-heal (see healRuntime) before any runtime branching.
        val cfg = healRuntime(rawCfg)
        // PENTAGI projects are remote-only (Docker on a Linux host) — never a local runtime.
        if (cfg.runtime == RuntimeType.PENTAGI) {
            val msg = "${cfg.name}: PentAGI runs on a remote Linux/Docker host — open the PentAGI tab to manage it."
            LogBus.e(LogCategory.PENTAGI, msg)
            _notices.tryEmit(msg)
            return
        }
        // SERVER projects share the single embedded node process — they are
        // collected and (re)started together, not given their own runtime.
        if (cfg.runtime == RuntimeType.SERVER) {
            if (!ServerRuntime.isAvailable()) {
                val msg = "${cfg.name}: embedded Node.js is not bundled in this build — server projects cannot start."
                LogBus.e(LogCategory.SYSTEM, msg)
                _notices.tryEmit(msg)
                return
            }
            synchronized(serverProjects) { serverProjects[cfg.name] = cfg }
            refreshServerRuntime()
            LogBus.i(LogCategory.SYSTEM, "Started server ${cfg.name} on port ${cfg.port} (shared node process)")
            return
        }
        val rt = runtimes.create(cfg.runtime)
        val unavailable = rt.unavailableReason()
        if (unavailable != null) {
            LogBus.e(LogCategory.SYSTEM, "Could not start ${cfg.name}: $unavailable")
            _notices.tryEmit("${cfg.name}: $unavailable")
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
            val why = e.message ?: e.javaClass.simpleName
            LogBus.e(LogCategory.SYSTEM, "Could not start ${cfg.name}: $why")
            _notices.tryEmit("${cfg.name} did not start: $why")
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
        stopServerProjects()
        tunnel.stop()
        activeRuntime?.stop()
        activeRuntime = null
    }

    private fun stopServerProjects() {
        val had = synchronized(serverProjects) {
            val h = serverProjects.isNotEmpty()
            serverProjects.clear()
            h
        }
        if (had) ServerRuntime.stopServers(appContext)
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
        // Self-heal before any runtime branching (see healRuntime).
        val healedCfg = healRuntime(cfg)
        // The project binds its OWN configured port (for a SERVER project: its main
        // server's port — ProjectManager keeps the two in sync) and the Cloudflare
        // tunnel targets exactly that port. There is NO global primary-port
        // override: the port a user sets in Project settings is the port that runs.
        val effective = healedCfg
        activeConfig = effective
        _state.value = HostState(ProcessState.STARTING, cfg.name, effective.runtime, effective.port)
        LogBus.i(LogCategory.SYSTEM, "Start Host: project=${cfg.name} runtime=${effective.runtime} port=${effective.port}")

        // PENTAGI primary: remote-only — fail with guidance instead of trying a local runtime.
        if (effective.runtime == RuntimeType.PENTAGI) {
            failStart(effective, "PentAGI cannot run on this device (it needs a Docker daemon on Linux). Open the PentAGI tab to manage the remote deployment.")
            return
        }

        // SERVER primary: runs on the embedded node (:node_server process), no ProjectRuntime.
        if (effective.runtime == RuntimeType.SERVER) {
            if (!ServerRuntime.isAvailable()) {
                failStart(effective, "Embedded Node.js is not bundled in this build (libnode.so missing) — server projects cannot start.")
                return
            }
            synchronized(serverProjects) { serverProjects[cfg.name] = effective }
            val serverErr = refreshServerRuntime()
            if (serverErr != null) {
                synchronized(serverProjects) { serverProjects.remove(cfg.name) }
                failStart(effective, serverErr)
                return
            }
            activeRuntime = null
            _state.value = HostState(ProcessState.RUNNING, cfg.name, effective.runtime, effective.port, startedAt = System.currentTimeMillis())
            LogBus.i(LogCategory.SYSTEM, "Server ${cfg.name} running on 127.0.0.1:${effective.port} (shared node process)")
            if (cfg.cloudflareEnabled) {
                startTunnel(effective)
            } else {
                LogBus.i(LogCategory.CLOUDFLARE, "Cloudflare disabled for this project; serving locally only")
            }
            return
        }

        val rt = runtimes.forType(effective.runtime)
        val unavailable = rt.unavailableReason()
        if (unavailable != null) {
            failStart(effective, unavailable)
            return
        }
        try {
            rt.start(effective)
        } catch (e: RuntimeStartException) {
            failStart(effective, e.message ?: "Runtime failed to start")
            return
        } catch (e: Exception) {
            failStart(effective, e.message ?: e.javaClass.simpleName)
            return
        }
        activeRuntime = rt
        _state.value = HostState(ProcessState.RUNNING, cfg.name, effective.runtime, effective.port, startedAt = System.currentTimeMillis())

        if (cfg.cloudflareEnabled) {
            startTunnel(effective)
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
        _notices.tryEmit("${cfg.name} did not start: $message")
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
