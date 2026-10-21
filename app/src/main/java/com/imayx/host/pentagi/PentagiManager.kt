package com.imayx.host.pentagi

import android.app.Application
import com.imayx.host.data.AppSettings
import com.imayx.host.data.LogBus
import com.imayx.host.data.LogCategory
import com.imayx.host.data.ProjectConfig
import com.imayx.host.data.ProjectStore
import com.imayx.host.data.RuntimeType
import com.imayx.host.security.SecretStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Owns PentAGI state for the UI. PentAGI itself runs ONLY on a remote
 * Linux/Docker host (see PentagiModels docs) — this class never tries to
 * run Docker, Compose, Postgres or PentAGI locally, and never claims a
 * remote action succeeded unless the manager actually answered.
 *
 * Secrets: the manager token / LLM key / PentAGI API token live in
 * [SecretStore] (Keystore AES-GCM) and are auto-registered with the log
 * Redactor by SecretStore.put/get, so LogBus output can never contain them.
 */
class PentagiManager(
    private val app: Application,
    private val settings: AppSettings,
    private val secrets: SecretStore,
    private val projectStore: ProjectStore
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _health = MutableStateFlow(
        PentagiHealth.notConfigured("PentAGI remote is not configured yet. Add the manager URL and token below.")
    )
    val health: StateFlow<PentagiHealth> = _health

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError

    private val _remoteLogs = MutableStateFlow<List<String>>(emptyList())
    val remoteLogs: StateFlow<List<String>> = _remoteLogs

    private val _configKeysSet = MutableStateFlow<List<String>>(emptyList())
    val configKeysSet: StateFlow<List<String>> = _configKeysSet

    val client = PentagiClient(
        managerBaseUrl = { settings.pentagiManagerUrl },
        managerToken = { secrets.get(SecretStore.KEY_PENTAGI_MANAGER_TOKEN) },
        pentagiBaseUrl = { settings.pentagiRemoteUrl }
    )

    fun isManagerConfigured(): Boolean =
        settings.pentagiManagerUrl.isNotBlank() &&
            PentagiValidator.validateManagerUrl(settings.pentagiManagerUrl) == null &&
            secrets.has(SecretStore.KEY_PENTAGI_MANAGER_TOKEN)

    fun deployMode(): PentagiDeployMode = when {
        isManagerConfigured() -> PentagiDeployMode.REMOTE
        settings.pentagiManagerUrl.isBlank() && settings.pentagiRemoteUrl.isBlank() ->
            PentagiDeployMode.NOT_CONFIGURED
        else -> PentagiDeployMode.REMOTE
    }

    /**
     * Creates the "pentagi" project entry (dir + ProjectConfig runtime=PENTAGI,
     * port 8443) if absent, so PentAGI shows up in the Projects list. Called
     * lazily when the PentAGI tab opens — never at app start.
     */
    fun ensureProjectEntry() {
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    if (projectStore.get("pentagi") == null) {
                        val dir: File = projectStore.projectDir("pentagi")
                        if (!dir.isDirectory) dir.mkdirs()
                        File(dir, "README.txt").apply {
                            if (!exists()) writeText(
                                "PentAGI is managed remotely from the PentAGI tab.\n" +
                                    "It runs on a Linux/Docker host — not on this device.\n" +
                                    "See PENTAGI_INTEGRATION.md in the HostMyWeb repository.\n"
                            )
                        }
                        projectStore.save(
                            ProjectConfig(name = "pentagi", runtime = RuntimeType.PENTAGI, port = 8443)
                        )
                        LogBus.i(LogCategory.PENTAGI, "Created pentagi project entry (remote-managed)")
                    }
                }
            } catch (e: Exception) {
                LogBus.w(LogCategory.PENTAGI, "Could not create pentagi project entry: ${e.message}")
            }
        }
    }

    fun refresh() {
        if (_busy.value) return
        scope.launch {
            _busy.value = true
            _lastError.value = null
            try {
                if (!isManagerConfigured()) {
                    _health.value = if (settings.pentagiManagerUrl.isBlank() && settings.pentagiRemoteUrl.isBlank()) {
                        PentagiHealth.notConfigured(
                            "Not configured. PentAGI runs on a remote Linux/Docker host — " +
                                "set the manager URL + token in Configuration below. " +
                                "Local execution on Android is not supported (Docker daemon required)."
                        )
                    } else {
                        PentagiHealth(
                            status = "INCOMPLETE_CONFIG",
                            detail = "Manager URL or token is missing/invalid. Complete the Configuration section below.",
                            checkedAt = System.currentTimeMillis(),
                            mode = PentagiDeployMode.REMOTE
                        )
                    }
                    return@launch
                }
                val statusRes = withContext(Dispatchers.IO) { client.status() }
                val probeRes = withContext(Dispatchers.IO) { client.probePentagi() }
                // Config-status is best-effort; refresh the checklist when it answers.
                val cfgRes = withContext(Dispatchers.IO) { client.configStatus() }
                if (cfgRes is PentagiClient.Result.Ok) _configKeysSet.value = cfgRes.value.envKeysSet

                when (statusRes) {
                    is PentagiClient.Result.Err -> {
                        _lastError.value = statusRes.message
                        _health.value = PentagiHealth(
                            status = "MANAGER_UNREACHABLE",
                            pentagiReachable = (probeRes as? PentagiClient.Result.Ok)?.value == true,
                            managerReachable = false,
                            composeState = "unknown",
                            detail = "Manager unreachable: ${statusRes.message}",
                            checkedAt = System.currentTimeMillis(),
                            mode = PentagiDeployMode.REMOTE
                        )
                        LogBus.w(LogCategory.PENTAGI, "Manager unreachable: ${statusRes.message}")
                    }
                    is PentagiClient.Result.Ok -> {
                        val st = statusRes.value
                        _health.value = PentagiHealth(
                            status = if (st.pentagiReachable) "RUNNING" else st.composeState.uppercase(),
                            pentagiReachable = st.pentagiReachable,
                            managerReachable = true,
                            composeState = st.composeState,
                            detail = "Services: " + st.services.entries.joinToString { "${it.key}=${it.value}" },
                            checkedAt = System.currentTimeMillis(),
                            mode = PentagiDeployMode.REMOTE
                        )
                        LogBus.i(LogCategory.PENTAGI, "Status refreshed: compose=${st.composeState} pentagiReachable=${st.pentagiReachable}")
                    }
                }
            } finally {
                _busy.value = false
            }
        }
    }

    private fun runAction(action: PentagiAction) {
        if (_busy.value) return
        scope.launch {
            _busy.value = true
            _lastError.value = null
            try {
                LogBus.i(LogCategory.PENTAGI, "Action requested: ${action.name.lowercase()} (remote manager)")
                val res = withContext(Dispatchers.IO) { client.action(action) }
                when (res) {
                    is PentagiClient.Result.Ok -> LogBus.i(LogCategory.PENTAGI, "Action ${action.name.lowercase()} accepted by manager")
                    is PentagiClient.Result.Err -> {
                        _lastError.value = res.message
                        LogBus.e(LogCategory.PENTAGI, "Action ${action.name.lowercase()} failed: ${res.message}")
                    }
                }
                _busy.value = false
                refresh()
            } finally {
                if (_busy.value) _busy.value = false
            }
        }
    }

    fun start() = runAction(PentagiAction.START)
    fun stop() = runAction(PentagiAction.STOP)
    fun restart() = runAction(PentagiAction.RESTART)

    fun fetchLogs(tail: Int = 100) {
        if (_busy.value) return
        scope.launch {
            _busy.value = true
            _lastError.value = null
            try {
                val n = PentagiValidator.clampTail(tail)
                val res = withContext(Dispatchers.IO) { client.logs(n) }
                when (res) {
                    is PentagiClient.Result.Ok -> {
                        val lines = res.value.lines().takeLast(500)
                        _remoteLogs.value = lines
                        LogBus.i(LogCategory.PENTAGI, "Fetched ${lines.size} remote log line(s)")
                    }
                    is PentagiClient.Result.Err -> {
                        _lastError.value = res.message
                        LogBus.e(LogCategory.PENTAGI, "Fetch logs failed: ${res.message}")
                    }
                }
            } finally {
                _busy.value = false
            }
        }
    }
}
