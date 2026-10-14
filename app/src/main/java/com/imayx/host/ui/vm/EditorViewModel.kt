package com.imayx.host.ui.vm

import android.app.Application
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.imayx.host.editor.Highlighter
import com.imayx.host.editor.Lang
import com.imayx.host.files.FileOps
import com.imayx.host.graph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

/** One open file. Uses Compose snapshot state so the editor recomposes on every change. */
class EditorTab(val project: String, val rel: String) {
    val name: String = rel.substringAfterLast('/')
    val lang: Lang = Highlighter.langFor(name)
    var value by mutableStateOf(TextFieldValue(""))
    var saved by mutableStateOf("")
    var loaded by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)

    /** Line index the UI should scroll to (consumed by the screen). */
    var scrollToLine by mutableStateOf<Int?>(null)
    val undo = ArrayList<TextFieldValue>()
    val redo = ArrayList<TextFieldValue>()
    var lastPush = 0L
    var undoVersion by mutableIntStateOf(0)

    val dirty: Boolean get() = loaded && value.text != saved
    val key: String get() = "$project/$rel"
}

class EditorViewModel(app: Application) : AndroidViewModel(app) {
    private val g = app.graph

    val tabs = mutableStateListOf<EditorTab>()
    var active by mutableIntStateOf(0)

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages

    val autosave: Boolean get() = g.settings.autosave

    val current: EditorTab? get() = tabs.getOrNull(active)

    private fun ops(project: String) = FileOps(g.projectStore.projectDir(project))

    fun isEditable(name: String) = Highlighter.isEditable(name)

    fun open(project: String, rel: String) {
        val name = rel.substringAfterLast('/')
        if (!isEditable(name)) {
            _messages.tryEmit("\"$name\" is not an editable text type")
            return
        }
        val existing = tabs.indexOfFirst { it.project == project && it.rel == rel }
        if (existing >= 0) {
            active = existing
            return
        }
        val tab = EditorTab(project, rel)
        tabs.add(tab)
        active = tabs.size - 1
        viewModelScope.launch {
            try {
                val text = withContext(Dispatchers.IO) { ops(project).readText(rel) }
                tab.saved = text
                tab.value = TextFieldValue(text)
                tab.loaded = true
            } catch (e: Exception) {
                tab.error = e.message ?: "Could not open file"
                tab.loaded = true
            }
        }
    }

    fun select(index: Int) {
        if (index in tabs.indices) active = index
    }

    fun close(index: Int) {
        if (index !in tabs.indices) return
        tabs.removeAt(index)
        if (active >= tabs.size) active = (tabs.size - 1).coerceAtLeast(0)
    }

    fun onChange(tab: EditorTab, newValue: TextFieldValue) {
        if (newValue.text != tab.value.text) {
            val now = SystemClock.uptimeMillis()
            val big = abs(newValue.text.length - tab.value.text.length) > 20
            if (tab.undo.isEmpty() || now - tab.lastPush > 700 || big) {
                tab.undo.add(tab.value)
                if (tab.undo.size > 200) tab.undo.removeAt(0)
            }
            tab.lastPush = now
            tab.redo.clear()
            tab.undoVersion++
        }
        tab.value = newValue
    }

    fun canUndo(tab: EditorTab?): Boolean = tab != null && tab.undo.isNotEmpty()
    fun canRedo(tab: EditorTab?): Boolean = tab != null && tab.redo.isNotEmpty()

    fun undo(tab: EditorTab) {
        if (tab.undo.isEmpty()) return
        tab.redo.add(tab.value)
        tab.value = tab.undo.removeAt(tab.undo.size - 1)
        tab.lastPush = 0L
        tab.undoVersion++
    }

    fun redo(tab: EditorTab) {
        if (tab.redo.isEmpty()) return
        tab.undo.add(tab.value)
        tab.value = tab.redo.removeAt(tab.redo.size - 1)
        tab.lastPush = 0L
        tab.undoVersion++
    }

    fun save(tab: EditorTab, silent: Boolean = false) {
        if (!tab.loaded || tab.error != null) return
        val text = tab.value.text
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { ops(tab.project).writeText(tab.rel, text) }
                tab.saved = text
                if (!silent) _messages.emit("Saved ${tab.name}")
            } catch (e: Exception) {
                _messages.emit(e.message ?: "Save failed")
            }
        }
    }

    fun saveAll() {
        for (t in tabs) if (t.dirty) save(t, silent = true)
    }

    // ---- Search / replace --------------------------------------------------------------------

    fun countMatches(tab: EditorTab, query: String, matchCase: Boolean): Int {
        if (query.isEmpty()) return 0
        var count = 0
        var i = tab.value.text.indexOf(query, 0, ignoreCase = !matchCase)
        while (i >= 0 && count < 100_000) {
            count++
            i = tab.value.text.indexOf(query, i + query.length, ignoreCase = !matchCase)
        }
        return count
    }

    private fun lineOf(text: String, index: Int): Int {
        var line = 0
        val end = index.coerceIn(0, text.length)
        for (i in 0 until end) if (text[i] == '\n') line++
        return line
    }

    fun find(tab: EditorTab, query: String, matchCase: Boolean, forward: Boolean): Boolean {
        if (query.isEmpty()) return false
        val text = tab.value.text
        val sel = tab.value.selection
        var idx: Int
        if (forward) {
            idx = text.indexOf(query, sel.max, ignoreCase = !matchCase)
            if (idx < 0) idx = text.indexOf(query, 0, ignoreCase = !matchCase)
        } else {
            idx = if (sel.min > 0) text.lastIndexOf(query, sel.min - 1, ignoreCase = !matchCase) else -1
            if (idx < 0) idx = text.lastIndexOf(query, text.length, ignoreCase = !matchCase)
        }
        if (idx < 0) return false
        tab.value = tab.value.copy(selection = TextRange(idx, idx + query.length))
        tab.scrollToLine = lineOf(text, idx)
        return true
    }

    fun replaceCurrent(tab: EditorTab, query: String, replacement: String, matchCase: Boolean) {
        if (query.isEmpty()) return
        val sel = tab.value.selection
        val text = tab.value.text
        val selected = if (!sel.collapsed) text.substring(sel.min, sel.max) else ""
        if (selected.isNotEmpty() && selected.equals(query, ignoreCase = !matchCase)) {
            val newText = text.substring(0, sel.min) + replacement + text.substring(sel.max)
            onChange(tab, TextFieldValue(newText, TextRange(sel.min + replacement.length)))
        }
        find(tab, query, matchCase, forward = true)
    }

    fun replaceAll(tab: EditorTab, query: String, replacement: String, matchCase: Boolean): Int {
        if (query.isEmpty()) return 0
        val n = countMatches(tab, query, matchCase)
        if (n == 0) return 0
        val regex = Regex(Regex.escape(query), if (matchCase) emptySet() else setOf(RegexOption.IGNORE_CASE))
        val newText = regex.replace(tab.value.text) { replacement }
        onChange(tab, TextFieldValue(newText, TextRange(0)))
        return n
    }
}
