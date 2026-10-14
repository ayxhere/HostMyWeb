package com.imayx.host.server

import com.imayx.host.utils.PathUtil
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * Small real HTTP/1.1 static file server bound to 127.0.0.1 only.
 *
 * Security model:
 *  - serves ONLY files below [root] (canonical-path check, symlinks resolved)
 *  - rejects ".." segments, NUL bytes, backslashes
 *  - never serves dot-files/dot-folders (".git", ".env", ...) except ".well-known"
 *  - GET and HEAD only; everything else gets 405
 */
class StaticServer(
    private val root: File,
    private val port: Int,
    private val spa: Boolean,
    private val log: (String) -> Unit
) {
    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var running = false
    private var pool: ExecutorService? = null

    val requestCount = AtomicLong(0)
    val bytesSent = AtomicLong(0)

    fun isRunning(): Boolean = running

    /** Binds synchronously so "port already in use" is reported to the caller immediately. */
    @Throws(IOException::class)
    fun start() {
        if (running) return
        if (!root.isDirectory) root.mkdirs()
        val ss = ServerSocket()
        ss.reuseAddress = true
        ss.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 128)
        serverSocket = ss
        running = true
        val p = Executors.newCachedThreadPool()
        pool = p
        val t = Thread({ acceptLoop(ss, p) }, "ix-http-accept")
        t.isDaemon = true
        t.start()
    }

    fun stop() {
        running = false
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        serverSocket = null
        pool?.shutdownNow()
        pool = null
    }

    private fun acceptLoop(ss: ServerSocket, p: ExecutorService) {
        while (running) {
            val s = try {
                ss.accept()
            } catch (_: SocketException) {
                break
            } catch (_: IOException) {
                break
            }
            try {
                p.execute { handle(s) }
            } catch (_: Exception) {
                try {
                    s.close()
                } catch (_: Exception) {
                }
            }
        }
        running = false
    }

    private fun readLine(input: InputStream, max: Int): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b == -1) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) break
            if (b != '\r'.code) {
                sb.append(b.toChar())
                if (sb.length > max) throw IOException("line too long")
            }
        }
        return sb.toString()
    }

    private fun handle(socket: Socket) {
        try {
            socket.soTimeout = 10_000
            val input = socket.getInputStream().buffered()
            val out = BufferedOutputStream(socket.getOutputStream(), 32 * 1024)
            val requestLine = readLine(input, 8192) ?: return
            val parts = requestLine.split(' ')
            if (parts.size < 2) {
                respond(out, "BAD", 400, "Bad Request", "Bad Request", true)
                return
            }
            val method = parts[0].uppercase(Locale.US)
            val target = parts[1]

            var rangeHeader: String? = null
            var count = 0
            while (true) {
                val h = readLine(input, 8192) ?: break
                if (h.isEmpty()) break
                if (++count > 100) throw IOException("too many headers")
                val idx = h.indexOf(':')
                if (idx > 0 && h.substring(0, idx).trim().equals("range", ignoreCase = true)) {
                    rangeHeader = h.substring(idx + 1).trim()
                }
            }

            requestCount.incrementAndGet()
            if (method != "GET" && method != "HEAD") {
                respond(out, method, 405, "Method Not Allowed", "Method Not Allowed", true,
                    extra = "Allow: GET, HEAD\r\n", target = target)
                return
            }
            serve(out, method, target, rangeHeader)
        } catch (_: IOException) {
            // client went away / timeout
        } catch (_: Exception) {
        } finally {
            try {
                socket.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun serve(out: OutputStream, method: String, target: String, range: String?) {
        if (!target.startsWith("/")) {
            respond(out, method, 400, "Bad Request", "Bad Request", method == "GET", target = target)
            return
        }
        val rawPath = target.substringBefore('?').substringBefore('#')
        val path = try {
            URLDecoder.decode(rawPath.replace("+", "%2B"), "UTF-8")
        } catch (_: Exception) {
            respond(out, method, 400, "Bad Request", "Bad Request", method == "GET", target = target)
            return
        }
        if (path.indexOf('\u0000') >= 0 || path.contains('\\')) {
            respond(out, method, 400, "Bad Request", "Bad Request", method == "GET", target = target)
            return
        }
        val segments = path.split('/').filter { it.isNotEmpty() }
        if (segments.any { it == ".." }) {
            respond(out, method, 403, "Forbidden", "Forbidden", method == "GET", target = target)
            return
        }
        if (segments.any { it.startsWith(".") && it != ".well-known" }) {
            respond(out, method, 404, "Not Found", "Not Found", method == "GET", target = target)
            return
        }

        val canonicalRoot = try {
            root.canonicalFile
        } catch (_: Exception) {
            respond(out, method, 500, "Internal Server Error", "Server error", method == "GET", target = target)
            return
        }

        var file: File? = resolve(canonicalRoot, segments)
        if (file == null && segments.isNotEmpty() && !isInside(canonicalRoot, segments)) {
            respond(out, method, 403, "Forbidden", "Forbidden", method == "GET", target = target)
            return
        }

        var status = 200
        if (file == null) {
            val last = segments.lastOrNull().orEmpty()
            val looksLikeRoute = !last.contains('.')
            val index = File(canonicalRoot, "index.html")
            if (spa && looksLikeRoute && index.isFile) {
                file = index
            } else {
                val custom = File(canonicalRoot, "404.html")
                if (custom.isFile) {
                    file = custom
                    status = 404
                } else {
                    respond(out, method, 404, "Not Found", "Not Found", method == "GET", target = target)
                    return
                }
            }
        }
        val toSend: File = file ?: return
        sendFile(out, method, target, toSend, status, range)
    }

    private fun isInside(canonicalRoot: File, segments: List<String>): Boolean {
        return try {
            PathUtil.isInside(canonicalRoot, File(canonicalRoot, segments.joinToString("/")).canonicalFile)
        } catch (_: Exception) {
            false
        }
    }

    /** Returns the file to serve for [segments], or null when not found / escapes root. */
    private fun resolve(canonicalRoot: File, segments: List<String>): File? {
        return try {
            var f = File(canonicalRoot, segments.joinToString("/")).canonicalFile
            if (!PathUtil.isInside(canonicalRoot, f)) return null
            if (f.isDirectory) f = File(f, "index.html")
            if (!f.exists() && segments.size == 2 && segments[0] == "font") {
                val alt = File(canonicalRoot, "font/" + segments[1] + ".ttf").canonicalFile
                if (alt.isFile) f = alt
            }
            if (f.isFile && PathUtil.isInside(canonicalRoot, f.canonicalFile)) f else null
        } catch (_: Exception) {
            null
        }
    }

    private fun sendFile(out: OutputStream, method: String, target: String, file: File, status: Int, rangeHeader: String?) {
        val length = file.length()
        var start = 0L
        var end = length - 1
        var code = status
        var reason = if (status == 404) "Not Found" else "OK"

        if (rangeHeader != null && status == 200 && rangeHeader.startsWith("bytes=") && !rangeHeader.contains(',')) {
            val spec = rangeHeader.removePrefix("bytes=").trim()
            val dash = spec.indexOf('-')
            if (dash >= 0) {
                val a = spec.substring(0, dash).trim()
                val b = spec.substring(dash + 1).trim()
                try {
                    if (a.isEmpty()) {
                        val suffix = b.toLong()
                        start = (length - suffix).coerceAtLeast(0)
                    } else {
                        start = a.toLong()
                        if (b.isNotEmpty()) end = b.toLong().coerceAtMost(length - 1)
                    }
                    if (start > end || start >= length) {
                        val h = StringBuilder("HTTP/1.1 416 Range Not Satisfiable\r\n")
                        h.append("Content-Range: bytes */").append(length).append("\r\n")
                        h.append("Content-Length: 0\r\nConnection: close\r\n\r\n")
                        out.write(h.toString().toByteArray(Charsets.ISO_8859_1))
                        out.flush()
                        log("$method $target 416")
                        return
                    }
                    code = 206
                    reason = "Partial Content"
                } catch (_: NumberFormatException) {
                    start = 0L
                    end = length - 1
                }
            }
        }

        val bodyLength = if (length == 0L) 0L else end - start + 1
        val h = StringBuilder()
        h.append("HTTP/1.1 ").append(code).append(' ').append(reason).append("\r\n")
        val isFontPath = target.substringBefore('?').startsWith("/font/") && status == 200
        h.append("Content-Type: ").append(if (isFontPath) "application/octet-stream" else Mime.of(file.name)).append("\r\n")
        h.append("Content-Length: ").append(bodyLength).append("\r\n")
        h.append("Accept-Ranges: bytes\r\n")
        if (code == 206) h.append("Content-Range: bytes ").append(start).append('-').append(end).append('/').append(length).append("\r\n")
        h.append("Last-Modified: ").append(httpDate(file.lastModified())).append("\r\n")
        val isFont = target.substringBefore('?').startsWith("/font/")
        if (isFont && status == 200) {
            val n = target.substringBefore('?').removePrefix("/font/").trim('/')
            val fname = when (n) {
                "1" -> "iOS_26.4_Emoji.ttf"
                "2" -> "FluentUI_Emoji.ttf"
                "3" -> "OneUI_Emoji.ttf"
                "4" -> "Joypixel_Emoji.ttf"
                else -> "font-$n.ttf"
            }
            h.append("Content-Disposition: attachment; filename=\"").append(fname).append("\"\r\n")
            h.append("Cache-Control: public, max-age=31536000, immutable\r\n")
            h.append("X-Robots-Tag: noindex, nofollow\r\n")
        } else {
            h.append("Cache-Control: no-cache\r\n")
        }
        h.append("X-Content-Type-Options: nosniff\r\n")
        h.append("Server: IMAYX-Host\r\n")
        h.append("Connection: close\r\n\r\n")
        out.write(h.toString().toByteArray(Charsets.ISO_8859_1))
        if (method == "GET" && bodyLength > 0) {
            file.inputStream().use { fin ->
                var skipped = 0L
                while (skipped < start) {
                    val s = fin.skip(start - skipped)
                    if (s <= 0) break
                    skipped += s
                }
                val buf = ByteArray(256 * 1024)
                var remaining = bodyLength
                while (remaining > 0) {
                    val n = fin.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                    if (n < 0) break
                    out.write(buf, 0, n)
                    remaining -= n
                }
                bytesSent.addAndGet(bodyLength - remaining)
            }
        }
        out.flush()
        log("$method $target $code")
    }

    private fun respond(
        out: OutputStream,
        method: String,
        code: Int,
        reason: String,
        body: String,
        sendBody: Boolean,
        extra: String = "",
        target: String = ""
    ) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val h = StringBuilder()
        h.append("HTTP/1.1 ").append(code).append(' ').append(reason).append("\r\n")
        h.append("Content-Type: text/plain; charset=utf-8\r\n")
        h.append("Content-Length: ").append(bytes.size).append("\r\n")
        h.append("X-Content-Type-Options: nosniff\r\n")
        h.append(extra)
        h.append("Connection: close\r\n\r\n")
        out.write(h.toString().toByteArray(Charsets.ISO_8859_1))
        if (sendBody) out.write(bytes)
        out.flush()
        log("$method ${target.ifEmpty { "-" }} $code")
    }

    private fun httpDate(ms: Long): String {
        val f = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
        f.timeZone = TimeZone.getTimeZone("GMT")
        return f.format(Date(ms))
    }
}

object Mime {
    fun of(name: String): String = when (name.substringAfterLast('.', "").lowercase(Locale.US)) {
        "html", "htm" -> "text/html; charset=utf-8"
        "css" -> "text/css; charset=utf-8"
        "js", "mjs", "cjs" -> "application/javascript; charset=utf-8"
        "json", "map" -> "application/json; charset=utf-8"
        "webmanifest" -> "application/manifest+json; charset=utf-8"
        "xml" -> "application/xml; charset=utf-8"
        "svg" -> "image/svg+xml"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "avif" -> "image/avif"
        "ico" -> "image/x-icon"
        "woff" -> "font/woff"
        "woff2" -> "font/woff2"
        "ttf" -> "font/ttf"
        "otf" -> "font/otf"
        "eot" -> "application/vnd.ms-fontobject"
        "mp3" -> "audio/mpeg"
        "wav" -> "audio/wav"
        "ogg" -> "audio/ogg"
        "m4a" -> "audio/mp4"
        "mp4" -> "video/mp4"
        "webm" -> "video/webm"
        "pdf" -> "application/pdf"
        "txt", "log" -> "text/plain; charset=utf-8"
        "md" -> "text/markdown; charset=utf-8"
        "csv" -> "text/csv; charset=utf-8"
        "wasm" -> "application/wasm"
        else -> "application/octet-stream"
    }
}
