package com.imayx.host.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.OpenInBrowser
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.imayx.host.data.ProcessState
import com.imayx.host.data.TunnelStatus
import com.imayx.host.ui.Screen
import com.imayx.host.ui.components.BtnKind
import com.imayx.host.ui.components.EmptyState
import com.imayx.host.ui.components.IxButton
import com.imayx.host.ui.components.IxCard
import com.imayx.host.ui.components.IxSelect
import com.imayx.host.ui.components.KeyValueRow
import com.imayx.host.ui.components.LoadingState
import com.imayx.host.ui.components.Meter
import com.imayx.host.ui.components.StatTile
import com.imayx.host.ui.components.StatusDot
import com.imayx.host.ui.components.StatusPill
import com.imayx.host.ui.hostStatusColor
import com.imayx.host.ui.hostStatusText
import com.imayx.host.ui.publicUrlOf
import com.imayx.host.ui.theme.Ix
import com.imayx.host.ui.tunnelStatusColor
import com.imayx.host.ui.tunnelStatusText
import com.imayx.host.ui.vm.DashboardViewModel
import com.imayx.host.utils.Fmt
import kotlinx.coroutines.delay

@Composable
fun DashboardScreen(
    onNavigate: (Screen) -> Unit,
    onPreview: (String) -> Unit,
    vm: DashboardViewModel = viewModel()
) {
    val c = Ix.c
    val host by vm.host.collectAsStateWithLifecycle()
    val tunnel by vm.tunnel.collectAsStateWithLifecycle()
    val res by vm.resources.collectAsStateWithLifecycle()
    val projects by vm.projects.collectAsStateWithLifecycle()
    val loaded by vm.loaded.collectAsStateWithLifecycle()
    val active by vm.activeProject.collectAsStateWithLifecycle()
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(1000)
        }
    }

    LaunchedEffect(host.state, host.project) { vm.refresh() }

    val cfg = vm.config(host.project ?: active)
    val busy = host.state == ProcessState.STARTING || host.state == ProcessState.RUNNING
    val port = if (host.project != null) host.port else (cfg?.port ?: host.port)
    val publicUrl = publicUrlOf(tunnel, cfg, vm.defaultDomain)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("IMAYX Host", color = c.text, style = MaterialTheme.typography.headlineMedium)
                Text("Hosting control panel", color = c.soft, style = MaterialTheme.typography.bodyMedium)
            }
            StatusPill(hostStatusText(host.state), hostStatusColor(host.state))
        }

        if (!loaded) {
            LoadingState("Loading dashboard…")
            return@Column
        }

        if (projects.isEmpty()) {
            IxCard {
                EmptyState(
                    icon = Icons.Rounded.Language,
                    title = "No websites hosted yet",
                    subtitle = "Import your first ZIP",
                    actionLabel = "Add project",
                    onAction = { onNavigate(Screen.Projects) }
                )
            }
            return@Column
        }

        IxCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(hostStatusColor(host.state), pulse = host.state == ProcessState.RUNNING)
                Spacer(Modifier.width(6.dp))
                Column {
                    Text("Server status", color = c.soft, style = MaterialTheme.typography.labelMedium)
                    Text(
                        hostStatusText(host.state),
                        color = hostStatusColor(host.state),
                        style = MaterialTheme.typography.headlineMedium
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            when (host.state) {
                ProcessState.STOPPED -> Text("Host is offline", color = c.soft, style = MaterialTheme.typography.bodyMedium)
                ProcessState.FAILED -> Text(
                    host.error ?: "Hosting failed",
                    color = c.danger,
                    style = MaterialTheme.typography.bodyMedium
                )
                else -> {
                    val e = host.error
                    if (e != null) Text(e, color = c.warn, style = MaterialTheme.typography.bodyMedium)
                }
            }
            Spacer(Modifier.height(8.dp))
            KeyValueRow("Local server", "127.0.0.1:$port", mono = true, valueColor = if (busy) c.text else c.soft)
            KeyValueRow(
                "Cloudflare",
                tunnelStatusText(tunnel.status),
                valueColor = tunnelStatusColor(tunnel.status)
            )
            KeyValueRow("Public URL", publicUrl ?: "Not connected", mono = publicUrl != null, valueColor = if (publicUrl != null) c.accent else c.soft)
            if (tunnel.status != TunnelStatus.CONNECTED && tunnel.lastError != null) {
                Text(tunnel.lastError ?: "", color = c.danger, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
            }
            if (tunnel.status == TunnelStatus.DISCONNECTED && busy) {
                Text(
                    "Cloudflare tunnel is not connected. Add a Tunnel Token or enable Quick Tunnel in the Cloudflare tab.",
                    color = c.soft,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            Spacer(Modifier.height(10.dp))
            IxSelect(
                label = "Current project",
                selectedLabel = host.project ?: active,
                options = projects.map { it.name to it.name },
                onSelect = { vm.setActive(it) },
                enabled = !busy
            )
            Spacer(Modifier.height(14.dp))
            IxButton(
                "Start Host",
                onClick = { vm.startHost() },
                icon = Icons.Rounded.PlayArrow,
                enabled = !busy && active.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IxButton(
                    "Stop Host",
                    onClick = { vm.stopHost() },
                    icon = Icons.Rounded.Stop,
                    kind = BtnKind.Danger,
                    enabled = busy,
                    modifier = Modifier.weight(1f)
                )
                IxButton(
                    "Restart Host",
                    onClick = { vm.restartHost() },
                    icon = Icons.Rounded.RestartAlt,
                    kind = BtnKind.Secondary,
                    enabled = busy || host.state == ProcessState.FAILED,
                    modifier = Modifier.weight(1f)
                )
            }
            if (busy) {
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IxButton(
                        "Preview local",
                        onClick = { onPreview("http://127.0.0.1:$port/") },
                        icon = Icons.Rounded.OpenInBrowser,
                        kind = BtnKind.Secondary,
                        modifier = Modifier.weight(1f)
                    )
                    if (publicUrl != null) {
                        IxButton(
                            "Preview public",
                            onClick = { onPreview(publicUrl) },
                            icon = Icons.Rounded.Language,
                            kind = BtnKind.Secondary,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }

        IxCard {
            Text("Resources", color = c.text, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            val r = res
            if (r == null) {
                LoadingState("Measuring…")
            } else {
                Meter(
                    "CPU (this app + hosted processes)",
                    r.cpuPercent?.let { it / 100f },
                    Fmt.percent(r.cpuPercent)
                )
                Spacer(Modifier.height(12.dp))
                Meter(
                    "RAM (device)",
                    if (r.ramTotalBytes > 0) r.ramUsedBytes.toFloat() / r.ramTotalBytes else null,
                    "${Fmt.bytes(r.ramUsedBytes)} / ${Fmt.bytes(r.ramTotalBytes)}"
                )
                Spacer(Modifier.height(12.dp))
                Meter(
                    "Storage",
                    if (r.storageTotalBytes > 0) r.storageUsedBytes.toFloat() / r.storageTotalBytes else null,
                    "${Fmt.bytes(r.storageUsedBytes)} / ${Fmt.bytes(r.storageTotalBytes)}"
                )
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatTile(
                        "Uptime",
                        if (host.state == ProcessState.RUNNING && host.startedAt > 0) Fmt.duration(now - host.startedAt) else "—",
                        modifier = Modifier.weight(1f)
                    )
                    StatTile(
                        "App RAM",
                        Fmt.bytes(r.appRamBytes),
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatTile(
                        "Network ↓ / ↑",
                        if (r.rxPerSec != null && r.txPerSec != null) "${Fmt.bytes(r.rxPerSec)}/s · ${Fmt.bytes(r.txPerSec)}/s" else "Unavailable",
                        modifier = Modifier.weight(1f)
                    )
                    StatTile(
                        "Project size",
                        r.projectBytes?.let { Fmt.bytes(it) } ?: "Unavailable",
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}
