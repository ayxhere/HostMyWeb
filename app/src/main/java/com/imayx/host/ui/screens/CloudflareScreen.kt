package com.imayx.host.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.imayx.host.data.TunnelMode
import com.imayx.host.data.TunnelStatus
import com.imayx.host.data.TunnelTransport
import com.imayx.host.graph
import com.imayx.host.ui.cleanDomain
import com.imayx.host.ui.components.BtnKind
import com.imayx.host.ui.components.IxButton
import com.imayx.host.ui.components.IxCard
import com.imayx.host.ui.components.IxSelect
import com.imayx.host.ui.components.IxTextField
import com.imayx.host.ui.components.KeyValueRow
import com.imayx.host.ui.components.LocalSnack
import com.imayx.host.ui.components.Meter
import com.imayx.host.ui.components.ScreenHeader
import com.imayx.host.ui.components.SecretField
import com.imayx.host.ui.components.SectionLabel
import com.imayx.host.ui.components.StatusDot
import com.imayx.host.ui.components.StatusPill
import com.imayx.host.ui.components.SwitchRow
import com.imayx.host.ui.isValidHostname
import com.imayx.host.ui.theme.Ix
import com.imayx.host.ui.tunnelStatusColor
import com.imayx.host.ui.tunnelStatusText
import com.imayx.host.ui.vm.CloudflareViewModel
import com.imayx.host.utils.Fmt

@Composable
fun CloudflareScreen(vm: CloudflareViewModel = viewModel()) {
    val c = Ix.c
    val ui by vm.ui.collectAsStateWithLifecycle()
    val tunnel by vm.tunnel.collectAsStateWithLifecycle()
    val snack = LocalSnack.current
    val gate = LocalGate.current
    val lock = LocalContext.current.graph.appLock

    LaunchedEffect(Unit) { vm.refresh() }
    LaunchedEffect(Unit) { vm.messages.collect { snack(it) } }

    var credentialsUnlocked by remember { mutableStateOf(!lock.isEnabled() || lock.isSensitiveUnlocked()) }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importBinary(uri)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        ScreenHeader(
            "Cloudflare",
            subtitle = "Tunnel to the public internet",
            modifier = Modifier.padding(horizontal = 0.dp),
            actions = { StatusPill(tunnelStatusText(tunnel.status), tunnelStatusColor(tunnel.status)) }
        )

        // ---- status ----
        IxCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(tunnelStatusColor(tunnel.status), pulse = tunnel.status == TunnelStatus.CONNECTED || tunnel.status == TunnelStatus.STARTING)
                Spacer(Modifier.width(6.dp))
                Text(
                    when (tunnel.status) {
                        TunnelStatus.CONNECTED -> "Connected"
                        TunnelStatus.STARTING -> "Connecting…"
                        TunnelStatus.FAILED -> "Connection failed"
                        TunnelStatus.DISCONNECTED -> "Cloudflare tunnel is not connected"
                    },
                    color = c.text,
                    style = MaterialTheme.typography.titleMedium
                )
            }
            Spacer(Modifier.height(10.dp))
            KeyValueRow("Mode", when (tunnel.mode) { TunnelMode.QUICK -> "Quick Tunnel"; TunnelMode.NAMED -> "Named Tunnel"; null -> if (ui.quick) "Quick Tunnel (selected)" else "Named Tunnel (selected)" })
            KeyValueRow("Tunnel name", "Not provided by cloudflared", valueColor = c.soft)
            KeyValueRow("Tunnel ID", tunnel.tunnelId ?: ui.tokenInfo?.tunnelId ?: "—", mono = true)
            KeyValueRow("Public hostname", tunnel.publicUrl ?: tunnel.hostnames.firstOrNull() ?: "Not detected", mono = true, valueColor = if (tunnel.publicUrl != null) c.accent else c.soft)
            KeyValueRow("Local service", "http://127.0.0.1:${vm.activePort()}", mono = true)
            KeyValueRow("Edge", tunnel.edgeLocation?.let { "$it · ${tunnel.connections} connection(s)" } ?: "—")
            KeyValueRow("Last connected", if (tunnel.lastConnectedAt > 0) Fmt.date(tunnel.lastConnectedAt) else "Never")
            if (tunnel.lastError != null) {
                Spacer(Modifier.height(6.dp))
                Text(tunnel.lastError ?: "", color = c.danger, style = MaterialTheme.typography.bodyMedium)
            } else if (tunnel.status == TunnelStatus.DISCONNECTED) {
                Spacer(Modifier.height(6.dp))
                Text(
                    if (ui.quick) "Press Connect Tunnel, or Start Host on the Dashboard." else "Add a Tunnel Token or enable Quick Tunnel",
                    color = c.soft,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IxButton(
                    "Connect Tunnel",
                    onClick = { vm.connect() },
                    icon = Icons.Rounded.Cloud,
                    enabled = tunnel.status != TunnelStatus.CONNECTED && tunnel.status != TunnelStatus.STARTING,
                    modifier = Modifier.weight(1f)
                )
                IxButton(
                    "Disconnect",
                    onClick = { vm.disconnect() },
                    icon = Icons.Rounded.CloudOff,
                    kind = BtnKind.Danger,
                    enabled = tunnel.status == TunnelStatus.CONNECTED || tunnel.status == TunnelStatus.STARTING,
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(8.dp))
            IxButton("Test Cloudflare", onClick = { vm.runTest() }, kind = BtnKind.Secondary, loading = ui.testRunning, modifier = Modifier.fillMaxWidth())
        }

        if (ui.testSteps.isNotEmpty()) {
            IxCard {
                Text("Connection test", color = c.text, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                for (step in ui.testSteps) {
                    Row(modifier = Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
                        val color = when (step.ok) {
                            true -> c.ok
                            false -> c.danger
                            null -> c.soft
                        }
                        Icon(
                            when (step.ok) {
                                true -> Icons.Rounded.Check
                                false -> Icons.Rounded.Close
                                null -> Icons.Rounded.Info
                            },
                            contentDescription = null,
                            tint = color,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(step.label, color = c.text, style = MaterialTheme.typography.titleSmall)
                            Text(step.detail, color = color, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }

        // ---- cloudflared binary ----
        SectionLabel("cloudflared")
        IxCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Cloud, contentDescription = null, tint = c.accent)
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        if (!ui.checked) "Checking…" else if (ui.installed) "Installed" else "Not installed",
                        color = c.text,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(ui.version ?: ui.source ?: "Install the official ARM64 build from Cloudflare's GitHub releases", color = c.soft, style = MaterialTheme.typography.bodySmall)
                }
            }
            if (ui.installed) {
                Spacer(Modifier.height(8.dp))
                KeyValueRow("Source", ui.source ?: "—")
                KeyValueRow("Binary", ui.binaryInfo ?: "—")
                KeyValueRow(
                    "Runs on device",
                    when (ui.runnable) { true -> "Yes"; false -> "No"; null -> "Unknown" },
                    valueColor = when (ui.runnable) { true -> c.ok; false -> c.danger; null -> c.soft }
                )
                if (ui.runError != null) {
                    Text("cloudflared binary incompatible: ${ui.runError}", color = c.danger, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                }
            }
            if (ui.installing) {
                Spacer(Modifier.height(10.dp))
                Meter("Downloading", ui.installPercent?.let { it / 100f }, ui.installPercent?.let { "$it%" } ?: "…")
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IxButton(
                    if (ui.installed) "Reinstall" else "Install cloudflared",
                    onClick = { vm.install() },
                    icon = Icons.Rounded.Download,
                    enabled = !ui.installing,
                    modifier = Modifier.weight(1f)
                )
                IxButton(
                    "Import file",
                    onClick = { importLauncher.launch(arrayOf("*/*")) },
                    icon = Icons.Rounded.Upload,
                    kind = BtnKind.Secondary,
                    enabled = !ui.installing,
                    modifier = Modifier.weight(1f)
                )
            }
            if (ui.source == "downloaded by app") {
                Spacer(Modifier.height(8.dp))
                IxButton("Remove downloaded binary", onClick = { vm.uninstall() }, icon = Icons.Rounded.Delete, kind = BtnKind.Ghost)
            }
        }

        // ---- credentials ----
        SectionLabel("Tunnel mode & credentials")
        IxCard {
            SwitchRow(
                title = "Cloudflare Quick Tunnel",
                checked = ui.quick,
                onChange = { vm.setQuick(it) },
                subtitle = if (ui.quick) "ON — temporary trycloudflare.com URL, no token needed" else "OFF — a Tunnel Token is required"
            )
            Spacer(Modifier.height(8.dp))
            IxSelect(
                label = "Tunnel transport",
                selectedLabel = when (ui.transport) {
                    TunnelTransport.AUTO -> "Auto (recommended)"
                    TunnelTransport.QUIC -> "QUIC (UDP)"
                    TunnelTransport.HTTP2 -> "HTTP/2 (TCP)"
                },
                options = listOf(
                    TunnelTransport.AUTO.name to "Auto (recommended)",
                    TunnelTransport.QUIC.name to "QUIC (UDP)",
                    TunnelTransport.HTTP2.name to "HTTP/2 (TCP)"
                ),
                onSelect = { vm.setTransport(TunnelTransport.valueOf(it)) }
            )
            if (ui.vpnActive) {
                Spacer(Modifier.height(6.dp))
                Text(
                    if (ui.transport == TunnelTransport.QUIC)
                        "A VPN is active on this device — QUIC (UDP) will likely fail. Switch the transport to Auto or HTTP/2."
                    else
                        "A VPN is active on this device — the tunnel will use HTTP/2 instead of QUIC, " +
                            "because VPNs usually block the UDP traffic QUIC needs.",
                    color = c.warn,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Spacer(Modifier.height(8.dp))
            if (!credentialsUnlocked) {
                IxButton(
                    "Unlock credentials",
                    onClick = { gate.request(lock) { credentialsUnlocked = true } },
                    icon = Icons.Rounded.Lock,
                    kind = BtnKind.Secondary,
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                var token by remember { mutableStateOf("") }
                SecretField(
                    label = "Cloudflare Tunnel Token",
                    value = token,
                    onValueChange = { token = it },
                    hasSaved = ui.hasTunnelToken,
                    supportingText = "Runs: cloudflared tunnel run --token … Stored encrypted (Android Keystore). Not a Cloudflare login."
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IxButton(
                        "Save token",
                        onClick = { if (vm.saveTunnelToken(token)) token = "" },
                        enabled = token.isNotBlank(),
                        modifier = Modifier.weight(1f)
                    )
                    IxButton(
                        "Remove",
                        onClick = { vm.clearTunnelToken() },
                        kind = BtnKind.Danger,
                        enabled = ui.hasTunnelToken,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        // ---- domain & routes ----
        SectionLabel("Domain & routes")
        RoutesCard(vm)
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun RoutesCard(vm: CloudflareViewModel) {
    val c = Ix.c
    val ui by vm.ui.collectAsStateWithLifecycle()
    var hostname by remember { mutableStateOf(vm.activeDomain()) }
    var service by remember { mutableStateOf("http://127.0.0.1:${vm.activePort()}") }
    val host = cleanDomain(hostname)
    val hostErr = if (host.isNotEmpty() && !isValidHostname(host)) "Enter a valid hostname" else null
    val canApi = ui.hasApiToken && ui.hasTunnelToken && ui.tokenInfo != null

    IxCard {
        Text("Public hostname → local service", color = c.text, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Text(
            "The domain must already be on your Cloudflare account, and the tunnel needs a Public Hostname for it. " +
                "IMAYX Host does not change DNS unless you press the buttons below with an API Token configured.",
            color = c.soft,
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(Modifier.height(12.dp))
        IxTextField(hostname, { hostname = it }, "Domain", placeholder = "imayx.in", isError = hostErr != null, supportingText = hostErr)
        Spacer(Modifier.height(8.dp))
        IxTextField(service, { service = it }, "Service", placeholder = "http://127.0.0.1:3000")
        Spacer(Modifier.height(12.dp))

        if (!canApi) {
            Text("Manual setup (no API token configured)", color = c.text, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            Text(
                "1. Cloudflare Zero Trust → Networks → Tunnels → your tunnel → Public Hostname → Add.\n" +
                    "2. Hostname: ${host.ifBlank { "your-domain" }}  ·  Service: ${service.ifBlank { "http://127.0.0.1:3000" }}\n" +
                    "3. Copy the tunnel token into the field above and turn Quick Tunnel OFF.\n" +
                    "4. Press Connect Tunnel. Cloudflare creates the DNS record when the hostname is added in the dashboard.\n\n" +
                    "To manage routes from this app, save both the Tunnel Token and a Cloudflare API Token (Settings → Cloudflare) with " +
                    "\"Cloudflare Tunnel: Edit\" and \"DNS: Edit\" permissions.",
                color = c.soft,
                style = MaterialTheme.typography.bodySmall
            )
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IxButton("Load routes", { vm.loadRoutes() }, kind = BtnKind.Secondary, loading = ui.apiBusy, modifier = Modifier.weight(1f))
                IxButton("Save route", { vm.addRoute(host, service.trim()) }, enabled = host.isNotEmpty() && hostErr == null && service.isNotBlank() && !ui.apiBusy, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            IxButton(
                "Create DNS record (CNAME → tunnel)",
                onClick = { vm.createDns(host) },
                icon = Icons.Rounded.Link,
                kind = BtnKind.Secondary,
                enabled = host.isNotEmpty() && hostErr == null && !ui.apiBusy,
                modifier = Modifier.fillMaxWidth()
            )
        }

        if (ui.apiMessage != null) {
            Spacer(Modifier.height(8.dp))
            Text(ui.apiMessage ?: "", color = if (ui.apiOk == false) c.danger else c.ok, style = MaterialTheme.typography.bodySmall)
        }

        val routes = ui.routes
        if (canApi && routes != null) {
            Spacer(Modifier.height(12.dp))
            if (routes.isEmpty()) {
                Text("No public hostnames are configured on this tunnel yet.", color = c.soft, style = MaterialTheme.typography.bodySmall)
            }
            for (r in routes) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(r.hostname ?: "", color = c.text, style = MaterialTheme.typography.titleSmall)
                        Text("→ " + r.service, color = c.soft, style = MaterialTheme.typography.bodySmall)
                    }
                    IconButton(onClick = { vm.removeRoute(r.hostname ?: "") }) {
                        Icon(Icons.Rounded.Delete, contentDescription = "Remove route", tint = c.danger)
                    }
                }
            }
        }
    }
}
