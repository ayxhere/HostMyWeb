package com.imayx.host.ui.screens

import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.OpenInBrowser
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.imayx.host.pentagi.PentagiDeployMode
import com.imayx.host.pentagi.PentagiLlmProvider
import com.imayx.host.pentagi.PentagiValidator
import com.imayx.host.ui.components.BtnKind
import com.imayx.host.ui.components.IxButton
import com.imayx.host.ui.components.IxCard
import com.imayx.host.ui.components.IxSelect
import com.imayx.host.ui.components.IxTextField
import com.imayx.host.ui.components.KeyValueRow
import com.imayx.host.ui.components.LocalSnack
import com.imayx.host.ui.components.ScreenHeader
import com.imayx.host.ui.components.SecretField
import com.imayx.host.ui.components.SectionLabel
import com.imayx.host.ui.components.StatusPill
import com.imayx.host.ui.components.SwitchRow
import com.imayx.host.ui.theme.Ix
import com.imayx.host.ui.vm.PentagiViewModel
import com.imayx.host.utils.Fmt

@Composable
fun PentagiScreen(vm: PentagiViewModel = viewModel()) {
    val c = Ix.c
    val ctx = LocalContext.current
    val snack = LocalSnack.current
    val health by vm.health.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val lastError by vm.lastError.collectAsStateWithLifecycle()
    val remoteLogs by vm.remoteLogs.collectAsStateWithLifecycle()
    val configKeysSet by vm.configKeysSet.collectAsStateWithLifecycle()
    val config by vm.config.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { vm.onScreenOpen() }
    LaunchedEffect(Unit) { vm.messages.collect { snack(it) } }

    // Editable form state — secrets start blank (write-only, never read back).
    var managerUrl by remember(config.managerUrl) { mutableStateOf(config.managerUrl) }
    var remoteUrl by remember(config.remoteUrl) { mutableStateOf(config.remoteUrl) }
    var publicUrl by remember(config.publicUrl) { mutableStateOf(config.publicUrl) }
    var provider by remember(config.provider) { mutableStateOf(config.provider) }
    var llmKey by remember { mutableStateOf("") }
    var managerToken by remember { mutableStateOf("") }
    var apiToken by remember { mutableStateOf("") }
    var confirmAction by remember { mutableStateOf<String?>(null) }

    val managerConfigured = config.hasManagerToken && config.managerUrl.isNotBlank()
    val providerObj = PentagiLlmProvider.fromId(provider)
    // Remembered: joinToString over the remote logs ran on EVERY recomposition
    // of this screen (e.g. each keystroke in the config fields below).
    val logsText = remember(remoteLogs) {
        if (remoteLogs.isEmpty()) "" else remoteLogs.joinToString("\n").take(12_000)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        ScreenHeader("PentAGI", subtitle = "AI security platform — remote Docker deployment")
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ---- Honest platform banner ----
            IxCard {
                Row {
                    Icon(Icons.Rounded.Security, contentDescription = null, tint = c.warn)
                    Spacer(Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Remote only — Docker required", color = c.text, style = MaterialTheme.typography.titleSmall)
                        Text(
                            "PentAGI spawns Docker sandbox containers (docker.sock, NET_ADMIN) and needs " +
                                "Postgres/pgvector on a real Linux host. It cannot run on Android or in a " +
                                "Termux proot — HostMyWeb manages it remotely instead. Nothing here claims " +
                                "PentAGI is running locally.",
                            color = c.soft, style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            // ---- (a) Status ----
            SectionLabel("Status")
            IxCard {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusPill(
                        when (health.mode) {
                            PentagiDeployMode.NOT_CONFIGURED -> "NOT CONFIGURED"
                            PentagiDeployMode.REMOTE -> "REMOTE"
                            PentagiDeployMode.LOCAL_UNSUPPORTED -> "LOCAL UNSUPPORTED"
                        },
                        if (health.mode == PentagiDeployMode.REMOTE) c.accent else c.warn
                    )
                    StatusPill(health.status, if (health.pentagiReachable) c.ok else c.soft)
                }
                Spacer(Modifier.height(10.dp))
                KeyValueRow("Manager reachable", if (health.managerReachable) "Yes" else "No",
                    valueColor = if (health.managerReachable) c.ok else c.danger)
                KeyValueRow("PentAGI reachable", if (health.pentagiReachable) "Yes" else "No",
                    valueColor = if (health.pentagiReachable) c.ok else c.soft)
                KeyValueRow("Compose state", health.composeState, mono = true)
                if (health.detail.isNotBlank()) KeyValueRow("Detail", health.detail)
                if (health.checkedAt > 0) KeyValueRow("Last checked", Fmt.time(health.checkedAt))
                if (lastError != null) {
                    Text(lastError ?: "", color = c.danger, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(4.dp))
                }
                Spacer(Modifier.height(8.dp))
                IxButton("Refresh status", onClick = { vm.refresh() }, icon = Icons.Rounded.Refresh,
                    kind = BtnKind.Secondary, loading = busy, modifier = Modifier.fillMaxWidth())
            }

            // ---- (b) Setup checklist ----
            SectionLabel("Setup checklist")
            IxCard {
                CheckRow("Remote manager configured", managerConfigured)
                CheckRow("Manager token saved", config.hasManagerToken)
                CheckRow("LLM provider selected (${providerObj.label} → ${providerObj.envKey})", true)
                CheckRow(
                    "LLM key saved on this device / set on remote",
                    config.hasLlmKey || providerObj.envKey in configKeysSet
                )
                CheckRow("Project entry created (Projects → pentagi)", config.projectEntryExists)
                CheckRow("PentAGI reachable from remote host", health.pentagiReachable)
                Text(
                    "Remote .env keys reported by the manager (names only): " +
                        if (configKeysSet.isEmpty()) "—" else configKeysSet.joinToString(", "),
                    color = c.soft, style = MaterialTheme.typography.bodySmall
                )
            }

            // ---- (c) Actions ----
            SectionLabel("Actions")
            IxCard {
                SwitchRow(
                    "PentAGI integration enabled", config.enabled, { vm.setEnabled(it) },
                    subtitle = "Master switch for this tab's remote controls"
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IxButton("Start", onClick = { confirmAction = "start" }, icon = Icons.Rounded.PlayArrow,
                        enabled = managerConfigured && config.enabled && !busy, modifier = Modifier.weight(1f))
                    IxButton("Stop", onClick = { confirmAction = "stop" }, icon = Icons.Rounded.Stop,
                        kind = BtnKind.Danger, enabled = managerConfigured && config.enabled && !busy,
                        modifier = Modifier.weight(1f))
                    IxButton("Restart", onClick = { confirmAction = "restart" }, icon = Icons.Rounded.RestartAlt,
                        kind = BtnKind.Secondary, enabled = managerConfigured && config.enabled && !busy,
                        modifier = Modifier.weight(1f))
                }
                Spacer(Modifier.height(8.dp))
                IxButton(
                    "Open PentAGI dashboard", icon = Icons.Rounded.OpenInBrowser, kind = BtnKind.Secondary,
                    enabled = config.remoteUrl.isNotBlank() && PentagiValidator.validateRemoteUrl(config.remoteUrl) == null,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        // Guarded: only a validated URL is ever handed to the browser.
                        if (PentagiValidator.validateRemoteUrl(config.remoteUrl) == null) {
                            try {
                                ctx.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse(config.remoteUrl))
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            } catch (_: Exception) {
                                snack("No browser could open the PentAGI URL")
                            }
                        }
                    }
                )
                if (!managerConfigured) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Start/Stop/Restart need the manager URL + token (Configuration below). " +
                            "Deploy pentagi-remote/ on your Linux host first — see PENTAGI_INTEGRATION.md.",
                        color = c.soft, style = MaterialTheme.typography.bodySmall
                    )
                }
                Text(
                    "Default PentAGI login is admin@pentagi.com / admin — change it immediately on the remote instance.",
                    color = c.warn, style = MaterialTheme.typography.bodySmall
                )
            }

            // ---- (d) Configuration ----
            SectionLabel("Configuration")
            IxCard {
                Text(
                    "Secrets are stored encrypted (Android Keystore) and never shown again, " +
                        "never logged, and never sent anywhere except as the manager Bearer token. " +
                        "The LLM key here is for reference/setup — the authoritative key lives in the " +
                        "remote .env (the manager reports only which keys are SET).",
                    color = c.soft, style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(10.dp))
                IxTextField(managerUrl, { managerUrl = it }, "Manager URL",
                    placeholder = "http://100.x.y.z:8787",
                    supportingText = "pentagi-remote manager — reach it via Tailscale / SSH tunnel, never public")
                Spacer(Modifier.height(8.dp))
                IxTextField(remoteUrl, { remoteUrl = it }, "PentAGI URL",
                    placeholder = "https://your-vps:8443",
                    supportingText = "PentAGI web UI on the remote host")
                Spacer(Modifier.height(8.dp))
                IxTextField(publicUrl, { publicUrl = it }, "Public URL (optional)",
                    placeholder = "https://pentagi.example.com",
                    supportingText = "Matches PUBLIC_URL in the remote .env")
                Spacer(Modifier.height(8.dp))
                IxSelect(
                    label = "LLM provider",
                    selectedLabel = providerObj.label,
                    options = PentagiLlmProvider.entries.map { it.id to "${it.label} (${it.envKey})" },
                    onSelect = { provider = it }
                )
                Spacer(Modifier.height(8.dp))
                SecretField("LLM API key (${providerObj.envKey})", llmKey, { llmKey = it },
                    hasSaved = config.hasLlmKey, supportingText = "Write-only — blank keeps the saved key")
                Spacer(Modifier.height(8.dp))
                SecretField("Manager token", managerToken, { managerToken = it },
                    hasSaved = config.hasManagerToken, supportingText = "Bearer token for pentagi-remote/manager (min 24 chars)")
                Spacer(Modifier.height(8.dp))
                SecretField("PentAGI API token (optional)", apiToken, { apiToken = it },
                    hasSaved = config.hasApiToken, supportingText = "Write-only — blank keeps the saved token")
                Spacer(Modifier.height(12.dp))
                IxButton("Save configuration", modifier = Modifier.fillMaxWidth(), onClick = {
                    vm.saveConfig(managerUrl, remoteUrl, publicUrl, provider, llmKey, managerToken, apiToken)
                    llmKey = ""; managerToken = ""; apiToken = ""
                })
            }

            // ---- (e) Logs ----
            SectionLabel("Remote logs")
            IxCard {
                Text(
                    "Logs come from the remote manager (docker compose logs). Secrets are redacted " +
                        "on the server before sending, and again by this app's log redactor.",
                    color = c.soft, style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(8.dp))
                IxButton("Fetch logs (last 100)", onClick = { vm.fetchLogs(100) },
                    kind = BtnKind.Secondary, loading = busy,
                    enabled = managerConfigured && !busy, modifier = Modifier.fillMaxWidth())
                if (logsText.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text(logsText, color = c.text, fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall)
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }

    confirmAction?.let { action ->
        AlertDialog(
            onDismissRequest = { confirmAction = null },
            containerColor = c.card, titleContentColor = c.text, textContentColor = c.soft,
            title = { Text("${action.replaceFirstChar { it.uppercase() }} PentAGI?") },
            text = { Text("This runs docker compose on the remote host via the manager. PentAGI sandboxes and data volumes are affected.") },
            confirmButton = {
                IxButton(action.replaceFirstChar { it.uppercase() }, onClick = {
                    confirmAction = null
                    when (action) {
                        "start" -> vm.start()
                        "stop" -> vm.stop()
                        "restart" -> vm.restart()
                    }
                }, kind = if (action == "stop") BtnKind.Danger else BtnKind.Primary)
            },
            dismissButton = { IxButton("Cancel", { confirmAction = null }, kind = BtnKind.Ghost) }
        )
    }
}

@Composable
private fun CheckRow(label: String, done: Boolean) {
    val c = Ix.c
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Icon(
            if (done) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
            contentDescription = null, tint = if (done) c.ok else c.soft
        )
        Spacer(Modifier.width(8.dp))
        Text(label, color = if (done) c.text else c.soft, style = MaterialTheme.typography.bodyMedium)
    }
}
