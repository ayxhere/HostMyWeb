package com.imayx.host.cloudflare

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.imayx.host.data.AppSettings
import com.imayx.host.data.LogBus
import com.imayx.host.data.LogCategory
import com.imayx.host.data.LogLevel
import com.imayx.host.data.TunnelMode
import com.imayx.host.data.TunnelState
import com.imayx.host.data.TunnelStatus
import com.imayx.host.data.TunnelTransport
import com.imayx.host.runtime.ExecUtil
import com.imayx.host.runtime.ManagedProcess
import com.imayx.host.runtime.ProcessRegistry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.net.Inet4Address
import java.net.InetAddress

/**
 * True when a VPN transport is active on any current network.
 * Uses the existing ConnectivityManager — no extra permission required.
 */
fun isVpnActive(context: Context): Boolean {
    return try {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        cm.allNetworks.any { net ->
            cm.getNetworkCapabilities(net)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        }
    } catch (_: Exception) {
        false
    }
}

/**
 * Runs `cloudflared` as a managed child process and derives a real TunnelState from its output.
 * Nothing here is simulated: CONNECTED is only set after cloudflared logs "Registered tunnel connection".
 */
class TunnelController(
    private val context: Context,
    private val manager: CloudflareManager,
    private val registry: ProcessRegistry,
    private val settings: AppSettings
) {
    private val _state = MutableStateFlow(TunnelState())
    val state: StateFlow<TunnelState> = _state

    var onCrash: (() -> Unit)? = null

    private val dns = DnsHelper(context)

    /**
     * cloudflared normally finds the Cloudflare edge with an SRV lookup, which fails on Android (no resolv.conf).
     * We resolve the edge hostnames with Android's own resolver and pass the addresses explicitly.
     */
    private fun edgeArgs(): List<String> {
        val addrs = LinkedHashSet<String>()
        for (h in listOf("region1.v2.argotunnel.com", "region2.v2.argotunnel.com")) {
            try {
                for (a in InetAddress.getAllByName(h)) if (a is Inet4Address) addrs.add("${a.hostAddress}:7844")
            } catch (_: Exception) {
            }
        }
        if (addrs.isEmpty()) {
            addrs.addAll(listOf("198.41.192.7:7844", "198.41.200.13:7844", "198.41.192.27:7844", "198.41.200.23:7844"))
            LogBus.i(LogCategory.CLOUDFLARE, "Could not resolve Cloudflare edge; using built-in edge addresses")
        } else {
            LogBus.i(LogCategory.CLOUDFLARE, "Resolved ${addrs.size} Cloudflare edge address(es)")
        }
        val out = ArrayList<String>()
        for (a in addrs.take(4)) {
            out.add("--edge")
            out.add(a)
        }
        return out
    }

    @Volatile
    private var proc: ManagedProcess? = null
    private val lock = Any()

    /** Last start parameters, so AUTO mode can retry once with HTTP/2 after a QUIC failure. */
    @Volatile
    private var lastStart: Triple<TunnelMode, Int, String?>? = null
    @Volatile
    private var http2FallbackDone = false
    @Volatile
    private var usingHttp2 = false
    /** Incremented on every start; lets stale process-exit callbacks ignore themselves. */
    private var generation = 0

    private val quickUrlRx = Regex("https://[a-z0-9-]+\\.trycloudflare\\.com")
    private val tunnelIdRx = Regex("tunnelID=([0-9a-fA-F-]{36})")
    private val locationRx = Regex("location=([A-Za-z0-9-]+)")
    private val hostnameRx = Regex("\"hostname\":\"([^\"]+)\"")

    fun pids(): List<Int> {
        val pid = proc?.pid ?: return emptyList()
        return if (pid > 0) listOf(pid) else emptyList()
    }

    fun isActive(): Boolean = proc != null

    fun hasLastStart(): Boolean = lastStart != null

    /**
     * Restart with the SAME parameters after a network / VPN change. Transport and edge addresses are
     * decided again (AUTO: HTTP/2 while a VPN is up, QUIC otherwise). Never loops: one call = one start.
     */
    fun restartForNetworkChange(): String? {
        val l = lastStart ?: return "tunnel was never started"
        return synchronized(lock) {
            http2FallbackDone = false
            startLocked(l.first, l.second, l.third, forceHttp2 = false)
        }
    }

    /** Starts the tunnel. Returns an error message, or null when the process was launched. */
    fun start(mode: TunnelMode, port: Int, token: String?): String? {
        return synchronized(lock) {
            lastStart = Triple(mode, port, token)
            http2FallbackDone = false
            startLocked(mode, port, token, forceHttp2 = false)
        }
    }

    /**
     * AUTO-mode recovery: after a QUIC dial failure, restart ONCE with `--protocol http2`.
     * The [http2FallbackDone] flag is set atomically here, so this can never loop even if
     * several QUIC error lines arrive on both output pumps.
     */
    private fun retryWithHttp2() {
        val l = synchronized(lock) {
            if (http2FallbackDone) return
            http2FallbackDone = true
            lastStart
        } ?: return
        synchronized(lock) {
            startLocked(l.first, l.second, l.third, forceHttp2 = true)
        }
    }

    private fun startLocked(mode: TunnelMode, port: Int, token: String?, forceHttp2: Boolean): String? {
        stopLocked()
            if (!manager.isInstalled()) {
                return fail(mode, "cloudflared is not installed. Open the Cloudflare tab and press \"Install cloudflared\".")
            }
            val probe = manager.probe()
            if (probe == null || !probe.ok) {
                val why = ExecUtil.explain(probe?.error, manager.elfInfo() ?: com.imayx.host.runtime.ElfInfo.Info(false, "unknown", false, null))
                return fail(mode, "cloudflared binary incompatible: $why")
            }
            dns.start()
            val edge = edgeArgs()
            // Transport selection: explicit setting wins; AUTO uses QUIC unless a VPN is
            // active (VPNs usually block the UDP traffic QUIC needs), then HTTP/2 over TCP/443.
            val transportSetting = settings.tunnelTransport
            val vpnActive = isVpnActive(context)
            val useHttp2 = forceHttp2 || transportSetting == TunnelTransport.HTTP2 ||
                (transportSetting == TunnelTransport.AUTO && vpnActive)
            usingHttp2 = useHttp2
            val args = ArrayList<String>()
            args.add("tunnel")
            args.add("--no-autoupdate")
            if (useHttp2) {
                args.add("--protocol")
                args.add("http2")
                if (forceHttp2) {
                    LogBus.w(LogCategory.CLOUDFLARE, "Retrying tunnel with HTTP/2 transport (TCP/443) after QUIC failure")
                } else if (transportSetting == TunnelTransport.AUTO && vpnActive) {
                    LogBus.i(LogCategory.CLOUDFLARE, "VPN detected — using HTTP/2 transport (QUIC/UDP is usually blocked by VPNs)")
                } else {
                    LogBus.i(LogCategory.CLOUDFLARE, "Using HTTP/2 tunnel transport (per settings)")
                }
            }
            args.addAll(edge)
            args.add("--edge-ip-version")
            args.add("4")
            // Android has no resolv.conf: if this cloudflared build exposes the flag, give it public resolvers
            // so the local "[::1]:53 connection refused" lookups succeed. Skipped when unsupported.
            if (manager.supportsFlag("--dns-resolver-addrs")) {
                args.add("--dns-resolver-addrs")
                args.add("1.1.1.1:53")
                args.add("--dns-resolver-addrs")
                args.add("8.8.8.8:53")
            }
            var tunnelId: String? = null
            when (mode) {
                TunnelMode.QUICK -> {
                    args.addAll(listOf("--url", "http://127.0.0.1:$port"))
                }
                TunnelMode.NAMED -> {
                    val rawToken = token?.trim().orEmpty()
                    if (rawToken.isEmpty()) return fail(mode, "Add a Tunnel Token or enable Quick Tunnel.")
                    val t = TunnelToken.canonicalize(rawToken) ?: rawToken
                    val parsed = TunnelToken.parse(t)
                        ?: return fail(mode, "Invalid tunnel token: it is not a Cloudflare Tunnel token. Copy it from Zero Trust → Networks → Tunnels.")
                    tunnelId = parsed.tunnelId
                    args.addAll(listOf("run", "--token", t))
                }
            }
            val cmd = manager.command(args) ?: return fail(mode, "cloudflared is not installed.")

            val home = File(context.filesDir, "cf-home").also { it.mkdirs() }
            val env = LinkedHashMap<String, String>()
            env["HOME"] = home.absolutePath
            env["TMPDIR"] = context.cacheDir.absolutePath
            // Android keeps its CA bundle as one file per cert; Go honours SSL_CERT_DIR.
            env["SSL_CERT_DIR"] = "/system/etc/security/cacerts"

            _state.value = TunnelState(
                status = TunnelStatus.STARTING,
                mode = mode,
                tunnelId = tunnelId,
                startedAt = System.currentTimeMillis()
            )
            LogBus.i(LogCategory.CLOUDFLARE, "Starting cloudflared (${if (mode == TunnelMode.QUICK) "quick tunnel → 127.0.0.1:$port" else "named tunnel"})")

            generation++
            val gen = generation
            val p = ManagedProcess(
                command = cmd,
                workDir = home,
                env = env,
                registry = registry,
                registryMarker = "cloudflared",
                onLine = { line, _ -> handleLine(line) },
                onExit = { code, requested -> handleExit(gen, code, requested) }
            )
            try {
                p.start()
            } catch (e: Exception) {
                val info = manager.elfInfo()
                val why = if (info != null) ExecUtil.explain(e.message, info) else (e.message ?: "unknown error")
                return fail(mode, "cloudflared binary incompatible: $why")
            }
            proc = p
            startWatchdog(p)
            return null
    }

    private fun fail(mode: TunnelMode?, message: String): String {
        _state.value = TunnelState(status = TunnelStatus.FAILED, mode = mode, lastError = message)
        LogBus.e(LogCategory.CLOUDFLARE, message)
        return message
    }

    private fun startWatchdog(p: ManagedProcess) {
        val t = Thread({
            var waited = 0
            var active = true
            while (active && waited < 90) {
                try {
                    Thread.sleep(1000)
                } catch (_: InterruptedException) {
                    active = false
                }
                waited++
                if (proc !== p) active = false
                if (active && _state.value.status == TunnelStatus.CONNECTED) active = false
            }
            if (proc === p && _state.value.status == TunnelStatus.STARTING) {
                val prev = _state.value.lastError
                val msg = "Timed out waiting for the tunnel to connect (90s)." +
                    (if (prev != null) " Last error: $prev" else " Check your network connection.")
                _state.update { it.copy(status = TunnelStatus.FAILED, lastError = msg) }
                LogBus.e(LogCategory.CLOUDFLARE, msg)
                p.stop()
            }
        }, "ix-tunnel-watchdog")
        t.isDaemon = true
        t.start()
    }

    private fun levelOf(line: String): LogLevel = when {
        line.contains(" ERR ") || line.startsWith("ERR") -> LogLevel.ERROR
        line.contains(" WRN ") || line.startsWith("WRN") -> LogLevel.WARN
        line.contains(" DBG ") -> LogLevel.DEBUG
        else -> LogLevel.INFO
    }

    private fun handleLine(raw: String) {
        val line = raw.trim()
        val lower = line.lowercase()
        val level = if (levelOf(line) == LogLevel.INFO &&
            (lower.startsWith("failed to ") || lower.contains("token is not valid") || lower.startsWith("error "))
        ) LogLevel.ERROR else levelOf(line)
        LogBus.log(LogCategory.CLOUDFLARE, level, line)

        quickUrlRx.find(line)?.let { m ->
            if (_state.value.mode == TunnelMode.QUICK && !m.value.contains("api.trycloudflare.com")) {
                _state.update { it.copy(publicUrl = m.value) }
            }
        }
        tunnelIdRx.find(line)?.let { m -> _state.update { it.copy(tunnelId = m.groupValues[1]) } }

        if (line.contains("ingress") && line.contains("hostname")) {
            val cleaned = line.replace("\\\"", "\"")
            val hosts = hostnameRx.findAll(cleaned).map { it.groupValues[1] }.distinct().toList()
            if (hosts.isNotEmpty()) {
                _state.update { s ->
                    s.copy(
                        hostnames = hosts,
                        publicUrl = if (s.mode == TunnelMode.NAMED) "https://${hosts.first()}" else s.publicUrl
                    )
                }
            }
        }

        if (line.contains("Registered tunnel connection")) {
            val loc = locationRx.find(line)?.groupValues?.get(1)
            _state.update {
                it.copy(
                    status = TunnelStatus.CONNECTED,
                    connections = it.connections + 1,
                    edgeLocation = loc ?: it.edgeLocation,
                    lastConnectedAt = System.currentTimeMillis(),
                    lastError = null
                )
            }
        } else if (line.contains("Unregistered tunnel connection")) {
            _state.update {
                val n = (it.connections - 1).coerceAtLeast(0)
                it.copy(
                    connections = n,
                    status = if (n == 0 && it.status == TunnelStatus.CONNECTED) TunnelStatus.STARTING else it.status
                )
            }
        }

        if (level == LogLevel.ERROR) {
            val friendly = friendlyError(line)
            _state.update { it ->
                // Once connected, don't let routine log noise (e.g. the Named Tunnel's
                // expected DNS-fallback warnings) repaint a scary red banner over a
                // healthy tunnel. Genuine origin failures are still surfaced below.
                if (it.status == TunnelStatus.CONNECTED) it else it.copy(lastError = friendly)
            }
        } else if (line.contains("Unable to reach the origin service", ignoreCase = true)) {
            _state.update { it.copy(lastError = friendlyError(line)) }
        }

        // AUTO fallback: a QUIC dial failure almost always means something on the device
        // (usually a VPN) is blocking UDP. Restart ONCE with HTTP/2, then report normally.
        // The one-shot guard lives in retryWithHttp2() (atomic check-and-set).
        if (!usingHttp2 &&
            settings.tunnelTransport == TunnelTransport.AUTO &&
            line.contains("failed to dial to edge with quic", ignoreCase = true)
        ) {
            LogBus.w(
                LogCategory.CLOUDFLARE,
                "QUIC dial to the Cloudflare edge failed — a VPN on this device often blocks the UDP traffic QUIC needs. " +
                    "Retrying once with HTTP/2 transport…"
            )
            _state.update { it.copy(status = TunnelStatus.STARTING, lastError = "QUIC blocked (a VPN is the likely cause) — retrying once with HTTP/2…") }
            Thread({ retryWithHttp2() }, "ix-tunnel-h2retry").apply { isDaemon = true; start() }
        }
    }

    private fun friendlyError(line: String): String {
        val l = line.lowercase()
        return when {
            l.contains("unauthorized") || l.contains("invalid tunnel secret") || l.contains("tunnel credentials") ||
                l.contains("not valid") && l.contains("token") || l.contains("failed to unmarshal tunnel token") ->
                "Tunnel authentication failed: the tunnel token is invalid or the tunnel was deleted."
            (l.contains("lookup") && (l.contains(":53") || l.contains("no such host"))) ||
                l.contains("no such host") || l.contains("dial udp") && l.contains(":53") ->
                "DNS resolution failed inside cloudflared (Go builds read /etc/resolv.conf, which Android does not have). " +
                    "Quick Tunnel needs this lookup and cannot work here. Use a Named Tunnel (Tunnel Token) instead: it discovers the Cloudflare edge " +
                    "with its own DNS-over-TLS fallback. Otherwise import an Android-compatible cloudflared build."
            l.contains("unable to reach the origin") || l.contains("127.0.0.1") && l.contains("connection refused") ->
                "Local service unavailable: nothing is listening on the configured origin (is the host running on the right port?)."
            l.contains("failed to dial to edge with quic") ->
                "QUIC/UDP dial to the Cloudflare edge failed — a VPN on this device is the most likely cause (VPNs often block UDP). " +
                    if (http2FallbackDone) "HTTP/2 fallback was already tried: disable the VPN for IMAYX Host (split-tunneling), or set Tunnel transport to HTTP/2 in the Cloudflare tab."
                    else "Retrying automatically with HTTP/2 transport…"
            l.contains("network is unreachable") || l.contains("i/o timeout") || l.contains("no route to host") ->
                "Network unavailable: cloudflared cannot reach the Cloudflare edge."
            l.contains("certificate") && (l.contains("x509") || l.contains("unknown authority")) ->
                "TLS certificate validation failed (system CA store not readable by cloudflared)."
            else -> line.replace(Regex("^\\S+\\s+ERR\\s+"), "").take(300)
        }
    }

    private fun handleExit(gen: Int, code: Int, requested: Boolean) {
        synchronized(lock) {
            // Superseded by a newer start (e.g. the HTTP/2 retry): ignore this stale exit.
            if (gen != generation) return
            proc = null
        }
        if (requested) {
            _state.update {
                TunnelState(status = TunnelStatus.DISCONNECTED, mode = it.mode, lastConnectedAt = it.lastConnectedAt)
            }
            LogBus.i(LogCategory.CLOUDFLARE, "cloudflared stopped")
        } else {
            val msg = _state.value.lastError ?: "cloudflared exited unexpectedly (code $code)"
            _state.update {
                it.copy(status = TunnelStatus.FAILED, connections = 0, lastError = msg)
            }
            LogBus.e(LogCategory.CLOUDFLARE, "cloudflared exited (code $code)")
            onCrash?.invoke()
        }
    }

    fun stop() {
        synchronized(lock) { stopLocked() }
    }

    private fun stopLocked() {
        val p = proc
        proc = null
        if (p != null) {
            p.stop()
        }
        val s = _state.value
        if (s.status != TunnelStatus.FAILED) {
            _state.value = TunnelState(status = TunnelStatus.DISCONNECTED, mode = s.mode, lastConnectedAt = s.lastConnectedAt)
        }
    }
}
