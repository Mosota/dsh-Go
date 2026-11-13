package io.github.ccch1mneyyy.dsh_app.runtime

import android.content.Context
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.SecureRandom
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * 备份 / 恢复: the user's DSH home (`rootfs/root/.dsh`) — sessions, settings, skills and
 * installed plugins — packed into one zip.
 *
 * 敏感文件（API key 所在的 .env、settings.json 等）不再丢弃，而是**加密后**一起带走：
 * 口令由用户在备份页填写，PBKDF2 派生密钥、AES-GCM 加密，产物是 zip 里的 `secrets.enc`。
 * 没有口令时（旧行为）就不带敏感文件。恢复时若遇到 `secrets.enc`，必须给同一个口令。
 */
object BackupManager {
    /** 备份时**加密**打包的文件名（API key、密钥、凭据）。 */
    private val SECRET_NAMES = setOf(
        ".env", ".env.local", "secrets.json", "credentials.json",
        "auth.json", "settings.json",
    )

    private fun isSecret(name: String): Boolean =
        name in SECRET_NAMES || name.endsWith(".key") || name.endsWith(".pem") || name.endsWith(".p12")

    private const val SECRETS_ENTRY = "secrets.enc"
    private const val MAGIC = "DSHBAK1"
    private const val ITERATIONS = 120_000
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128

    private fun dshHome(context: Context): File {
        val rootfs = File(context.filesDir, "runtime/rootfs")
        return File(rootfs, "root/.dsh")
    }

    /**
     * 打包 /root/.dsh → files/backup/dsh-go-backup-<ts>.zip，返回绝对路径。
     * [password] 非空时敏感文件加密进 `secrets.enc`；为空时不含敏感文件（旧行为）。
     */
    fun backup(context: Context, password: String?): String? {
        val home = dshHome(context)
        if (!home.isDirectory) return null
        val outDir = File(context.filesDir, "backup").apply { mkdirs() }
        val out = File(outDir, "dsh-go-backup-${System.currentTimeMillis()}.zip")
        return try {
            val secrets = ByteArrayOutputStream()
            ZipOutputStream(secrets).use { inner -> collectSecrets(inner, home, "") }
            val secretBytes = secrets.toByteArray()
            ZipOutputStream(FileOutputStream(out)).use { zip ->
                if (password != null && password.isNotEmpty() && secretBytes.isNotEmpty()) {
                    zip.putNextEntry(ZipEntry(SECRETS_ENTRY))
                    zip.write(encrypt(secretBytes, password))
                    zip.closeEntry()
                }
                zipDir(zip, home, "")
            }
            out.absolutePath
        } catch (_: Throwable) {
            runCatching { out.delete() }
            null
        }
    }

    /** 从 zip 覆盖恢复到 /root/.dsh；zip 里没有的文件保留不动。防路径穿越。 */
    fun restore(context: Context, zipPath: String, password: String?): Boolean {
        val home = dshHome(context)
        val zipFile = File(zipPath)
        if (!zipFile.isFile) return false
        return try {
            // 先把 secrets.enc 读出来单独处理（它在正常解包时跳过）。
            var secrets: ByteArray? = null
            ZipInputStream(FileInputStream(zipFile)).use { zin ->
                var entry = zin.nextEntry
                while (entry != null) {
                    if (entry.name == SECRETS_ENTRY) secrets = zin.readBytes()
                    zin.closeEntry()
                    entry = zin.nextEntry
                }
            }
            if (secrets != null) {
                val plain = password?.takeIf { it.isNotEmpty() }?.let { decrypt(secrets!!, it) } ?: return false
                ZipInputStream(ByteArrayInputStream(plain)).use { zin -> unzipInto(zin, home) }
            }
            ZipInputStream(FileInputStream(zipFile)).use { zin ->
                var entry = zin.nextEntry
                while (entry != null) {
                    if (entry.name != SECRETS_ENTRY && safe(entry.name)) {
                        writeEntry(home, entry.name, entry.isDirectory, zin)
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

    /** 备份里有没有加密的敏感文件（恢复前用来决定要不要问口令）。 */
    fun hasSecrets(zipPath: String): Boolean = try {
        ZipInputStream(FileInputStream(File(zipPath))).use { zin ->
            var found = false
            var entry = zin.nextEntry
            while (entry != null) {
                if (entry.name == SECRETS_ENTRY) { found = true; break }
                entry = zin.nextEntry
            }
            found
        }
    } catch (_: Throwable) {
        false
    }

    private fun unzipInto(zin: ZipInputStream, home: File) {
        var entry = zin.nextEntry
        while (entry != null) {
            if (safe(entry.name)) writeEntry(home, entry.name, entry.isDirectory, zin)
            zin.closeEntry()
            entry = zin.nextEntry
        }
    }

    private fun safe(name: String): Boolean = !name.contains("..") && !name.startsWith("/")

    private fun writeEntry(home: File, name: String, dir: Boolean, zin: ZipInputStream) {
        val target = File(home, name)
        if (dir) {
            target.mkdirs()
        } else {
            target.parentFile?.mkdirs()
            FileOutputStream(target).use { zin.copyTo(it) }
        }
    }

    /** 只把敏感文件收进内层 zip（路径保持相对 /root/.dsh）。 */
    private fun collectSecrets(zip: ZipOutputStream, dir: File, prefix: String) {
        dir.listFiles()?.sortedBy { it.name }?.forEach { f ->
            val entryName = prefix + f.name
            if (f.isDirectory) {
                collectSecrets(zip, f, "$entryName/")
            } else if (isSecret(f.name)) {
                zip.putNextEntry(ZipEntry(entryName))
                FileInputStream(f).use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    private fun zipDir(zip: ZipOutputStream, dir: File, prefix: String) {
        dir.listFiles()?.sortedBy { it.name }?.forEach { f ->
            val entryName = prefix + f.name
            if (f.isDirectory) {
                zip.putNextEntry(ZipEntry("$entryName/"))
                zip.closeEntry()
                zipDir(zip, f, "$entryName/")
            } else if (!isSecret(f.name)) {
                zip.putNextEntry(ZipEntry(entryName))
                FileInputStream(f).use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    // ── AES-GCM + PBKDF2 ───────────────────────────────────────────────────────

    private fun keyOf(password: String, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(password.toCharArray(), salt, ITERATIONS, 256)
        val raw = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        return SecretKeySpec(raw, "AES")
    }

    /** MAGIC | salt(16) | iv(12) | ciphertext+tag。 */
    private fun encrypt(plain: ByteArray, password: String): ByteArray {
        val random = SecureRandom()
        val salt = ByteArray(SALT_BYTES).also { random.nextBytes(it) }
        val iv = ByteArray(IV_BYTES).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, keyOf(password, salt), GCMParameterSpec(TAG_BITS, iv))
        val body = cipher.doFinal(plain)
        return ByteArrayOutputStream().apply {
            write(MAGIC.toByteArray(Charsets.US_ASCII))
            write(salt)
            write(iv)
            write(body)
        }.toByteArray()
    }

    private fun decrypt(blob: ByteArray, password: String): ByteArray? = try {
        val head = MAGIC.length + SALT_BYTES + IV_BYTES
        if (blob.size <= head || String(blob, 0, MAGIC.length, Charsets.US_ASCII) != MAGIC) {
            null
        } else {
            val salt = blob.copyOfRange(MAGIC.length, MAGIC.length + SALT_BYTES)
            val iv = blob.copyOfRange(MAGIC.length + SALT_BYTES, head)
            val body = blob.copyOfRange(head, blob.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, keyOf(password, salt), GCMParameterSpec(TAG_BITS, iv))
            cipher.doFinal(body)
        }
    } catch (_: Throwable) {
        null
    }
}
