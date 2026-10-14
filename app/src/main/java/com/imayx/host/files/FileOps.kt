package com.imayx.host.files

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.imayx.host.projects.ProjectException
import com.imayx.host.projects.ZipTools
import com.imayx.host.utils.PathUtil
import java.io.File
import java.io.OutputStream

data class FileItem(
    val name: String,
    val relPath: String,
    val isDir: Boolean,
    val size: Long,
    val modified: Long
)

enum class SortKey { NAME, SIZE, DATE, TYPE }

/**
 * Sandboxed file operations for one project. Every path goes through [resolve], which rejects traversal and
 * anything that canonicalises outside the project root (including symlinks).
 */
class FileOps(root: File) {
    private val canonicalRoot: File = root.canonicalFile

    val rootDir: File get() = canonicalRoot

    fun resolve(rel: String): File =
        PathUtil.resolveInside(canonicalRoot, rel.trim('/'))
            ?: throw ProjectException("Path is outside the project folder")

    private fun relOf(f: File): String = PathUtil.relativeTo(canonicalRoot, f)

    private fun toItem(f: File) = FileItem(f.name, relOf(f), f.isDirectory, if (f.isDirectory) 0L else f.length(), f.lastModified())

    fun list(rel: String, sort: SortKey, ascending: Boolean): List<FileItem> {
        val dir = resolve(rel)
        if (!dir.isDirectory) return emptyList()
        return sortItems(dir.listFiles().orEmpty().map { toItem(it) }, sort, ascending)
    }

    fun search(rel: String, query: String, limit: Int = 300): List<FileItem> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        val out = ArrayList<FileItem>()
        val stack = ArrayDeque<File>()
        stack.add(resolve(rel))
        while (stack.isNotEmpty() && out.size < limit) {
            val d = stack.removeLast()
            for (c in d.listFiles().orEmpty()) {
                if (c.name.lowercase().contains(q)) out.add(toItem(c))
                if (c.isDirectory && out.size < limit) stack.add(c)
            }
        }
        return out.sortedBy { it.relPath.lowercase() }
    }

    private fun sortItems(items: List<FileItem>, sort: SortKey, asc: Boolean): List<FileItem> {
        val cmp: Comparator<FileItem> = when (sort) {
            SortKey.NAME -> compareBy<FileItem> { it.name.lowercase() }
            SortKey.SIZE -> compareBy<FileItem> { it.size }
            SortKey.DATE -> compareBy<FileItem> { it.modified }
            SortKey.TYPE -> compareBy<FileItem>({ PathUtil.extension(it.name) }, { it.name.lowercase() })
        }
        val sorted = items.sortedWith(if (asc) cmp else cmp.reversed())
        // Folders always first.
        return sorted.filter { it.isDir } + sorted.filter { !it.isDir }
    }

    private fun checkName(name: String) {
        if (!PathUtil.isValidEntryName(name)) throw ProjectException("Invalid name")
    }

    fun createFolder(parentRel: String, name: String): FileItem {
        checkName(name)
        val f = File(resolve(parentRel), name)
        if (f.exists()) throw ProjectException("\"$name\" already exists")
        if (!f.mkdirs()) throw ProjectException("Could not create folder")
        return toItem(f)
    }

    fun createFile(parentRel: String, name: String): FileItem {
        checkName(name)
        val f = File(resolve(parentRel), name)
        if (f.exists()) throw ProjectException("\"$name\" already exists")
        if (!f.createNewFile()) throw ProjectException("Could not create file")
        return toItem(f)
    }

    fun rename(rel: String, newName: String): FileItem {
        checkName(newName)
        val src = resolve(rel)
        if (src == canonicalRoot) throw ProjectException("Cannot rename the project root")
        val dst = File(src.parentFile, newName)
        if (dst.exists()) throw ProjectException("\"$newName\" already exists")
        if (!src.renameTo(dst)) throw ProjectException("Rename failed")
        return toItem(dst)
    }

    fun delete(rel: String) {
        val f = resolve(rel)
        if (f == canonicalRoot) throw ProjectException("Cannot delete the project root")
        val ok = if (f.isDirectory) f.deleteRecursively() else f.delete()
        if (!ok) throw ProjectException("Delete failed")
    }

    private fun uniqueTarget(dir: File, name: String, suffix: String): File {
        var candidate = File(dir, name)
        if (!candidate.exists()) return candidate
        val base = if (name.contains('.') && !name.startsWith(".")) name.substringBeforeLast('.') else name
        val ext = if (name.contains('.') && !name.startsWith(".")) "." + name.substringAfterLast('.') else ""
        var n = 1
        while (candidate.exists()) {
            val tag = if (n == 1) suffix else "$suffix $n"
            candidate = File(dir, "$base ($tag)$ext")
            n++
        }
        return candidate
    }

    fun copy(rel: String, destDirRel: String): FileItem {
        val src = resolve(rel)
        val destDir = resolve(destDirRel)
        if (!destDir.isDirectory) throw ProjectException("Destination is not a folder")
        if (src.isDirectory && PathUtil.isInside(src, destDir)) throw ProjectException("Cannot copy a folder into itself")
        val dst = uniqueTarget(destDir, src.name, "copy")
        val ok = if (src.isDirectory) src.copyRecursively(dst, overwrite = false) else {
            src.copyTo(dst, overwrite = false)
            true
        }
        if (!ok) throw ProjectException("Copy failed")
        return toItem(dst)
    }

    fun duplicate(rel: String): FileItem {
        val src = resolve(rel)
        if (src == canonicalRoot) throw ProjectException("Cannot duplicate the project root")
        return copy(rel, relOf(src.parentFile ?: canonicalRoot))
    }

    fun move(rel: String, destDirRel: String): FileItem {
        val src = resolve(rel)
        if (src == canonicalRoot) throw ProjectException("Cannot move the project root")
        val destDir = resolve(destDirRel)
        if (!destDir.isDirectory) throw ProjectException("Destination is not a folder")
        if (src.isDirectory && PathUtil.isInside(src, destDir)) throw ProjectException("Cannot move a folder into itself")
        val dst = File(destDir, src.name)
        if (dst.exists()) throw ProjectException("\"${src.name}\" already exists in the destination")
        if (!src.renameTo(dst)) {
            val ok = if (src.isDirectory) src.copyRecursively(dst, overwrite = false) else {
                src.copyTo(dst, overwrite = false)
                true
            }
            if (!ok) throw ProjectException("Move failed")
            if (src.isDirectory) src.deleteRecursively() else src.delete()
        }
        return toItem(dst)
    }

    fun writeText(rel: String, text: String) {
        val f = resolve(rel)
        if (f.isDirectory) throw ProjectException("Is a folder")
        f.parentFile?.mkdirs()
        f.writeText(text)
    }

    fun readText(rel: String, maxBytes: Long = 2L * 1024 * 1024): String {
        val f = resolve(rel)
        if (!f.isFile) throw ProjectException("Not a file")
        if (f.length() > maxBytes) throw ProjectException("File is larger than ${maxBytes / (1024 * 1024)} MB; open it with another tool")
        return f.readText()
    }

    /** Copies a file (or zips a folder) to [out]; used by "Download" through the system file picker. */
    fun exportTo(rel: String, out: OutputStream) {
        val f = resolve(rel)
        if (f.isDirectory) ZipTools.zipDirectory(f, out) else f.inputStream().use { it.copyTo(out, 64 * 1024) }
    }

    /** Builds a content:// Uri via FileProvider for sharing; folders are zipped into the cache first. */
    fun shareIntent(context: Context, rel: String): Intent {
        val f = resolve(rel)
        val file: File
        val mime: String
        if (f.isDirectory) {
            val dir = File(context.cacheDir, "share").also { it.mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            file = File(dir, f.name + ".zip")
            file.outputStream().use { ZipTools.zipDirectory(f, it) }
            mime = "application/zip"
        } else {
            file = f
            mime = com.imayx.host.server.Mime.of(f.name).substringBefore(';')
        }
        val uri: Uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
        return Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
