package com.imayx.host.ui

import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.Crossfade
import androidx.compose.runtime.LaunchedEffect
import com.imayx.host.security.findFragmentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.imayx.host.graph
import com.imayx.host.ui.components.LocalSnack
import com.imayx.host.ui.screens.BackupsScreen
import com.imayx.host.ui.screens.CloudflareScreen
import com.imayx.host.ui.screens.DashboardScreen
import com.imayx.host.ui.screens.EditorScreen
import com.imayx.host.ui.screens.FilesScreen
import com.imayx.host.ui.screens.Gate
import com.imayx.host.ui.screens.GateDialog
import com.imayx.host.ui.screens.LocalGate
import com.imayx.host.ui.screens.LockScreen
import com.imayx.host.ui.screens.LogsScreen
import com.imayx.host.ui.screens.PentagiScreen
import com.imayx.host.ui.screens.PreviewScreen
import com.imayx.host.ui.screens.ProjectsScreen
import com.imayx.host.ui.screens.SettingsScreen
import com.imayx.host.ui.theme.Ix
import com.imayx.host.ui.theme.IxTheme
import kotlinx.coroutines.launch

private class PreviewRequest(val local: String, val public: String?, val startPublic: Boolean)

@Composable
fun AppRoot() {
    val g = LocalContext.current.graph
    val theme by g.settings.theme.collectAsStateWithLifecycle()
    val activity = LocalContext.current.findFragmentActivity()
    LaunchedEffect(theme) {
        val light = theme == com.imayx.host.data.ThemeMode.LIGHT
        val style = if (light) SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
        else SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        activity?.enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
    }
    IxTheme(theme) {
        val unlocked by g.appLock.unlocked.collectAsStateWithLifecycle()
        val locked = g.appLock.isEnabled() && !unlocked
        Box(modifier = Modifier.fillMaxSize().background(Ix.c.bg)) {
            if (locked) LockScreen(g.appLock) else MainShell()
        }
    }
}

@Composable
private fun MainShell() {
    val c = Ix.c
    val g = LocalContext.current.graph
    var current by rememberSaveable { mutableStateOf(Screen.Dashboard.name) }
    val screen = Screen.valueOf(current)
    val snackState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val gate = remember { Gate() }
    var preview by remember { mutableStateOf<PreviewRequest?>(null) }
    val tunnel by g.tunnel.state.collectAsStateWithLifecycle()
    val host by g.host.state.collectAsStateWithLifecycle()

    val snack: (String) -> Unit = { msg -> scope.launch { snackState.currentSnackbarData?.dismiss(); snackState.showSnackbar(msg) } }

    fun openPreview(url: String) {
        val cfg = host.project?.let { g.projects.get(it) }
        val pub = publicUrlOf(tunnel, cfg, g.settings.defaultDomain)
        preview = PreviewRequest(
            local = if (pub != null && url == pub) "http://127.0.0.1:${cfg?.port ?: g.settings.defaultPort}/" else url,
            public = pub,
            startPublic = pub != null && url == pub
        )
    }

    CompositionLocalProvider(LocalSnack provides snack, LocalGate provides gate) {
        Scaffold(
            containerColor = c.bg,
            snackbarHost = { SnackbarHost(snackState, modifier = Modifier.navigationBarsPadding().padding(bottom = 72.dp)) },
            bottomBar = { BottomNav(screen) { current = it.name } }
        ) { pad ->
            Box(modifier = Modifier.fillMaxSize().padding(pad).statusBarsPadding()) {
                Crossfade(targetState = screen, label = "screen") { s ->
                    when (s) {
                        Screen.Dashboard -> DashboardScreen(onNavigate = { current = it.name }, onPreview = ::openPreview)
                        Screen.Projects -> ProjectsScreen(onPreview = ::openPreview)
                        Screen.Files -> FilesScreen(onOpenEditor = { current = Screen.Editor.name })
                        Screen.Editor -> EditorScreen(onOpenFiles = { current = Screen.Files.name })
                        Screen.Cloudflare -> CloudflareScreen()
                        Screen.PentAGI -> PentagiScreen()
                        Screen.Logs -> LogsScreen()
                        Screen.Backups -> BackupsScreen()
                        Screen.Settings -> SettingsScreen()
                    }
                }
            }
        }
        GateDialog(gate)
        preview?.let { p ->
            Box(modifier = Modifier.fillMaxSize().background(c.bg)) {
                PreviewScreen(p.local, p.public, p.startPublic) { preview = null }
            }
        }
    }
}

@Composable
private fun BottomNav(selected: Screen, onSelect: (Screen) -> Unit) {
    val c = Ix.c
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(c.card)
            .border(1.dp, c.border)
            .navigationBarsPadding()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        for (s in Screen.entries) {
            val on = s == selected
            Column(
                modifier = Modifier
                    .width(76.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (on) c.accent.copy(alpha = 0.16f) else androidx.compose.ui.graphics.Color.Transparent)
                    .clickable { onSelect(s) }
                    .padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(s.icon, contentDescription = s.label, tint = if (on) c.accent else c.soft)
                Text(s.label, color = if (on) c.accent else c.soft, style = MaterialTheme.typography.labelSmall, maxLines = 1)
            }
        }
    }
}
