package com.imayx.host.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Redo
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.FindReplace
import androidx.compose.material.icons.rounded.Save
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.imayx.host.editor.Highlighter
import com.imayx.host.editor.Lang
import com.imayx.host.editor.SyntaxPalette
import com.imayx.host.ui.components.BtnKind
import com.imayx.host.ui.components.ConfirmDialog
import com.imayx.host.ui.components.EmptyState
import com.imayx.host.ui.components.ErrorState
import com.imayx.host.ui.components.IxButton
import com.imayx.host.ui.components.IxChip
import com.imayx.host.ui.components.IxTextField
import com.imayx.host.ui.components.LoadingState
import com.imayx.host.ui.components.LocalSnack
import com.imayx.host.ui.components.ScreenHeader
import com.imayx.host.ui.theme.Ix
import com.imayx.host.ui.vm.EditorTab
import com.imayx.host.ui.vm.EditorViewModel
import kotlinx.coroutines.delay

private class HighlightTransformation(val lang: Lang, val palette: SyntaxPalette) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText =
        TransformedText(Highlighter.highlight(text.text, lang, palette), OffsetMapping.Identity)
}

private val LINE_HEIGHT = 20.sp
private val CODE_SIZE = 13.sp

@Composable
fun EditorScreen(onOpenFiles: () -> Unit, vm: EditorViewModel = viewModel()) {
    val c = Ix.c
    val snack = LocalSnack.current
    LaunchedEffect(Unit) { vm.messages.collect { snack(it) } }

    var showSearch by remember { mutableStateOf(false) }
    var closeAsk by remember { mutableStateOf<Int?>(null) }
    val tab = vm.current
    // Reading this state makes the undo/redo buttons recompose when the history changes.
    val historyVersion = tab?.undoVersion ?: 0

    Column(modifier = Modifier.fillMaxSize()) {
        ScreenHeader(
            "Editor",
            subtitle = tab?.let { it.project + "/" + it.rel } ?: "No file open",
            actions = {
                if (tab != null) {
                    IconButton(onClick = { vm.undo(tab) }, enabled = historyVersion >= 0 && vm.canUndo(tab)) {
                        Icon(Icons.AutoMirrored.Rounded.Undo, contentDescription = "Undo", tint = if (vm.canUndo(tab)) c.text else c.soft.copy(alpha = 0.4f))
                    }
                    IconButton(onClick = { vm.redo(tab) }, enabled = historyVersion >= 0 && vm.canRedo(tab)) {
                        Icon(Icons.AutoMirrored.Rounded.Redo, contentDescription = "Redo", tint = if (vm.canRedo(tab)) c.text else c.soft.copy(alpha = 0.4f))
                    }
                    IconButton(onClick = { showSearch = !showSearch }) {
                        Icon(Icons.Rounded.FindReplace, contentDescription = "Find and replace", tint = if (showSearch) c.accent else c.text)
                    }
                    IconButton(onClick = { vm.save(tab) }, enabled = tab.dirty) {
                        Icon(Icons.Rounded.Save, contentDescription = "Save", tint = if (tab.dirty) c.accent else c.soft.copy(alpha = 0.4f))
                    }
                }
            }
        )

        if (vm.tabs.isNotEmpty()) {
            LazyRow(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                itemsIndexed(vm.tabs, key = { _, t -> t.key }) { index, t ->
                    val selected = index == vm.active
                    val shape = RoundedCornerShape(12.dp)
                    Row(
                        modifier = Modifier
                            .clip(shape)
                            .background(if (selected) c.accent.copy(alpha = 0.16f) else c.card2, shape)
                            .border(1.dp, if (selected) c.accent else c.border, shape)
                            .clickable { vm.select(index) }
                            .padding(start = 12.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (t.dirty) {
                            Box(Modifier.size(7.dp).background(c.warn, CircleShape))
                            Spacer(Modifier.width(6.dp))
                        }
                        Text(t.name, color = if (selected) c.text else c.soft, style = MaterialTheme.typography.labelLarge)
                        IconButton(onClick = { if (t.dirty) closeAsk = index else vm.close(index) }, modifier = Modifier.size(34.dp)) {
                            Icon(Icons.Rounded.Close, contentDescription = "Close", tint = c.soft, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        if (tab == null) {
            EmptyState(
                icon = Icons.Rounded.Code,
                title = "No file open",
                subtitle = "Open an HTML, CSS, JS, JSON, XML, TXT or MD file from the Files tab.",
                actionLabel = "Open Files",
                onAction = onOpenFiles
            )
        } else {
            if (showSearch) SearchBar(vm, tab)
            when {
                !tab.loaded -> LoadingState("Opening ${tab.name}…")
                tab.error != null -> ErrorState("Cannot open file", tab.error ?: "", onRetry = { vm.close(vm.active) }, retryLabel = "Close tab")
                else -> CodeArea(vm, tab, modifier = Modifier.weight(1f))
            }
        }
    }

    closeAsk?.let { idx ->
        val t = vm.tabs.getOrNull(idx)
        ConfirmDialog(
            title = "Unsaved changes",
            message = "\"${t?.name ?: "This file"}\" has unsaved changes. Close it and discard them?",
            confirmText = "Discard",
            destructive = true,
            onConfirm = {
                vm.close(idx)
                closeAsk = null
            },
            onDismiss = { closeAsk = null }
        )
    }
}

@Composable
private fun SearchBar(vm: EditorViewModel, tab: EditorTab) {
    val c = Ix.c
    var query by remember { mutableStateOf("") }
    var replacement by remember { mutableStateOf("") }
    var matchCase by remember { mutableStateOf(false) }
    val snack = LocalSnack.current
    val count = vm.countMatches(tab, query, matchCase)
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(bottom = 8.dp)
            .clip(shape)
            .background(c.card, shape)
            .border(1.dp, c.border, shape)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        IxTextField(query, { query = it }, "Find", supportingText = if (query.isNotEmpty()) "$count match${if (count == 1) "" else "es"}" else null)
        IxTextField(replacement, { replacement = it }, "Replace with")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IxChip("Aa", matchCase, { matchCase = !matchCase })
            IxButton("Prev", { if (!vm.find(tab, query, matchCase, forward = false)) snack("No matches") }, kind = BtnKind.Secondary, modifier = Modifier.weight(1f), enabled = query.isNotEmpty())
            IxButton("Next", { if (!vm.find(tab, query, matchCase, forward = true)) snack("No matches") }, kind = BtnKind.Secondary, modifier = Modifier.weight(1f), enabled = query.isNotEmpty())
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IxButton("Replace", { vm.replaceCurrent(tab, query, replacement, matchCase) }, kind = BtnKind.Secondary, modifier = Modifier.weight(1f), enabled = query.isNotEmpty())
            IxButton("Replace all", {
                val n = vm.replaceAll(tab, query, replacement, matchCase)
                snack(if (n == 0) "No matches" else "Replaced $n occurrence${if (n == 1) "" else "s"}")
            }, modifier = Modifier.weight(1f), enabled = query.isNotEmpty())
        }
    }
}

@Composable
private fun CodeArea(vm: EditorViewModel, tab: EditorTab, modifier: Modifier = Modifier) {
    val c = Ix.c
    val palette = remember(c) {
        SyntaxPalette(
            keyword = Color(0xFFC792EA),
            string = Color(0xFF9BD97F),
            number = Color(0xFFF2A65A),
            comment = Color(0xFF6B7280),
            tag = Color(0xFF7CC4FF),
            attr = Color(0xFFFFD479)
        )
    }
    // Remembered: this TextStyle was re-allocated on every CodeArea recomposition.
    val codeStyle = remember(c) {
        TextStyle(
            fontFamily = FontFamily.Monospace,
            fontSize = CODE_SIZE,
            lineHeight = LINE_HEIGHT,
            color = c.text
        )
    }
    val transformation = remember(tab.lang, palette) { HighlightTransformation(tab.lang, palette) }
    val vScroll = rememberScrollState()
    val hScroll = rememberScrollState()
    val density = LocalDensity.current
    val screenWidth = LocalConfiguration.current.screenWidthDp
    // One remembered pass per text change: count newlines + build the gutter
    // string together. Previously the newline count re-scanned the whole file
    // on EVERY recomposition of CodeArea (including ones unrelated to typing).
    val gutter = remember(tab.value.text) {
        val lineCount = tab.value.text.count { it == '\n' } + 1
        (1..lineCount).joinToString("\n")
    }

    // Autosave (optional, configured in Settings).
    val autosave = vm.autosave
    LaunchedEffect(tab.value.text, autosave) {
        if (autosave && tab.dirty) {
            delay(1500)
            if (tab.dirty) vm.save(tab, silent = true)
        }
    }
    // Scroll to the line found by search.
    LaunchedEffect(tab.scrollToLine) {
        val line = tab.scrollToLine
        if (line != null) {
            val y = with(density) { (LINE_HEIGHT.toPx() * line).toInt() }
            vScroll.animateScrollTo((y - 160).coerceAtLeast(0))
            tab.scrollToLine = null
        }
    }

    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(bottom = 8.dp)
            .clip(shape)
            .background(c.card, shape)
            .border(1.dp, c.border, shape)
            .verticalScroll(vScroll)
    ) {
        Text(
            text = gutter,
            style = codeStyle.copy(color = c.soft.copy(alpha = 0.7f), textAlign = TextAlign.End),
            modifier = Modifier
                .background(c.card2)
                .padding(horizontal = 8.dp, vertical = 10.dp)
                .defaultMinSize(minWidth = 28.dp)
        )
        Box(modifier = Modifier.weight(1f).horizontalScroll(hScroll)) {
            BasicTextField(
                value = tab.value,
                onValueChange = { vm.onChange(tab, it) },
                textStyle = codeStyle,
                cursorBrush = SolidColor(c.accent),
                visualTransformation = transformation,
                modifier = Modifier
                    .defaultMinSize(minWidth = (screenWidth - 80).dp, minHeight = 480.dp)
                    .padding(10.dp)
            )
        }
    }
}
