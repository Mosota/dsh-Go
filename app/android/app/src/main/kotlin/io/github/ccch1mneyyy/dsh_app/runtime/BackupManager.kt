package io.github.ccch1mneyyy.dsh_app.runtime

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 备份 / 恢复: the user's DSH home (`rootfs/root/.dsh`) — sessions, settings, skills and
 * installed plugins — packed into one zip, without the API key ([.env]) or anything else
 * secret. Backup writes into app-private storage (no permission needed); the Dart side
 * shares it out. Restore takes a zip path (from the file picker) and overlays it back.
 */
object BackupManager {
    /** 备份时排除的敏感文件（API key、密钥）。 */
    private val EXCLUDE = setOf(".env", ".env.local", "secrets.json", "credentials.json")

    private fun dshHome(context: Context): File {
        val rootfs = File(context.filesDir, "runtime/rootfs")
        return File(rootfs, "root/.dsh")
    }

    /** 打包 /root/.dsh（排除敏感文件）→ files/backup/dsh-go-backup-<ts>.zip，返回绝对路径。 */
    fun backup(context: Context): String? {
        val home = dshHome(context)
        if (!home.isDirectory) return null
        val outDir = File(context.filesDir, "backup").apply { mkdirs() }
        val out = File(outDir, "dsh-go-backup-${System.currentTimeMillis()}.zip")
        return try {
            ZipOutputStream(FileOutputStream(out)).use { zip -> zipDir(zip, home, "") }
            out.absolutePath
        } catch (_: Throwable) {
            runCatching { out.delete() }
            null
        }
    }

    /** 从 zip 覆盖恢复到 /root/.dsh；zip 里没有的文件保留不动。防路径穿越。 */
    fun restore(context: Context, zipPath: String): Boolean {
        val home = dshHome(context)
        val zipFile = File(zipPath)
        if (!zipFile.isFile) return false
        return try {
            ZipInputStream(FileInputStream(zipFile)).use { zin ->
                var entry = zin.nextEntry
                while (entry != null) {
                    val name = entry.name
                    if (!name.contains("..") && !name.startsWith("/")) {
                        val target = File(home, name)
                        if (entry.isDirectory) {
                            target.mkdirs()
                        } else {
                            target.parentFile?.mkdirs()
                            FileOutputStream(target).use { zin.copyTo(it) }
                        }
                    }
                    zin.closeEntry()
                    entry = zin.nextEntry
                }
            }
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun zipDir(zip: ZipOutputStream, dir: File, prefix: String) {
        dir.listFiles()?.sortedBy { it.name }?.forEach { f ->
            if (f.name in EXCLUDE) return@forEach
            val entry = prefix + f.name + if (f.isDirectory) "/" else ""
            zip.putNextEntry(ZipEntry(entry))
            if (f.isFile) FileInputStream(f).use { it.copyTo(zip) }
            zip.closeEntry()
            if (f.isDirectory) zipDir(zip, f, entry)
        }
    }
}
