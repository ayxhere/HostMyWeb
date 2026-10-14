package com.imayx.host.ui.vm

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.imayx.host.cloudflare.IngressRoute
import com.imayx.host.cloudflare.TunnelToken
import com.imayx.host.cloudflare.TunnelTokenInfo
import com.imayx.host.cloudflare.isVpnActive
import com.imayx.host.data.LogBus
import com.imayx.host.data.LogCategory
import com.imayx.host.data.ProcessState
import com.imayx.host.data.TunnelMode
import com.imayx.host.data.TunnelState
import com.imayx.host.data.TunnelStatus
import com.imayx.host.data.TunnelTransport
import com.imayx.host.graph
import com.imayx.host.runtime.ExecUtil
import com.imayx.host.security.SecretStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

data class CfTestStep(val label: String, val ok: Boolean?, val detail: String)

data class CfUi(
    val checked: Boolean = false,
    val installed: Boolean = false,
    val source: String? = null,
    val version: String? = null,
    val binaryInfo: String? = null,
    val runnable: Boolean? = null,
    val runError: String? = null,
    val installing: Boolean = false,
    val installPercent: Int? = null,
    val quick: Boolean = true,
    val transport: TunnelTransport = TunnelTransport.AUTO,
    val vpnActive: Boolean = false,
    val hasTunnelToken: Boolean = false,
    val hasApiToken: Boolean = false,
    val tokenInfo: TunnelTokenInfo? = null,
    val testRunning: Boolean = false,
    val testSteps: List<CfTestStep> = emptyList(),
    val routes: List<IngressRoute>? = null,
    val apiBusy: Boolean = false,
    val apiMessage: String? = null,
    val apiOk: Boolean? = null
)

class CloudflareViewModel(app: Application) : AndroidViewModel(app) {
    private val g = app.graph

    private val _ui = MutableStateFlow(CfUi())
    val ui: StateFlow<CfUi> = _ui

    val tunnel: StateFlow<TunnelState> = g.tunnel.state

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages

    val activeProject: String get() = g.settings.activeProject
    val lockEnabled: Boolean get() = g.appLock.isEnabled()

    fun activePort(): Int {
        val h = g.host.state.value
        if (h.project != null && h.state != ProcessState.STOPPED) return h.port
        return g.projectStore.get(g.settings.activeProject)?.port ?: g.settings.defaultPort
    }

    fun activeDomain(): String =
        g.projectStore.get(g.settings.activeProject)?.domain?.takeIf { it.isNotBlank() } ?: g.settings.defaultDomain

    private fun tokenInfo(): TunnelTokenInfo? = g.secrets.get(SecretStore.KEY_TUNNEL_TOKEN)?.let { TunnelToken.parse(it) }

    fun refresh() {
        viewModelScope.launch {
            val snapshot = withContext(Dispatchers.IO) {
                val installed = g.cloudflare.isInstalled()
                val probe = if (installed) g.cloudflare.probe(force = true) else null
                val info = g.cloudflare.elfInfo()
                _ui.value.copy(
                    checked = true,
                    installed = installed,
                    source = g.cloudflare.source(),
                    version = if (probe?.ok == true) g.cloudflare.version() else null,
                    binaryInfo = info?.describe(),
                    runnable = probe?.ok,
                    runError = if (probe != null && !probe.ok && info != null) ExecUtil.explain(probe.error, info) else null,
                    quick = g.settings.quickTunnel,
                    transport = g.settings.tunnelTransport,
                    vpnActive = isVpnActive(getApplication()),
                    hasTunnelToken = g.secrets.has(SecretStore.KEY_TUNNEL_TOKEN),
                    hasApiToken = g.secrets.has(SecretStore.KEY_API_TOKEN),
                    tokenInfo = tokenInfo()
                )
            }
            _ui.value = snapshot.copy(
                installing = _ui.value.installing,
                installPercent = _ui.value.installPercent,
                testRunning = _ui.value.testRunning,
                testSteps = _ui.value.testSteps,
                routes = _ui.value.routes,
                apiBusy = _ui.value.apiBusy,
                apiMessage = _ui.value.apiMessage,
                apiOk = _ui.value.apiOk
            )
        }
    }

    // ---- credentials (never echoed back to the UI) ----------------------------------------------

    fun saveTunnelToken(value: String): Boolean {
        val v = TunnelToken.canonicalize(value) ?: ""
        if (v.isEmpty() || TunnelToken.parse(v) == null) {
            _messages.tryEmit("That does not look like a Cloudflare Tunnel token (Zero Trust → Networks → Tunnels → your tunnel → token).")
            return false
        }
        val ok = g.secrets.put(SecretStore.KEY_TUNNEL_TOKEN, v)
        _messages.tryEmit(if (ok) "Tunnel token saved securely" else "Could not store the token in the Android Keystore")
        refresh()
        return ok
    }

    fun clearTunnelToken() {
        g.secrets.remove(SecretStore.KEY_TUNNEL_TOKEN)
        _messages.tryEmit("Tunnel token removed")
        refresh()
    }

    fun saveApiToken(value: String): Boolean {
        val ok = g.secrets.put(SecretStore.KEY_API_TOKEN, value.trim())
        _messages.tryEmit(if (ok) "API token saved securely" else "Could not store the token in the Android Keystore")
        refresh()
        return ok
    }

    fun clearApiToken() {
        g.secrets.remove(SecretStore.KEY_API_TOKEN)
        _ui.value = _ui.value.copy(apiMessage = null, apiOk = null)
        _messages.tryEmit("API token removed")
        refresh()
    }

    fun setQuick(v: Boolean) {
        g.settings.quickTunnel = v
        _ui.value = _ui.value.copy(quick = v)
    }

    fun setTransport(t: TunnelTransport) {
        g.settings.tunnelTransport = t
        _ui.value = _ui.value.copy(transport = t)
    }

    // ---- cloudflared binary ----------------------------------------------------------------------

    fun install() {
        if (_ui.value.installing) return
        viewModelScope.launch {
            _ui.value = _ui.value.copy(installing = true, installPercent = 0)
            val result = withContext(Dispatchers.IO) {
                g.cloudflare.install { pct -> _ui.value = _ui.value.copy(installPercent = pct) }
            }
            _ui.value = _ui.value.copy(installing = false, installPercent = null)
            _messages.emit(result.message)
            refresh()
        }
    }

    fun importBinary(uri: Uri) {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(installing = true, installPercent = null)
            val result = withContext(Dispatchers.IO) {
                val input = getApplication<Application>().contentResolver.openInputStream(uri)
                if (input == null) com.imayx.host.runtime.InstallResult(false, "Cannot open the selected file")
                else input.use { g.cloudflare.installFromStream(it) }
            }
            _ui.value = _ui.value.copy(installing = false)
            _messages.emit(result.message)
            refresh()
        }
    }

    fun uninstall() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { g.cloudflare.uninstall() }
            _messages.emit("Downloaded cloudflared removed")
            refresh()
        }
    }

    // ---- tunnel control --------------------------------------------------------------------------

    fun connect() {
        viewModelScope.launch {
            val error = withContext(Dispatchers.IO) {
                val quick = g.settings.quickTunnel
                val token = if (quick) null else g.secrets.get(SecretStore.KEY_TUNNEL_TOKEN)
                g.tunnel.start(if (quick) TunnelMode.QUICK else TunnelMode.NAMED, activePort(), token)
            }
            if (error != null) _messages.emit(error)
            val h = g.host.state.value
            if (error == null && h.state != ProcessState.RUNNING) {
                _messages.emit("Tunnel started, but the host is offline — press Start Host so the origin is available.")
            }
        }
    }

    fun disconnect() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { g.tunnel.stop() }
            _messages.emit("Tunnel disconnected")
        }
    }

    // ---- connection test -------------------------------------------------------------------------

    fun runTest() {
        if (_ui.value.testRunning) return
        viewModelScope.launch {
            _ui.value = _ui.value.copy(testRunning = true, testSteps = emptyList())
            val steps = withContext(Dispatchers.IO) { buildTest() }
            _ui.value = _ui.value.copy(testRunning = false, testSteps = steps)
            LogBus.i(LogCategory.CLOUDFLARE, "Connection test finished: " + steps.joinToString { (if (it.ok == true) "OK" else if (it.ok == false) "FAIL" else "n/a") })
        }
    }

    private fun buildTest(): List<CfTestStep> {
        val steps = ArrayList<CfTestStep>()
        val cf = g.cloudflare
        val installed = cf.isInstalled()
        steps.add(CfTestStep("cloudflared installed", installed, if (installed) (cf.source() ?: "") else "Not installed — press Install cloudflared"))

        val info = cf.elfInfo()
        if (installed && info != null) {
            steps.add(CfTestStep("Binary architecture", info.isArm64, info.describe() + if (info.isArm64) "" else " — ARM64 required"))
            val probe = cf.probe(force = true)
            if (probe != null && probe.ok) {
                steps.add(CfTestStep("Binary executes on this device", true, (cf.version() ?: "ok") + if (probe.viaLinker) " (via system linker)" else ""))
            } else {
                steps.add(CfTestStep("Binary executes on this device", false, "cloudflared binary incompatible: " + ExecUtil.explain(probe?.error, info)))
            }
        } else {
            steps.add(CfTestStep("Binary architecture", null, "Skipped — nothing installed"))
            steps.add(CfTestStep("Binary executes on this device", null, "Skipped — nothing installed"))
        }

        val net = checkNetwork()
        steps.add(CfTestStep("Network reachable (Cloudflare)", net == null, net ?: "Reached cloudflare.com"))

        if (g.settings.quickTunnel) {
            steps.add(CfTestStep("Tunnel token", null, "Quick Tunnel is ON — token not required"))
        } else {
            val raw = g.secrets.get(SecretStore.KEY_TUNNEL_TOKEN)
            if (raw.isNullOrBlank()) {
                steps.add(CfTestStep("Tunnel token", false, "No token saved. Add a Tunnel Token or enable Quick Tunnel."))
            } else {
                val parsed = TunnelToken.parse(raw)
                steps.add(
                    CfTestStep(
                        "Tunnel token", parsed != null,
                        if (parsed != null) "Format valid (tunnel ${parsed.tunnelId.take(8)}…). Authentication is checked when the tunnel connects."
                        else "Invalid tunnel token format"
                    )
                )
            }
        }

        val port = activePort()
        steps.add(localCheck(port))

        val t = g.tunnel.state.value
        steps.add(CfTestStep("Tunnel process started", t.status != TunnelStatus.DISCONNECTED && t.status != TunnelStatus.FAILED || g.tunnel.isActive(),
            when (t.status) {
                TunnelStatus.DISCONNECTED -> "Not running — press Connect Tunnel"
                TunnelStatus.STARTING -> "Running, connecting…"
                TunnelStatus.CONNECTED -> "Running"
                TunnelStatus.FAILED -> t.lastError ?: "Failed"
            }))
        steps.add(CfTestStep("Tunnel connected", t.status == TunnelStatus.CONNECTED,
            if (t.status == TunnelStatus.CONNECTED) "${t.connections} connection(s)" + (t.edgeLocation?.let { " · edge $it" } ?: "")
            else (t.lastError ?: "Not connected")))
        val host = t.publicUrl ?: t.hostnames.firstOrNull()
        steps.add(CfTestStep("Public hostname detected", host != null, host ?: "Not detected yet"))
        return steps
    }

    private fun checkNetwork(): String? {
        return try {
            val conn = URL("https://www.cloudflare.com/cdn-cgi/trace").openConnection() as HttpURLConnection
            conn.connectTimeout = 6000
            conn.readTimeout = 6000
            val code = conn.responseCode
            conn.disconnect()
            if (code in 200..399) null else "Network unavailable or blocked (HTTP $code)"
        } catch (e: Exception) {
            "Network unavailable: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    private fun localCheck(port: Int): CfTestStep {
        return try {
            val conn = URL("http://127.0.0.1:$port/").openConnection() as HttpURLConnection
            conn.requestMethod = "HEAD"
            conn.connectTimeout = 2500
            conn.readTimeout = 2500
            val code = conn.responseCode
            conn.disconnect()
            CfTestStep("Local server reachable (127.0.0.1:$port)", true, "HTTP $code")
        } catch (e: Exception) {
            val running = g.host.state.value.state == ProcessState.RUNNING
            CfTestStep(
                "Local server reachable (127.0.0.1:$port)", false,
                if (running) "Local service unavailable: ${e.message}" else "Local service unavailable — host is offline. Press Start Host."
            )
        }
    }

    // ---- Cloudflare API (separate API token) -----------------------------------------------------

    private fun api(block: () -> String?) {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(apiBusy = true, apiMessage = null, apiOk = null)
            val result = withContext(Dispatchers.IO) {
                try {
                    block()
                } catch (e: Exception) {
                    "Error: ${e.message}"
                }
            }
            // block() returns null on success (and sets apiMessage itself via messages), or an error string.
            _ui.value = _ui.value.copy(apiBusy = false, apiOk = result == null, apiMessage = result ?: _ui.value.apiMessage)
        }
    }

    fun verifyApiToken() = api {
        val r = g.cloudflareApi.verifyToken(tokenInfo()?.accountId)
        if (r.ok) {
            _ui.value = _ui.value.copy(apiMessage = "API token is active")
            null
        } else r.error ?: "Token verification failed"
    }

    fun loadRoutes() = api {
        val t = tokenInfo() ?: return@api "Save your Tunnel Token first — it identifies which tunnel to read."
        val r = g.cloudflareApi.getIngress(t.accountId, t.tunnelId)
        if (r.ok) {
            _ui.value = _ui.value.copy(routes = r.data.orEmpty().filter { !it.hostname.isNullOrBlank() }, apiMessage = "Routes loaded from Cloudflare")
            null
        } else r.error ?: "Could not load routes"
    }

    fun addRoute(hostname: String, service: String) = api {
        val t = tokenInfo() ?: return@api "Save your Tunnel Token first."
        val current = g.cloudflareApi.getIngress(t.accountId, t.tunnelId)
        if (!current.ok) return@api current.error ?: "Could not read current routes"
        val routes = current.data.orEmpty().filter { !it.hostname.isNullOrBlank() && !it.hostname.equals(hostname, true) } + IngressRoute(hostname, service)
        val r = g.cloudflareApi.putIngress(t.accountId, t.tunnelId, routes)
        if (r.ok) {
            _ui.value = _ui.value.copy(routes = routes, apiMessage = "Route $hostname → $service saved in Cloudflare. DNS is a separate step.")
            null
        } else r.error ?: "Cloudflare rejected the route"
    }

    fun removeRoute(hostname: String) = api {
        val t = tokenInfo() ?: return@api "Save your Tunnel Token first."
        val current = g.cloudflareApi.getIngress(t.accountId, t.tunnelId)
        if (!current.ok) return@api current.error ?: "Could not read current routes"
        val routes = current.data.orEmpty().filter { !it.hostname.isNullOrBlank() && !it.hostname.equals(hostname, true) }
        val r = g.cloudflareApi.putIngress(t.accountId, t.tunnelId, routes)
        if (r.ok) {
            _ui.value = _ui.value.copy(routes = routes, apiMessage = "Route $hostname removed")
            null
        } else r.error ?: "Cloudflare rejected the change"
    }

    fun createDns(hostname: String) = api {
        val t = tokenInfo() ?: return@api "Save your Tunnel Token first."
        val zone = g.cloudflareApi.findZoneId(hostname)
        if (!zone.ok) return@api zone.error ?: "Zone lookup failed"
        val r = g.cloudflareApi.upsertTunnelCname(zone.data ?: "", hostname, t.tunnelId)
        if (r.ok) {
            _ui.value = _ui.value.copy(apiMessage = r.data ?: "DNS record saved")
            null
        } else r.error ?: "DNS update failed"
    }
}
