package com.imayx.host.utils

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Fmt {
    // SimpleDateFormat construction parses the pattern and allocates a Calendar —
    // far too expensive to do per call when list rows format dates/times on every
    // recomposition (Logs, Files). Formatters are not thread-safe, so cache one
    // per thread per pattern instead of constructing one per call.
    private val dateFmt = ThreadLocal.withInitial { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US) }
    private val timeFmt = ThreadLocal.withInitial { SimpleDateFormat("HH:mm:ss.SSS", Locale.US) }
    private val stampFmt = ThreadLocal.withInitial { SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US) }

    fun bytes(value: Long): String {
        if (value < 0) return "Unavailable"
        if (value < 1024) return "$value B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var v = value.toDouble()
        var i = -1
        while (v >= 1024 && i < units.size - 1) {
            v /= 1024
            i++
        }
        return String.format(Locale.US, "%.1f %s", v, units[i])
    }

    fun duration(ms: Long): String {
        if (ms <= 0) return "0s"
        val s = ms / 1000
        val d = s / 86400
        val h = (s % 86400) / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return when {
            d > 0 -> "${d}d ${h}h ${m}m"
            h > 0 -> "${h}h ${m}m ${sec}s"
            m > 0 -> "${m}m ${sec}s"
            else -> "${sec}s"
        }
    }

    fun date(ms: Long): String {
        if (ms <= 0) return "-"
        return dateFmt.get().format(Date(ms))
    }

    fun time(ms: Long): String =
        timeFmt.get().format(Date(ms))

    fun fileStamp(ms: Long): String =
        stampFmt.get().format(Date(ms))

    fun percent(value: Float?): String =
        if (value == null) "Unavailable" else String.format(Locale.US, "%.0f%%", value)
}
