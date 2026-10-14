package com.imayx.host.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.imayx.host.data.LogBus
import com.imayx.host.data.LogCategory
import com.imayx.host.ui.components.BtnKind
import com.imayx.host.ui.components.EmptyState
import com.imayx.host.ui.components.IxButton
import com.imayx.host.ui.components.IxChip
import com.imayx.host.ui.components.LocalSnack
import com.imayx.host.ui.components.ScreenHeader
import com.imayx.host.ui.levelColor
import com.imayx.host.ui.theme.Ix
import com.imayx.host.utils.Fmt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun LogsScreen() {
    val c = Ix.c
    val ctx = LocalContext.current
    val snack = LocalSnack.current
    val scope = rememberCoroutineScope()
    val all by LogBus.entries.collectAsStateWithLifecycle()
    var category by remember { mutableStateOf<LogCategory?>(null) }
    var follow by remember { mutableStateOf(true) }
    val shown = remember(all, category) { LogBus.filter(all, category) }
    val listState = rememberLazyListState()

    LaunchedEffect(shown.size, follow) {
        if (follow && shown.isNotEmpty()) listState.scrollToItem(shown.size - 1)
    }
    val atEnd by remember { derivedStateOf { !listState.canScrollForward } }
    LaunchedEffect(atEnd) { follow = atEnd }

    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) scope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    ctx.contentResolver.openOutputStream(uri)?.use { it.write(LogBus.exportText(shown).toByteArray()) } != null
                } catch (_: Exception) {
                    false
                }
            }
            snack(if (ok) "Logs exported" else "Export failed")
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        ScreenHeader("Logs", subtitle = "${shown.size} entries · secrets are redacted")
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            IxChip("All", category == null, { category = null })
            for (cat in LogCategory.entries) IxChip(cat.label, category == cat, { category = cat })
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            IxButton("Copy", {
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("logs", LogBus.exportText(shown.takeLast(500))))
                snack("Copied last ${minOf(shown.size, 500)} lines")
            }, icon = Icons.Rounded.ContentCopy, kind = BtnKind.Secondary, enabled = shown.isNotEmpty())
            IxButton("Export", { exporter.launch("imayx-host-logs.txt") }, icon = Icons.Rounded.FileDownload, kind = BtnKind.Secondary, enabled = shown.isNotEmpty())
            IxButton("Clear", { LogBus.clear(category) }, icon = Icons.Rounded.DeleteSweep, kind = BtnKind.Danger, enabled = shown.isNotEmpty())
        }
        if (shown.isEmpty()) {
            EmptyState(Icons.Rounded.Terminal, "No logs yet", "Server, Cloudflare and system events appear here live.")
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                items(shown, key = { it.id }) { e ->
                    Text(
                        "${Fmt.time(e.time)} [${e.category.label}] ${e.message}",
                        color = levelColor(e.level),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}
