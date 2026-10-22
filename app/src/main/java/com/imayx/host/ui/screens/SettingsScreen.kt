package com.imayx.host.ui.screens

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Upload
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.imayx.host.ai.AiProvider
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
import com.imayx.host.ui.components.StatusPill
import com.imayx.host.ui.components.SwitchRow
import com.imayx.host.ui.isValidHostname
import com.imayx.host.ui.theme.Ix
import com.imayx.host.ui.vm.CfUi
import com.imayx.host.ui.vm.CloudflareViewModel
import com.imayx.host.ui.vm.SettingsUi
import com.imayx.host.ui.vm.SettingsViewModel
import com.imayx.host.utils.Fmt

/**
 * Settings is a HUB: one row per section, and every section is its own page
 * (the user asked for separate pages, not one long combined screen). The AI
 * providers each get a page too — own key, own model, own Test button.
 */
private enum class SettingsPage {
    HUB, GENERAL, CLOUDFLARE_CREDENTIALS, CLOUDFLARED, NODE,
    AI_GROQ, AI_GOOGLE, AI_OPENROUTER,
    BACKGROUND, SECURITY, STORAGE, ABOUT
}

private fun pageFor(p: AiProvider): SettingsPage = when (p) {
    AiProvider.GROQ -> SettingsPage.AI_GROQ
    AiProvider.GOOGLE -> SettingsPage.AI_GOOGLE
    AiProvider.OPENROUTER -> SettingsPage.AI_OPENROUTER
}

@Composable
fun SettingsScreen(vm: SettingsViewModel = viewModel(), cf: CloudflareViewModel = viewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val cfUi by cf.ui.collectAsStateWithLifecycle()
    val snack = LocalSnack.current

    LaunchedEffect(Unit) { vm.refresh(); cf.refresh() }
    LaunchedEffect(Unit) { vm.messages.collect { snack(it) } }
    LaunchedEffect(Unit) { cf.messages.collect { snack(it) } }

    var page by remember { mutableStateOf(SettingsPage.HUB) }
    BackHandler(enabled = page != SettingsPage.HUB) { page = SettingsPage.HUB }
    val back: () -> Unit = { page = SettingsPage.HUB }

    when (page) {
        SettingsPage.HUB -> SettingsHubPage(ui, vm, onOpen = { page = it })
        SettingsPage.GENERAL -> SettingsGeneralPage(vm, ui, snack, back)
        SettingsPage.CLOUDFLARE_CREDENTIALS -> SettingsCloudflarePage(cf, cfUi, back)
        SettingsPage.CLOUDFLARED -> SettingsCloudflaredPage(vm, ui, snack, back)
        SettingsPage.NODE -> SettingsNodePage(vm, ui, snack, back)
        SettingsPage.AI_GROQ -> AiProviderSettingsPage(AiProvider.GROQ, vm, ui, snack, back)
        SettingsPage.AI_GOOGLE -> AiProviderSettingsPage(AiProvider.GOOGLE, vm, ui, snack, back)
        SettingsPage.AI_OPENROUTER -> AiProviderSettingsPage(AiProvider.OPENROUTER, vm, ui, snack, back)
        SettingsPage.BACKGROUND -> SettingsBackgroundPage(vm, ui, snack, back)
        SettingsPage.SECURITY -> SettingsSecurityPage(vm, ui, back)
        SettingsPage.STORAGE -> SettingsStoragePage(vm, ui, back)
        SettingsPage.ABOUT -> SettingsAboutPage(back)
    }
}

/** Scaffold for one settings section: back arrow + title + scrolling content. */
@Composable
private fun SettingsSubPage(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val c = Ix.c
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
            IconButton(onClick = onBack) {
                Icon(Icons.Rounded.ArrowBack, contentDescription = "Back", tint = c.text)
            }
            Text(title, color = c.text, style = MaterialTheme.typography.headlineSmall)
        }
        content()
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SettingsNavRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    val c = Ix.c
    IxCard(onClick = onClick, padding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Icon(icon, contentDescription = null, tint = c.accent, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, color = c.text, style = MaterialTheme.typography.titleSmall)
                Text(subtitle, color = c.soft, style = MaterialTheme.typography.bodySmall)
            }
            Icon(Icons.Rounded.KeyboardArrowRight, contentDescription = null, tint = c.soft)
        }
    }
}

@Composable
private fun SettingsHubPage(ui: SettingsUi, vm: SettingsViewModel, onOpen: (SettingsPage) -> Unit) {
    val c = Ix.c
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        ScreenHeader("Settings", modifier = Modifier.padding(0.dp))

        SectionLabel("AI providers")
        for (p in AiProvider.entries) {
            val hasKey = ui.aiHasKey[p.id] == true
            val model = ui.aiModels[p.id] ?: p.defaultModels.first()
            SettingsNavRow(
                Icons.Rounded.SmartToy, "AI — ${p.label}",
                (if (hasKey) "Key saved · $model" else "No key yet · $model") +
                    if (ui.aiActiveProvider == p.id) " · tried first" else "",
                onClick = { onOpen(pageFor(p)) }
            )
        }
        IxCard {
            Text(
                "AI auto-fix: when a server project crashes, the AI reads the error logs and patches the project files by itself. If one provider fails, it automatically switches to the next (tried-first provider, then the rest) until one answers. A backup ZIP is always made before any edit.",
                color = c.soft, style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(6.dp))
            SwitchRow(
                "AI auto-fix",
                ui.aiAutoFix && ui.hasAnyAiKey,
                { vm.setAiAutoFix(it) },
                subtitle = if (!ui.hasAnyAiKey) "Add an API key on any provider page first"
                else if (ui.aiAutoFix) "On — crashes are fixed automatically" else "Off — fix manually with the AI Fix button",
                enabled = ui.hasAnyAiKey
            )
        }

        SectionLabel("App")
        SettingsNavRow(Icons.Rounded.Settings, "General", "Theme, ports, default project & domain") { onOpen(SettingsPage.GENERAL) }
        SettingsNavRow(Icons.Rounded.Cloud, "Cloudflare credentials", "Tunnel token & API token") { onOpen(SettingsPage.CLOUDFLARE_CREDENTIALS) }
        SettingsNavRow(Icons.Rounded.Cloud, "cloudflared", "Custom cloudflared binary URL") { onOpen(SettingsPage.CLOUDFLARED) }
        SettingsNavRow(
            Icons.Rounded.Terminal, "Node runtime",
            if (ui.nodeInstalled) "Installed · ${ui.nodeVersion ?: "unknown version"}" else "Not installed"
        ) { onOpen(SettingsPage.NODE) }
        SettingsNavRow(Icons.Rounded.Schedule, "Background (24/7)", "Battery & start on boot") { onOpen(SettingsPage.BACKGROUND) }
        SettingsNavRow(Icons.Rounded.Security, "Security", "App lock & biometrics") { onOpen(SettingsPage.SECURITY) }
        SettingsNavRow(Icons.Rounded.Storage, "Storage", "Project, backup & cache sizes") { onOpen(SettingsPage.STORAGE) }
        SettingsNavRow(Icons.Rounded.Info, "About", "Version & package") { onOpen(SettingsPage.ABOUT) }
        Spacer(Modifier.height(24.dp))
    }
}

/** One AI provider's own page: key, model, Test (working model auto-selects), tried-first control. */
@Composable
private fun AiProviderSettingsPage(
    provider: AiProvider,
    vm: SettingsViewModel,
    ui: SettingsUi,
    snack: (String) -> Unit,
    onBack: () -> Unit
) {
    val c = Ix.c
    val hasKey = ui.aiHasKey[provider.id] == true
    val isActive = ui.aiActiveProvider == provider.id
    var keyText by remember { mutableStateOf("") }
    var modelText by remember(ui.aiModels[provider.id]) {
        mutableStateOf(ui.aiModels[provider.id] ?: provider.defaultModels.first())
    }
    val test = ui.aiTestResults[provider.id]

    SettingsSubPage("AI — ${provider.label}", onBack) {
        IxCard {
            Text(
                "If ${provider.label} fails, the fixer automatically switches to the next provider. Get a key at ${provider.keyHint}.",
                color = c.soft, style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(10.dp))
            if (isActive) {
                StatusPill("Tried first", c.ok)
            } else {
                IxButton(
                    "Use this provider first",
                    { vm.setAiActiveProvider(provider); snack("${provider.label} will be tried first") },
                    kind = BtnKind.Secondary
                )
            }
            Spacer(Modifier.height(12.dp))
            SecretField(
                "${provider.label} API key", keyText, { keyText = it }, hasSaved = hasKey,
                supportingText = "Stored encrypted in the Android Keystore. Never logged."
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IxButton(
                    "Save key",
                    { if (vm.saveAiKey(provider, keyText)) { keyText = ""; snack("${provider.label} key saved") } else snack("Key is empty") },
                    kind = BtnKind.Secondary, enabled = keyText.isNotBlank()
                )
                if (hasKey) IxButton("Remove", { vm.clearAiKey(provider); snack("${provider.label} key removed") }, kind = BtnKind.Danger)
            }
            Spacer(Modifier.height(12.dp))
            IxTextField(
                modelText, { modelText = it }, "Model",
                supportingText = "Chat model id. Test below finds a working one and selects it automatically."
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IxButton(
                    "Save model",
                    { vm.setAiModel(provider, modelText); snack("Model saved") },
                    kind = BtnKind.Secondary, enabled = modelText.isNotBlank()
                )
                IxButton(
                    "Test",
                    { vm.testAiProvider(provider) },
                    loading = ui.aiTestBusy == provider.id,
                    enabled = hasKey && ui.aiTestBusy == null
                )
            }
            if (!hasKey) {
                Spacer(Modifier.height(6.dp))
                Text("Save an API key to enable Test.", color = c.soft, style = MaterialTheme.typography.bodySmall)
            }
            test?.let {
                Spacer(Modifier.height(8.dp))
                Text(it.text, color = if (it.ok) c.ok else c.danger, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun SettingsGeneralPage(vm: SettingsViewModel, ui: SettingsUi, snack: (String) -> Unit, onBack: () -> Unit) {
    var portText by remember(ui.port) { mutableStateOf(ui.port.toString()) }
    var primaryPortText by remember(ui.primaryPort) { mutableStateOf(ui.primaryPort.toString()) }
    var domainText by remember(ui.defaultDomain) { mutableStateOf(ui.defaultDomain) }

    SettingsSubPage("General", onBack) {
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
            IxTextField(
                primaryPortText, { primaryPortText = it.filter(Char::isDigit).take(5) }, "Primary port",
                keyboardType = KeyboardType.Number,
                supportingText = "1024–65535. Default port given to a bundle's MAIN server on import/detect. Every project then serves on its OWN saved port — point your Cloudflare tunnel routes at each project's own port."
            )
            IxButton("Save primary port", { primaryPortText.toIntOrNull()?.let { vm.setPrimaryPort(it); snack("Primary port saved — restart hosting to apply") } }, kind = BtnKind.Secondary,
                enabled = (primaryPortText.toIntOrNull() ?: 0) in 1024..65535)
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
            SwitchRow("Auto routes (api./hello.)", ui.autoRoutes, { vm.setAutoRoutes(it) }, subtitle = "Create Cloudflare route + DNS for every server's subdomain (needs API token + named tunnel)")
            SwitchRow("Auto reconnect on VPN/network change", ui.netWatch, { vm.setNetWatch(it) }, subtitle = "Restart the tunnel by itself when VPN or Wi-Fi/data changes")
        }
    }
}

@Composable
private fun SettingsCloudflarePage(cf: CloudflareViewModel, cfUi: CfUi, onBack: () -> Unit) {
    val c = Ix.c
    val ctx = LocalContext.current
    val gate = LocalGate.current
    val lock = ctx.graph.appLock
    var tunnelToken by remember { mutableStateOf("") }
    var apiToken by remember { mutableStateOf("") }
    var creds by remember { mutableStateOf(!lock.isEnabled() || lock.isSensitiveUnlocked()) }

    SettingsSubPage("Cloudflare credentials", onBack) {
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
    }
}

@Composable
private fun SettingsCloudflaredPage(vm: SettingsViewModel, ui: SettingsUi, snack: (String) -> Unit, onBack: () -> Unit) {
    var cfUrl by remember(ui.cloudflaredUrl) { mutableStateOf(ui.cloudflaredUrl) }

    SettingsSubPage("cloudflared", onBack) {
        IxCard {
            IxTextField(cfUrl, { cfUrl = it }, "Custom cloudflared URL (optional)", placeholder = "https://…/cloudflared-linux-arm64",
                supportingText = "Leave empty to use the official release. HTTPS only.")
            IxButton("Save URL", { vm.setCloudflaredUrl(cfUrl); snack("Saved") }, kind = BtnKind.Secondary)
        }
    }
}

@Composable
private fun SettingsNodePage(vm: SettingsViewModel, ui: SettingsUi, snack: (String) -> Unit, onBack: () -> Unit) {
    var nodeUrl by remember(ui.nodeUrl) { mutableStateOf(ui.nodeUrl) }
    var removeNode by remember { mutableStateOf(false) }
    val nodeImport = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importNode(uri)
    }

    SettingsSubPage("Node runtime", onBack) {
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
    }
    if (removeNode) {
        ConfirmDialog("Remove Node runtime?", "Node projects will not start until it is installed again.", "Remove",
            onConfirm = { vm.removeNode(); removeNode = false }, onDismiss = { removeNode = false }, destructive = true)
    }
}

@Composable
private fun SettingsBackgroundPage(vm: SettingsViewModel, ui: SettingsUi, snack: (String) -> Unit, onBack: () -> Unit) {
    val c = Ix.c
    val ctx = LocalContext.current

    SettingsSubPage("Background (24/7)", onBack) {
        IxCard {
            KeyValueRow(
                "Battery",
                if (ui.batteryUnrestricted) "Unrestricted" else "Optimized — Android may kill the app",
                valueColor = if (ui.batteryUnrestricted) c.ok else c.warn
            )
            if (!ui.batteryUnrestricted) {
                Spacer(Modifier.height(8.dp))
                IxButton("Allow background run", {
                    try {
                        ctx.startActivity(com.imayx.host.service.HostService.batteryUnrestrictedIntent(ctx))
                    } catch (_: Exception) {
                        snack("Could not open system settings")
                    }
                    vm.refresh()
                })
                Spacer(Modifier.height(4.dp))
            }
            SwitchRow(
                "Start hosting after reboot", ui.startOnBoot, { vm.setStartOnBoot(it) },
                subtitle = "Hosting resumes automatically when the phone restarts"
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Tip: on Xiaomi / Oppo / Vivo also allow \"Autostart\" and set battery to \"No restrictions\" in system settings, and lock IMAYX Host in the recent-apps list — otherwise the phone will still kill it.",
                color = c.soft, style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun SettingsSecurityPage(vm: SettingsViewModel, ui: SettingsUi, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val gate = LocalGate.current
    val lock = ctx.graph.appLock
    var pinDialog by remember { mutableStateOf(false) }
    var disableDialog by remember { mutableStateOf(false) }

    SettingsSubPage("Security", onBack) {
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
}

@Composable
private fun SettingsStoragePage(vm: SettingsViewModel, ui: SettingsUi, onBack: () -> Unit) {
    SettingsSubPage("Storage", onBack) {
        IxCard {
            KeyValueRow("Projects", Fmt.bytes(ui.projectsBytes))
            KeyValueRow("Backups", Fmt.bytes(ui.backupsBytes))
            KeyValueRow("Binaries", Fmt.bytes(ui.binariesBytes))
            KeyValueRow("Cache", Fmt.bytes(ui.cacheBytes))
            Spacer(Modifier.height(8.dp))
            IxButton("Clear cache", { vm.clearCache() }, kind = BtnKind.Secondary)
        }
    }
}

@Composable
private fun SettingsAboutPage(onBack: () -> Unit) {
    val c = Ix.c
    val ctx = LocalContext.current

    SettingsSubPage("About", onBack) {
        IxCard {
            KeyValueRow("App", "IMAYX Host")
            KeyValueRow("Version", remember { try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "—" } catch (_: Exception) { "—" } })
            KeyValueRow("Package", ctx.packageName, mono = true)
            Text(
                "Tokens are encrypted with the Android Keystore and are never written to logs, backups or project files.",
                color = c.soft, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}
