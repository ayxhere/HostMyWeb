package com.imayx.host.utils

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

object Downloader {
    private const val MAX_REDIRECTS = 6

    /** HTTPS-only download with manual redirect handling and a size cap. */
    @Throws(IOException::class)
    fun download(
        url: String,
        dest: File,
        maxBytes: Long = 700L * 1024 * 1024,
        onProgress: ((percent: Int) -> Unit)? = null
    ) {
        var current = url
        var redirects = 0
        while (true) {
            if (!current.startsWith("https://")) throw IOException("Only https:// downloads are allowed")
            val conn = URL(current).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 20_000
            conn.readTimeout = 60_000
            conn.setRequestProperty("User-Agent", "IMAYX-Host")
            conn.setRequestProperty("Accept", "application/octet-stream, */*")
            try {
                val code = conn.responseCode
                if (code in 300..399) {
                    val loc = conn.getHeaderField("Location") ?: throw IOException("Redirect without Location")
                    current = URL(URL(current), loc).toString()
                    if (++redirects > MAX_REDIRECTS) throw IOException("Too many redirects")
                    continue
                }
                if (code != 200) throw IOException("HTTP $code while downloading")
                val total = conn.contentLengthLong
                var done = 0L
                var lastPct = -1
                conn.inputStream.use { input ->
                    FileOutputStream(dest).use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            done += n
                            if (done > maxBytes) throw IOException("Download exceeds size limit")
                            out.write(buf, 0, n)
                            if (total > 0 && onProgress != null) {
                                val pct = (done * 100 / total).toInt()
                                if (pct != lastPct) {
                                    lastPct = pct
                                    onProgress(pct)
                                }
                            }
                        }
                    }
                }
                return
            } finally {
                conn.disconnect()
            }
        }
    }

    fun getText(url: String, headers: Map<String, String> = emptyMap()): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.setRequestProperty("User-Agent", "IMAYX-Host")
        for ((k, v) in headers) conn.setRequestProperty(k, v)
        try {
            val code = conn.responseCode
            if (code != 200) throw IOException("HTTP $code")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    fun sha256(file: File): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** First bytes of a file, used to sniff ELF / ZIP / GZIP. */
    fun magic(file: File): ByteArray {
        val b = ByteArray(4)
        file.inputStream().use { it.read(b) }
        return b
    }
}

object TarTools {
    private fun readFully(input: InputStream, buf: ByteArray): Boolean {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n < 0) return false
            off += n
        }
        return true
    }

    private fun skipFully(input: InputStream, count: Long) {
        var left = count
        val buf = ByteArray(8192)
        while (left > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n < 0) return
            left -= n
        }
    }

    private fun cString(b: ByteArray, off: Int, len: Int): String {
        var end = off
        while (end < off + len && b[end].toInt() != 0) end++
        return String(b, off, end - off, Charsets.UTF_8)
    }

    private fun octal(b: ByteArray, off: Int, len: Int): Long {
        val s = cString(b, off, len).trim()
        return if (s.isEmpty()) 0L else s.toLongOrNull(8) ?: 0L
    }

    /** Safe .tar.gz extraction (no symlinks, no path escape, size cap). */
    fun extractTarGz(input: InputStream, dest: File, maxBytes: Long = 1_500L * 1024 * 1024): Int {
        dest.mkdirs()
        val tin = GZIPInputStream(input.buffered())
        val header = ByteArray(512)
        var longName: String? = null
        var count = 0
        var written = 0L
        while (true) {
            if (!readFully(tin, header)) break
            if (header.all { it.toInt() == 0 }) break
            var name = cString(header, 0, 100)
            val magic = cString(header, 257, 5)
            val prefix = cString(header, 345, 155)
            if (magic == "ustar" && prefix.isNotEmpty()) name = "$prefix/$name"
            val size = octal(header, 124, 12)
            val type = header[156].toInt().toChar()
            val padded = ((size + 511) / 512) * 512
            if (type == 'L') {
                val data = ByteArray(size.toInt().coerceIn(0, 4096))
                readFully(tin, data)
                skipFully(tin, padded - data.size)
                longName = cString(data, 0, data.size)
                continue
            }
            if (type == 'x' || type == 'g') {
                skipFully(tin, padded)
                continue
            }
            var effective = (longName ?: name)
            longName = null
            while (effective.startsWith("./")) effective = effective.removePrefix("./")
            if (effective.isEmpty()) {
                skipFully(tin, padded)
                continue
            }
            val target = PathUtil.resolveInside(dest, effective.trimEnd('/'))
                ?: throw SecurityException("Unsafe path in archive: ${effective.take(120)}")
            if (type == '5') {
                target.mkdirs()
            } else if (type == '0' || type == '\u0000') {
                target.parentFile?.mkdirs()
                written += size
                if (written > maxBytes) throw SecurityException("Archive expands beyond the safety limit")
                FileOutputStream(target).use { out ->
                    var left = size
                    val buf = ByteArray(64 * 1024)
                    while (left > 0) {
                        val n = tin.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                        if (n < 0) throw IOException("Truncated archive")
                        out.write(buf, 0, n)
                        left -= n
                    }
                }
                skipFully(tin, padded - size)
                count++
            } else {
                skipFully(tin, padded)
            }
        }
        return count
    }
}
