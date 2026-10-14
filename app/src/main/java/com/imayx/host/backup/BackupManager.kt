package com.imayx.host.backup

import android.content.Context
import com.imayx.host.data.LogBus
import com.imayx.host.data.LogCategory
import com.imayx.host.projects.ProjectException
import com.imayx.host.projects.ProjectManager
import com.imayx.host.projects.ZipInfo
import com.imayx.host.projects.ZipTools
import com.imayx.host.utils.Fmt
import com.imayx.host.utils.PathUtil
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

data class BackupInfo(
    val file: File,
    val project: String,
    val createdAt: Long,
    val sizeBytes: Long
)

/**
 * Backups are plain ZIPs of a project directory. They never contain secrets: tunnel/API tokens and Node
 * environment variables live in the Android Keystore-backed SecretStore, not in project folders.
 */
class BackupManager(context: Context, private val projects: ProjectManager) {

    private val dir = File(context.filesDir, "backups").also { it.mkdirs() }
    private val tmpDir = File(context.filesDir, "restore-tmp")

    fun list(): List<BackupInfo> {
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".zip") }.orEmpty()
        return files.map { f ->
            val project = f.name.substringBefore('@')
            BackupInfo(f, project, f.lastModified(), f.length())
        }.sortedByDescending { it.createdAt }
    }

    fun create(project: String): BackupInfo {
        val src = projects.store.projectDir(project)
        if (!src.isDirectory) throw ProjectException("Project not found")
        val now = System.currentTimeMillis()
        val target = File(dir, "$project@${Fmt.fileStamp(now)}.zip")
        val part = File(dir, target.name + ".part")
        try {
            FileOutputStream(part).use { ZipTools.zipDirectory(src, it) }
            if (!part.renameTo(target)) throw ProjectException("Could not finalise backup")
        } catch (e: Exception) {
            part.delete()
            throw if (e is ProjectException) e else ProjectException(e.message ?: "Backup failed")
        }
        LogBus.i(LogCategory.SYSTEM, "Backup created: ${target.name} (${Fmt.bytes(target.length())})")
        return BackupInfo(target, project, now, target.length())
    }

    fun validate(backup: File): ZipInfo = ZipTools.inspect(backup)

    /**
     * Restore into [targetName] (defaults to the original project). The ZIP is validated first, extracted to a
     * staging folder, and only then swapped in, so a bad archive can never destroy the existing project.
     */
    fun restore(backup: File, targetName: String, replaceExisting: Boolean): String {
        if (!PathUtil.isInside(dir.canonicalFile, backup.canonicalFile)) {
            throw ProjectException("Backup is outside the backup directory")
        }
        if (!PathUtil.isValidProjectName(targetName)) throw ProjectException("Invalid project name")
        val info = validate(backup)
        if (!info.isSafe) throw ProjectException(info.error ?: "Backup contains unsafe paths and was rejected")
        val dest = projects.store.projectDir(targetName)
        if (dest.exists() && !replaceExisting) {
            throw ProjectException("Project \"$targetName\" already exists")
        }
        tmpDir.deleteRecursively()
        val staging = File(tmpDir, targetName)
        staging.mkdirs()
        try {
            ZipTools.extract(backup, staging, "")
            if (dest.exists()) dest.deleteRecursively()
            dest.parentFile?.mkdirs()
            if (!staging.renameTo(dest)) {
                staging.copyRecursively(dest, overwrite = true)
            }
        } catch (e: Exception) {
            throw if (e is ProjectException) e else ProjectException(e.message ?: "Restore failed")
        } finally {
            tmpDir.deleteRecursively()
        }
        if (!projects.store.hasConfig(targetName)) {
            projects.save(com.imayx.host.data.ProjectConfig(name = targetName))
        }
        LogBus.i(LogCategory.SYSTEM, "Backup restored into project $targetName")
        return targetName
    }

    fun export(backup: File, out: OutputStream) {
        if (!PathUtil.isInside(dir.canonicalFile, backup.canonicalFile)) {
            throw ProjectException("Backup is outside the backup directory")
        }
        backup.inputStream().use { it.copyTo(out, 64 * 1024) }
    }

    fun delete(backup: File) {
        if (PathUtil.isInside(dir.canonicalFile, backup.canonicalFile)) backup.delete()
    }
}
