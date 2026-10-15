package com.imayx.host.projects

import com.imayx.host.utils.PathUtil
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

data class ZipInfo(
    val compressedBytes: Long,
    val entryCount: Int,
    val fileCount: Int,
    val uncompressedBytes: Long,
    val hasIndexHtml: Boolean,
    /** Single top-level folder that will be stripped on import (or empty). */
    val stripPrefix: String,
    /** Extensions outside the supported web/media list (kept as-is, served as octet-stream). */
    val otherTypes: Set<String>,
    val unsafeEntries: List<String>,
    val error: String?,
    /** Normalized file entry names (junk skipped, capped) — used for server detection. */
    val entryNames: List<String> = emptyList()
) {
    val isSafe: Boolean get() = error == null && unsafeEntries.isEmpty()
}

object ZipTools {
    const val MAX_ENTRIES = 100_000
    const val MAX_TOTAL_BYTES = 2L * 1024 * 1024 * 1024
    private const val MAX_RATIO = 2000L

    private val KNOWN_EXT = setOf(
        "html", "htm", "css", "js", "mjs", "json", "xml", "svg", "png", "jpg", "jpeg", "webp", "gif", "ico",
        "woff", "woff2", "ttf", "otf", "mp3", "wav", "mp4", "webm", "pdf", "txt", "md",
        "map", "webmanifest", "wasm", "avif", "csv", "ogg", "m4a", "eot", "ts", "tsx", "jsx", "yml", "yaml",
        "lock", "env", "ejs", "cjs", "toml", "ini", "log", "license", "gitignore"
    )

    /** Normalise a ZIP entry name; returns null if it is unsafe (absolute, traversal, drive letter, NUL). */
    fun normalizeEntryName(raw: String): String? {
        if (raw.indexOf('\u0000') >= 0) return null
        val n = raw.replace('\\', '/')
        if (n.startsWith("/")) return null
        if (Regex("^[A-Za-z]:").containsMatchIn(n)) return null
        if (n.split('/').any { it == ".." }) return null
        return n
    }

    private fun isJunk(name: String): Boolean =
        name.startsWith("__MACOSX/") || name.endsWith(".DS_Store") || name.endsWith("Thumbs.db")

    fun inspect(zip: File): ZipInfo {
        val unsafe = ArrayList<String>()
        val others = HashSet<String>()
        val names = ArrayList<String>()
        var entries = 0
        var files = 0
        var total = 0L
        var hasIndex = false
        val topLevels = HashSet<String>()
        var rootFiles = 0
        try {
            ZipFile(zip).use { zf ->
                val en = zf.entries()
                while (en.hasMoreElements()) {
                    val e = en.nextElement()
                    entries++
                    if (entries > MAX_ENTRIES) {
                        return ZipInfo(zip.length(), entries, files, total, hasIndex, "", others, unsafe,
                            "ZIP has too many entries (limit $MAX_ENTRIES)")
                    }
                    val name = normalizeEntryName(e.name)
                    if (name == null) {
                        unsafe.add(e.name.take(120))
                        continue
                    }
                    if (isJunk(name)) continue
                    if (e.isDirectory) continue
                    files++
                    if (names.size < 3000) names.add(name)
                    val size = if (e.size >= 0) e.size else 0L
                    total += size
                    if (total > MAX_TOTAL_BYTES) {
                        return ZipInfo(zip.length(), entries, files, total, hasIndex, "", others, unsafe,
                            "ZIP expands to more than ${MAX_TOTAL_BYTES / (1024 * 1024)} MB")
                    }
                    if (e.compressedSize > 0 && size / e.compressedSize > MAX_RATIO) {
                        return ZipInfo(zip.length(), entries, files, total, hasIndex, "", others, unsafe,
                            "Suspicious compression ratio (possible zip bomb)")
                    }
                    val top = name.substringBefore('/', "")
                    if (name.contains('/')) topLevels.add(top) else rootFiles++
                    if (name == "index.html") hasIndex = true
                    val ext = PathUtil.extension(name)
                    if (ext.isNotEmpty() && ext !in KNOWN_EXT) others.add(ext)
                }
            }
        } catch (ex: Exception) {
            return ZipInfo(zip.length(), entries, files, total, false, "", others, unsafe,
                "Not a valid ZIP file: ${ex.message ?: ex.javaClass.simpleName}")
        }
        var strip = ""
        if (rootFiles == 0 && topLevels.size == 1) {
            strip = topLevels.first() + "/"
            // If the single folder holds the site's index.html, remember that for the preview.
            if (!hasIndex) {
                try {
                    ZipFile(zip).use { zf -> hasIndex = zf.getEntry(strip + "index.html") != null }
                } catch (_: Exception) {
                }
            }
        }
        return ZipInfo(zip.length(), entries, files, total, hasIndex, strip, others, unsafe, null, names)
    }

    /**
     * Extract [zip] into [dest]. Every entry is validated (Zip Slip / traversal / absolute paths) and the
     * real number of bytes written is capped, regardless of what the ZIP header claims.
     */
    fun extract(zip: File, dest: File, stripPrefix: String, onProgress: ((done: Int, total: Int) -> Unit)? = null): Int {
        dest.mkdirs()
        var written = 0L
        var count = 0
        ZipFile(zip).use { zf ->
            val total = zf.size()
            var seen = 0
            val en = zf.entries()
            while (en.hasMoreElements()) {
                val e = en.nextElement()
                seen++
                var name = normalizeEntryName(e.name)
                    ?: throw SecurityException("Unsafe path in ZIP: ${e.name.take(120)}")
                if (isJunk(name)) continue
                if (stripPrefix.isNotEmpty()) {
                    if (!name.startsWith(stripPrefix)) continue
                    name = name.removePrefix(stripPrefix)
                }
                if (name.isEmpty()) continue
                val out = PathUtil.resolveInside(dest, name)
                    ?: throw SecurityException("Unsafe path in ZIP: ${e.name.take(120)}")
                if (e.isDirectory) {
                    out.mkdirs()
                    continue
                }
                out.parentFile?.mkdirs()
                zf.getInputStream(e).use { input ->
                    FileOutputStream(out).use { output ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            written += n
                            if (written > MAX_TOTAL_BYTES) {
                                throw SecurityException("ZIP expands beyond the safety limit")
                            }
                            output.write(buf, 0, n)
                        }
                    }
                }
                count++
                if (seen % 25 == 0) onProgress?.invoke(seen, total)
            }
            onProgress?.invoke(total, total)
        }
        return count
    }

    fun zipDirectory(dir: File, out: OutputStream, exclude: (File) -> Boolean = { false }): Int {
        var count = 0
        val canonicalDir = dir.canonicalFile
        ZipOutputStream(out.buffered()).use { zos ->
            val stack = ArrayDeque<File>()
            stack.add(canonicalDir)
            while (stack.isNotEmpty()) {
                val d = stack.removeLast()
                val children = d.listFiles()?.sortedBy { it.name } ?: continue
                for (c in children) {
                    if (exclude(c)) continue
                    // Never follow symlinks out of the directory.
                    val canonical = try { c.canonicalFile } catch (_: Exception) { continue }
                    if (!PathUtil.isInside(canonicalDir, canonical)) continue
                    val rel = PathUtil.relativeTo(canonicalDir, canonical)
                    if (c.isDirectory) {
                        zos.putNextEntry(ZipEntry("$rel/"))
                        zos.closeEntry()
                        stack.add(canonical)
                    } else {
                        val entry = ZipEntry(rel)
                        entry.time = c.lastModified()
                        zos.putNextEntry(entry)
                        c.inputStream().use { it.copyTo(zos, 64 * 1024) }
                        zos.closeEntry()
                        count++
                    }
                }
            }
        }
        return count
    }

    fun copyToFile(input: InputStream, dest: File, maxBytes: Long = MAX_TOTAL_BYTES): Long {
        var total = 0L
        FileOutputStream(dest).use { out ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (total > maxBytes) throw SecurityException("File is larger than the allowed limit")
                out.write(buf, 0, n)
            }
        }
        return total
    }
}
