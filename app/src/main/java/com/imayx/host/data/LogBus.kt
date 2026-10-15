package com.imayx.host.data

import com.imayx.host.security.Redactor
import com.imayx.host.utils.Fmt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Process-wide log bus. Every message is passed through [Redactor] before it is stored, so no screen,
 * export or clipboard copy can ever contain a registered secret. Nothing is written to Logcat.
 */
object LogBus {
    private const val MAX_ENTRIES = 3000
    private const val MAX_MESSAGE = 4000

    private val lock = Any()
    private val buffer = ArrayDeque<LogEntry>()
    private var seq = 0L

    @Volatile
    private var dirty = false

    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries: StateFlow<List<LogEntry>> = _entries

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Wake-up signal for the publisher. CONFLATED = bursts collapse into one wakeup,
     * and the coroutine below suspends with zero CPU cost while no logs arrive
     * (previously this was a blind 200ms poll loop running forever).
     */
    private val wake = Channel<Unit>(Channel.CONFLATED)

    init {
        scope.launch {
            for (u in wake) {
                delay(150) // debounce: let a burst accumulate into a single publish
                while (wake.tryReceive().isSuccess) { /* drain */ }
                if (dirty) publish()
            }
        }
    }

    private fun publish() {
        val snapshot: List<LogEntry>
        synchronized(lock) {
            dirty = false
            snapshot = buffer.toList()
        }
        _entries.value = snapshot
    }

    fun log(category: LogCategory, level: LogLevel, message: String) {
        val clean = Redactor.redact(message).let { if (it.length > MAX_MESSAGE) it.take(MAX_MESSAGE) + "…" else it }
        synchronized(lock) {
            seq++
            buffer.addLast(LogEntry(seq, System.currentTimeMillis(), category, level, clean))
            while (buffer.size > MAX_ENTRIES) buffer.removeFirst()
            dirty = true
        }
        wake.trySend(Unit)
    }

    fun d(category: LogCategory, message: String) = log(category, LogLevel.DEBUG, message)
    fun i(category: LogCategory, message: String) = log(category, LogLevel.INFO, message)
    fun w(category: LogCategory, message: String) = log(category, LogLevel.WARN, message)
    fun e(category: LogCategory, message: String) = log(category, LogLevel.ERROR, message)

    fun clear(category: LogCategory? = null) {
        synchronized(lock) {
            if (category == null) {
                buffer.clear()
            } else if (category == LogCategory.ERRORS) {
                buffer.removeAll { it.level == LogLevel.ERROR }
            } else {
                buffer.removeAll { it.category == category }
            }
            dirty = true
        }
        publish()
    }

    fun filter(all: List<LogEntry>, category: LogCategory?): List<LogEntry> = when (category) {
        null -> all
        LogCategory.ERRORS -> all.filter { it.level == LogLevel.ERROR }
        else -> all.filter { it.category == category }
    }

    fun format(entry: LogEntry): String =
        "${Fmt.time(entry.time)} ${entry.level.name.padEnd(5)} [${entry.category.label}] ${entry.message}"

    fun exportText(entries: List<LogEntry>): String =
        Redactor.redact(entries.joinToString("\n") { format(it) })
}
