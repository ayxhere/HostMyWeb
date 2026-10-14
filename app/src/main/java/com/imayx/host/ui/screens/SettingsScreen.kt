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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Upload
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.imayx.host.data.ThemeMode
import com.imayx.host.graph
import com.imayx.host.security.BiometricHelper
import com.imayx.host.ui.cleanDomain
import com.imayx.host.ui.components.BtnKind
import com.imayx.host.ui.components.ConfirmDialog
import com.imayx.host.ui.components.IxButton
import com.imayx.host.ui.components.IxCard
import com.imayx.host.ui.components.IxSelect
import com.imayx.host.ui.components.IxTextField
import com.imayx.host.ui.components.KeyValueRow
import com.imayx.host.ui.components.LocalSnack
import com.imayx.host.ui.components.PromptDialog
import com.imayx.host.ui.components.ScreenHeader
import com.imayx.host.ui.components.SecretField
import com.imayx.host.ui.components.SectionLabel
import com.imayx.host.ui.components.SwitchRow
import com.imayx.host.ui.isValidHostname
import com.imayx.host.ui.theme.Ix
import com.imayx.host.ui.vm.CloudflareViewModel
import com.imayx.host.ui.vm.SettingsViewModel
import com.imayx.host.utils.Fmt

@Composable
fun SettingsScreen(vm: SettingsViewModel = viewModel(), cf: CloudflareViewModel = viewModel()) {
    val c = Ix.c
    val ctx = LocalContext.current
    val ui by vm.ui.collectAsStateWithLifecycle()
    val cfUi by cf.ui.collectAsStateWithLifecycle()
    val snack = LocalSnack.current
    val gate = LocalGate.current
    val lock = ctx.graph.appLock

    LaunchedEffect(Unit) { vm.refresh(); cf.refresh() }
    LaunchedEffect(Unit) { vm.messages.collect { snack(it) } }
    LaunchedEffect(Unit) { cf.messages.collect { snack(it) } }

    var portText by remember(ui.port) { mutableStateOf(ui.port.toString()) }
    var domainText by remember(ui.defaultDomain) { mutableStateOf(ui.defaultDomain) }
    var cfUrl by remember(ui.cloudflaredUrl) { mutableStateOf(ui.cloudflaredUrl) }
    var nodeUrl by remember(ui.nodeUrl) { mutableStateOf(ui.nodeUrl) }
    var tunnelToken by remember { mutableStateOf("") }
    var apiToken by remember { mutableStateOf("") }
    var creds by remember { mutableStateOf(!lock.isEnabled() || lock.isSensitiveUnlocked()) }
    var pinDialog by remember { mutableStateOf(false) }
    var disableDialog by remember { mutableStateOf(false) }
    var removeNode by remember { mutableStateOf(false) }

    val nodeImport = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importNode(uri)
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        ScreenHeader("Settings", modifier = Modifier.padding(0.dp))

        SectionLabel("General")
        IxCard {
            IxSelect(
                "Theme",
                when (ui.theme) { ThemeMode.AMOLED -> "AMOLED black"; ThemeMode.DARK -> "Dark"; ThemeMode.LIGHT -> "Light" },
                listOf(ThemeMode.AMOLED.name to "AMOLED black", ThemeMode.DARK.name to "Dark", ThemeMode.LIGHT.name to "Light"),
                { vm.setTheme(ThemeMode.valueOf(it)) }
            )
            Spacer(Modifier.height(10.dp))
            IxTextField(
                portText, { portText = it.filter(Char::isDigit).take(5) }, "Default port",
                keyboardType = KeyboardType.Number,
                supportingText = "1024–65535 (used for new projects)"
            )
            IxButton("Save port", { portText.toIntOrNull()?.let { vm.setPort(it); snack("Port saved") } }, kind = BtnKind.Secondary,
                enabled = (portText.toIntOrNull() ?: 0) in 1024..65535)
            Spacer(Modifier.height(10.dp))
            IxSelect("Default project", ui.defaultProject.ifBlank { "None" }, ui.projects.map { it to it }, { vm.setDefaultProject(it) }, enabled = ui.projects.isNotEmpty())
            Spacer(Modifier.height(10.dp))
            val domainOk = domainText.isBlank() || isValidHostname(cleanDomain(domainText))
            IxTextField(domainText, { domainText = it }, "Default domain", placeholder = "imayx.in", isError = !domainOk, supportingText = if (domainOk) null else "Not a valid hostname")
            IxButton("Save domain", { vm.setDefaultDomain(cleanDomain(domainText)); snack("Domain saved") }, kind = BtnKind.Secondary, enabled = domainOk)
            Spacer(Modifier.height(6.dp))
            SwitchRow("Autosave in editor", ui.autosave, { vm.setAutosave(it) })
            SwitchRow("Notifications", ui.notifications, { vm.setNotifications(it) }, subtitle = "Status notification while hosting")
            SwitchRow("Start on boot", ui.startOnBoot, { vm.setStartOnBoot(it) }, subtitle = "Resume hosting after the phone restarts (opt-in)")
            SwitchRow("AyX backend", ui.ayxBackend, { vm.setAyxBackend(it) }, subtitle = "Run the AyX Group API inside the app (port 3101) while hosting")
        }

        SectionLabel("Cloudflare credentials")
        IxCard {
            if (!creds) {
                Text("Protected by App Lock.", color = c.soft, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                IxButton("Unlock to edit", { gate.request(lock) { creds = true } }, kind = BtnKind.Secondary)
            } else {
                SecretField("Tunnel token", tunnelToken, { tunnelToken = it }, hasSaved = cfUi.hasTunnelToken,
                    supportingText = "Stored in the Android Keystore. Never logged.")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IxButton("Save", { if (cf.saveTunnelToken(tunnelToken)) tunnelToken = "" }, enabled = tunnelToken.isNotBlank())
                    IxButton("Remove", { cf.clearTunnelToken() }, kind = BtnKind.Danger, enabled = cfUi.hasTunnelToken)
                }
                Spacer(Modifier.height(10.dp))
                SecretField("Cloudflare API token", apiToken, { apiToken = it }, hasSaved = cfUi.hasApiToken,
                    supportingText = "Separate from the tunnel token. Needs Zone:DNS:Edit and Tunnel:Edit.")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IxButton("Save", { if (cf.saveApiToken(apiToken)) apiToken = "" }, enabled = apiToken.isNotBlank())
                    IxButton("Verify", { cf.verifyApiToken() }, kind = BtnKind.Secondary, enabled = cfUi.hasApiToken, loading = cfUi.apiBusy)
                    IxButton("Remove", { cf.clearApiToken() }, kind = BtnKind.Danger, enabled = cfUi.hasApiToken)
                }
                cfUi.apiMessage?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, color = if (cfUi.apiOk == true) c.ok else c.danger, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        SectionLabel("cloudflared")
        IxCard {
            IxTextField(cfUrl, { cfUrl = it }, "Custom cloudflared URL (optional)", placeholder = "https://…/cloudflared-linux-arm64",
                supportingText = "Leave empty to use the official release. HTTPS only.")
            IxButton("Save URL", { vm.setCloudflaredUrl(cfUrl); snack("Saved") }, kind = BtnKind.Secondary)
        }

        SectionLabel("Node runtime")
        IxCard {
            KeyValueRow("Status", if (ui.nodeInstalled) "Installed" else "Node runtime not installed")
            if (ui.nodeInstalled) {
                KeyValueRow("Version", ui.nodeVersion ?: "Unknown")
                KeyValueRow("Source", ui.nodeSource ?: "—")
            }
            Spacer(Modifier.height(8.dp))
            IxTextField(nodeUrl, { nodeUrl = it }, "Node runtime URL (ARM64, Android-compatible)", placeholder = "https://…",
                supportingText = "Official Linux builds need glibc and will not run on Android.")
            IxButton("Save URL", { vm.setNodeUrl(nodeUrl); snack("Saved") }, kind = BtnKind.Secondary)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IxButton(
                    if (ui.nodePercent != null) "Downloading ${ui.nodePercent}%" else "Install",
                    { vm.installNode() }, icon = Icons.Rounded.Download, loading = ui.nodeBusy, enabled = nodeUrl.isNotBlank() || ui.nodeUrl.isNotBlank()
                )
                IxButton("Import file", { nodeImport.launch(arrayOf("*/*")) }, icon = Icons.Rounded.Upload, kind = BtnKind.Secondary, enabled = !ui.nodeBusy)
            }
            if (ui.nodeInstalled) {
                Spacer(Modifier.height(8.dp))
                IxButton("Remove runtime", { removeNode = true }, icon = Icons.Rounded.Delete, kind = BtnKind.Danger)
            }
        }

        SectionLabel("Security")
        IxCard {
            SwitchRow(
                "App lock", ui.lockEnabled,
                { if (it) pinDialog = true else disableDialog = true },
                subtitle = if (ui.lockEnabled) "PIN required after the app is in the background" else "Protect the app and credentials with a PIN"
            )
            if (ui.lockEnabled) {
                val canBio = BiometricHelper.canAuthenticate(ctx)
                SwitchRow("Biometric unlock", ui.biometric && canBio, { vm.setBiometric(it) }, enabled = canBio,
                    subtitle = if (canBio) null else "No biometrics enrolled on this device")
                IxButton("Change PIN", { pinDialog = true }, kind = BtnKind.Secondary)
            }
        }

        SectionLabel("Storage")
        IxCard {
            KeyValueRow("Projects", Fmt.bytes(ui.projectsBytes))
            KeyValueRow("Backups", Fmt.bytes(ui.backupsBytes))
            KeyValueRow("Binaries", Fmt.bytes(ui.binariesBytes))
            KeyValueRow("Cache", Fmt.bytes(ui.cacheBytes))
            Spacer(Modifier.height(8.dp))
            IxButton("Clear cache", { vm.clearCache() }, kind = BtnKind.Secondary)
        }

        SectionLabel("About")
        IxCard {
            KeyValueRow("App", "IMAYX Host")
            KeyValueRow("Version", remember { try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "—" } catch (_: Exception) { "—" } })
            KeyValueRow("Package", ctx.packageName, mono = true)
            Text(
                "Tokens are encrypted with the Android Keystore and are never written to logs, backups or project files.",
                color = c.soft, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp)
            )
        }
        Spacer(Modifier.height(24.dp))
    }

    if (pinDialog) {
        PromptDialog(
            title = "Set PIN", label = "PIN (4–12 digits)", initial = "", confirmText = "Save",
            keyboardType = KeyboardType.NumberPassword,
            validator = { if (it.length !in 4..12 || !it.all(Char::isDigit)) "4–12 digits" else null },
            onConfirm = { vm.setPin(it); pinDialog = false },
            onDismiss = { pinDialog = false }
        )
    }
    if (disableDialog) {
        ConfirmDialog("Disable app lock?", "The app will open without a PIN.", "Disable",
            onConfirm = { disableDialog = false; gate.request(lock) { vm.disableLock() } }, onDismiss = { disableDialog = false }, destructive = true)
    }
    if (removeNode) {
        ConfirmDialog("Remove Node runtime?", "Node projects will not start until it is installed again.", "Remove",
            onConfirm = { vm.removeNode(); removeNode = false }, onDismiss = { removeNode = false }, destructive = true)
    }
}
