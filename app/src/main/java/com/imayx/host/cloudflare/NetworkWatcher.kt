package com.imayx.host.cloudflare

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.imayx.host.ai.AiNotifier
import com.imayx.host.data.AppSettings
import com.imayx.host.data.LogBus
import com.imayx.host.data.LogCategory
import com.imayx.host.data.TunnelStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Keeps the Cloudflare tunnel healthy when the network changes (VPN on/off, Wi-Fi <-> mobile data).
 *
 * Why: when a VPN starts or stops, Android moves the default route. cloudflared's open edge sockets die
 * ("network is unreachable") and it only retries with its own slow back-off. Here we notice the change,
 * wait ~3 s for the new network to settle, then restart cloudflared ONCE with the right transport
 * (HTTP/2 when a VPN is up, QUIC otherwise) and freshly resolved edge addresses.
 *
 * Also a health loop: if the tunnel stays disconnected / failed for too long while hosting is wanted,
 * it is restarted (bounded: max 5 restarts per 10 min, never for token / install errors).
 */
class NetworkWatcher(
    private val context: Context,
    private val tunnel: TunnelController,
    private val settings: AppSettings
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pending: Job? = null
    private var health: Job? = null
    private var registered = false
    private var lastNet: Network? = null
    private var lastVpn: Boolean? = null
    private var lastRestart = 0L
    private val restarts = ArrayList<Long>()

    private val cm get() = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            if (network != lastNet) {
                lastNet = network
                schedule("default network changed")
            }
        }

        override fun onLost(network: Network) {
            if (network == lastNet) {
                lastNet = null
                schedule("network lost")
            }
        }

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            if (network != lastNet) return
            val vpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            val prev = lastVpn
            lastVpn = vpn
            if (prev != null && prev != vpn) schedule(if (vpn) "VPN turned on" else "VPN turned off")
        }
    }

    @Synchronized
    fun start() {
        if (!settings.netWatch) return
        if (!registered) {
            try {
                cm.registerDefaultNetworkCallback(callback)
                registered = true
                lastVpn = isVpnActive(context)
                LogBus.i(LogCategory.CLOUDFLARE, "Network watcher on (VPN/Wi-Fi changes restart the tunnel automatically)")
            } catch (e: Exception) {
                LogBus.w(LogCategory.CLOUDFLARE, "Network watcher unavailable: ${e.message}")
            }
        }
        if (health == null || health?.isActive != true) health = scope.launch { healthLoop() }
    }

    @Synchronized
    fun stop() {
        pending?.cancel()
        health?.cancel()
        health = null
        if (registered) {
            try { cm.unregisterNetworkCallback(callback) } catch (_: Exception) {}
            registered = false
        }
        lastNet = null
    }

    private fun schedule(reason: String) {
        pending?.cancel()
        pending = scope.launch {
            delay(3000) // let the new network settle; collapses bursts of callbacks into one
            onChanged(reason)
        }
    }

    private suspend fun onChanged(reason: String) {
        if (!settings.hostingWanted || !tunnel.hasLastStart()) return
        // Wait (max 40 s) for real connectivity before touching cloudflared.
        var waited = 0
        while (!hasInternet() && waited < 40) {
            delay(2000)
            waited += 2
        }
        if (!hasInternet()) {
            LogBus.w(LogCategory.CLOUDFLARE, "No internet after $reason — tunnel restart postponed")
            return
        }
        restartTunnel("network: $reason", notify = true)
    }

    private suspend fun restartTunnel(why: String, notify: Boolean) {
        val now = System.currentTimeMillis()
        if (now - lastRestart < 10_000) return
        lastRestart = now
        val vpn = isVpnActive(context)
        LogBus.i(LogCategory.CLOUDFLARE, "Restarting tunnel ($why, VPN=${if (vpn) "on" else "off"})")
        val t0 = System.currentTimeMillis()
        tunnel.restartForNetworkChange()
        // Wait up to 30 s for CONNECTED, retry once if it did not come up.
        for (attempt in 1..2) {
            var w = 0
            while (w < 30 && tunnel.state.value.status != TunnelStatus.CONNECTED) {
                delay(1000)
                w++
            }
            if (tunnel.state.value.status == TunnelStatus.CONNECTED) {
                val sec = (System.currentTimeMillis() - t0) / 1000
                if (notify) {
                    AiNotifier.post(
                        context, "Tunnel reconnected",
                        "$why → back online in ${sec}s (${if (vpn) "HTTP/2 over VPN" else "direct"}).",
                        id = 7002
                    )
                }
                return
            }
            if (attempt == 1) {
                LogBus.w(LogCategory.CLOUDFLARE, "Tunnel not connected 30 s after restart — retrying once")
                tunnel.restartForNetworkChange()
            }
        }
        LogBus.e(LogCategory.CLOUDFLARE, "Tunnel still not connected after network restart")
    }

    private suspend fun healthLoop() {
        var badSince = 0L
        while (true) {
            delay(20_000)
            try {
                if (!settings.hostingWanted || !tunnel.hasLastStart()) {
                    badSince = 0L
                    continue
                }
                val st = tunnel.state.value
                val bad = st.status == TunnelStatus.STARTING || st.status == TunnelStatus.FAILED ||
                    (st.status == TunnelStatus.CONNECTED && st.connections == 0)
                if (!bad) {
                    badSince = 0L
                    continue
                }
                if (badSince == 0L) badSince = System.currentTimeMillis()
                if (System.currentTimeMillis() - badSince < 60_000) continue
                val err = st.lastError.orEmpty().lowercase()
                if (err.contains("authentication") || err.contains("not installed") || err.contains("add a tunnel token") ||
                    err.contains("invalid tunnel token") || err.contains("incompatible")
                ) continue // restarting cannot fix these
                val now = System.currentTimeMillis()
                restarts.removeAll { now - it > 10 * 60_000 }
                if (restarts.size >= 5) continue
                if (!hasInternet()) continue
                restarts.add(now)
                badSince = 0L
                restartTunnel("tunnel was down > 60 s", notify = true)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
            }
        }
    }

    private fun hasInternet(): Boolean = try {
        val n = cm.activeNetwork
        val c = if (n != null) cm.getNetworkCapabilities(n) else null
        c?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    } catch (_: Exception) {
        false
    }
}
