package com.imayx.host.utils

import java.io.File

/**
 * Central place for all path-safety logic (Zip Slip, traversal, absolute paths).
 * Every file operation in the app that takes user/ZIP/HTTP input goes through here.
 */
object PathUtil {
    private val PROJECT_NAME = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,47}$")

    fun isValidProjectName(name: String): Boolean =
        PROJECT_NAME.matches(name) && !name.endsWith(".")

    fun sanitizeProjectName(raw: String): String {
        var s = raw.trim().replace(Regex("[^A-Za-z0-9._-]"), "_")
        s = s.trimStart('.', '_', '-')
        if (s.length > 48) s = s.substring(0, 48)
        s = s.trimEnd('.')
        return s.ifBlank { "site" }
    }

    /** A single path component (file or folder name) must not contain separators or be . / .. */
    fun isValidEntryName(name: String): Boolean {
        if (name.isBlank() || name == "." || name == "..") return false
        if (name.length > 200) return false
        return name.none { it == '/' || it == '\\' || it == '\u0000' }
    }

    /**
     * Resolve [relative] under [root]. Returns null if the result would escape root,
     * if the path is absolute, contains NUL, drive letters or ".." segments.
     */
    fun resolveInside(root: File, relative: String): File? {
        if (relative.indexOf('\u0000') >= 0) return null
        val normalized = relative.replace('\\', '/')
        if (normalized.startsWith("/")) return null
        if (Regex("^[A-Za-z]:").containsMatchIn(normalized)) return null
        val segments = normalized.split('/')
        if (segments.any { it == ".." }) return null
        return try {
            val canonicalRoot = root.canonicalFile
            val candidate = File(canonicalRoot, normalized).canonicalFile
            if (isInside(canonicalRoot, candidate)) candidate else null
        } catch (_: Exception) {
            null
        }
    }

    fun isInside(canonicalRoot: File, candidate: File): Boolean {
        val r = canonicalRoot.path
        val c = candidate.path
        return c == r || c.startsWith(r + File.separator)
    }

    fun relativeTo(root: File, file: File): String {
        return try {
            val r = root.canonicalFile.path
            val c = file.canonicalFile.path
            if (c == r) "" else c.removePrefix(r + File.separator).replace(File.separatorChar, '/')
        } catch (_: Exception) {
            file.name
        }
    }

    fun extension(name: String): String = name.substringAfterLast('.', "").lowercase()

    fun folderSize(dir: File): Long {
        var total = 0L
        val stack = ArrayDeque<File>()
        stack.add(dir)
        while (stack.isNotEmpty()) {
            val d = stack.removeLast()
            val children = d.listFiles() ?: continue
            for (c in children) {
                if (c.isDirectory) stack.add(c) else total += c.length()
            }
        }
        return total
    }

    fun fileCount(dir: File): Int {
        var n = 0
        val stack = ArrayDeque<File>()
        stack.add(dir)
        while (stack.isNotEmpty()) {
            val d = stack.removeLast()
            val children = d.listFiles() ?: continue
            for (c in children) {
                if (c.isDirectory) stack.add(c) else n++
            }
        }
        return n
    }

    fun lastModified(dir: File): Long {
        var latest = dir.lastModified()
        val stack = ArrayDeque<File>()
        stack.add(dir)
        while (stack.isNotEmpty()) {
            val d = stack.removeLast()
            val children = d.listFiles() ?: continue
            for (c in children) {
                if (c.lastModified() > latest) latest = c.lastModified()
                if (c.isDirectory) stack.add(c)
            }
        }
        return latest
    }
}
