package com.imayx.host.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.imayx.host.data.LogLevel
import com.imayx.host.data.ProcessState
import com.imayx.host.data.ProjectConfig
import com.imayx.host.data.TunnelMode
import com.imayx.host.data.TunnelState
import com.imayx.host.data.TunnelStatus
import com.imayx.host.ui.theme.Ix

fun hostStatusText(s: ProcessState): String = when (s) {
    ProcessState.RUNNING -> "ONLINE"
    ProcessState.STARTING -> "STARTING"
    ProcessState.FAILED -> "FAILED"
    ProcessState.STOPPED -> "OFFLINE"
}

@Composable
fun hostStatusColor(s: ProcessState): Color = when (s) {
    ProcessState.RUNNING -> Ix.c.ok
    ProcessState.STARTING -> Ix.c.warn
    ProcessState.FAILED -> Ix.c.danger
    ProcessState.STOPPED -> Ix.c.soft
}

fun processStateText(s: ProcessState): String = s.name

fun tunnelStatusText(s: TunnelStatus): String = when (s) {
    TunnelStatus.CONNECTED -> "CONNECTED"
    TunnelStatus.STARTING -> "CONNECTING"
    TunnelStatus.FAILED -> "FAILED"
    TunnelStatus.DISCONNECTED -> "DISCONNECTED"
}

@Composable
fun tunnelStatusColor(s: TunnelStatus): Color = when (s) {
    TunnelStatus.CONNECTED -> Ix.c.ok
    TunnelStatus.STARTING -> Ix.c.warn
    TunnelStatus.FAILED -> Ix.c.danger
    TunnelStatus.DISCONNECTED -> Ix.c.soft
}

@Composable
fun levelColor(l: LogLevel): Color = when (l) {
    LogLevel.ERROR -> Ix.c.danger
    LogLevel.WARN -> Ix.c.warn
    LogLevel.INFO -> Ix.c.text
    LogLevel.DEBUG -> Ix.c.soft
}

/**
 * The public URL is only reported when it is actually known:
 *  - quick tunnel: the trycloudflare.com URL parsed from cloudflared's output
 *  - named tunnel: the hostname cloudflared reports in its ingress config, else the domain the user configured
 * Nothing is shown unless the tunnel is CONNECTED.
 */
fun publicUrlOf(tunnel: TunnelState, cfg: ProjectConfig?, defaultDomain: String): String? {
    if (tunnel.status != TunnelStatus.CONNECTED) return null
    tunnel.publicUrl?.let { return it }
    if (tunnel.mode == TunnelMode.NAMED) {
        val d = cfg?.domain?.takeIf { it.isNotBlank() } ?: defaultDomain.takeIf { it.isNotBlank() }
        if (d != null) return "https://" + d.removePrefix("https://").removePrefix("http://").trimEnd('/')
    }
    return null
}

fun cleanDomain(raw: String): String =
    raw.trim().removePrefix("https://").removePrefix("http://").trimEnd('/').lowercase()

fun isValidHostname(h: String): Boolean =
    Regex("^(?=.{1,253}$)([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,}$").matches(h)
