package com.imayx.host.cloudflare

import android.content.Context
import android.net.ConnectivityManager
import com.imayx.host.data.LogBus
import com.imayx.host.data.LogCategory
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Android has no /etc/resolv.conf, so Go programs (cloudflared) fall back to 127.0.0.1:53 / [::1]:53, where nothing
 * listens. This helper tries to listen on 127.0.0.1:53 and forwards raw DNS packets to the network's own resolvers.
 * Binding port 53 needs permission that many devices deny to apps; in that case this does nothing and the
 * tunnel relies on explicit edge addresses (see TunnelController.edgeArgs).
 */
class DnsHelper(private val context: Context) {

    @Volatile
    private var socket: DatagramSocket? = null
    private var pool: ExecutorService? = null
    private var tried = false

    @Synchronized
    fun start(): Boolean {
        if (socket != null) return true
        if (tried) return false
        tried = true
        val s = try {
            DatagramSocket(null).also {
                it.reuseAddress = true
                it.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 53))
            }
        } catch (e: Exception) {
            LogBus.i(LogCategory.CLOUDFLARE, "Local DNS helper unavailable (this device does not allow binding port 53); using direct edge addresses")
            return false
        }
        socket = s
        val ex = Executors.newCachedThreadPool()
        pool = ex
        Thread({
            val buf = ByteArray(4096)
            while (!s.isClosed) {
                try {
                    val p = DatagramPacket(buf, buf.size)
                    s.receive(p)
                    val query = p.data.copyOf(p.length)
                    val client = p.socketAddress
                    ex.execute {
                        val resp = forward(query)
                        if (resp != null) {
                            try {
                                s.send(DatagramPacket(resp, resp.size, client))
                            } catch (_: Exception) {
                            }
                        }
                    }
                } catch (_: Exception) {
                    if (s.isClosed) break
                }
            }
        }, "ix-dns").also { it.isDaemon = true }.start()
        LogBus.i(LogCategory.CLOUDFLARE, "Local DNS helper listening on 127.0.0.1:53")
        return true
    }

    @Synchronized
    fun stop() {
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        socket = null
        pool?.shutdownNow()
        pool = null
        tried = false
    }

    private fun upstreams(): List<InetAddress> {
        val out = ArrayList<InetAddress>()
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val net = cm.activeNetwork
            if (net != null) {
                cm.getLinkProperties(net)?.dnsServers?.filterIsInstance<Inet4Address>()?.let { out.addAll(it) }
            }
        } catch (_: Exception) {
        }
        try {
            out.add(InetAddress.getByAddress(byteArrayOf(1, 1, 1, 1)))
            out.add(InetAddress.getByAddress(byteArrayOf(8, 8, 8, 8)))
        } catch (_: Exception) {
        }
        return out
    }

    private fun forward(query: ByteArray): ByteArray? {
        for (server in upstreams()) {
            val u = DatagramSocket()
            try {
                u.soTimeout = 2500
                u.send(DatagramPacket(query, query.size, server, 53))
                val rb = ByteArray(4096)
                val rp = DatagramPacket(rb, rb.size)
                u.receive(rp)
                return rb.copyOf(rp.length)
            } catch (_: Exception) {
            } finally {
                u.close()
            }
        }
        return null
    }
}
