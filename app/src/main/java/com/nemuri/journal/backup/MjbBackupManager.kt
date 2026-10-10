package com.nemuri.journal.backup

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.nemuri.journal.data.db.MomentDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 本地 `.mjb` 备份与恢复。
 *
 * ## 归档格式
 * 见 [MjbManifest]。归档是 ZIP，含 `manifest.json` / `journal.db` / `images/`。
 *
 * ## 一致性
 * **绝不直接复制正在写入的数据库文件** —— WAL 模式下那会得到损坏的归档。
 * 一律通过 `VACUUM INTO` 产出快照，快照是完整、自洽的单文件数据库。
 *
 * ## 触发时机
 * 冷启动完成后 + 应用退到后台（onStop）。
 * 本版**不做**精确定时备份：Android 的 Doze 与后台限制会压制它，
 * 承诺一个做不到的周期只会让用户以为数据安全。
 */
class MjbBackupManager(private val context: Context) {

    companion object {
        /** 用户选定的备份根目录下，由本应用占用的子目录名。 */
        const val BACKUP_DIR_NAME = "MomentJournal"
        const val BACKUP_SUBDIR = "Backups"
        const val EXT = "mjb"

        const val ENTRY_MANIFEST = MjbManifest.FILE_NAME
        const val ENTRY_DB = "journal.db"
        const val ENTRY_IMAGES_PREFIX = "images/"

        /** 保留的归档份数上限，超出后按时间删除最旧的。 */
        const val MAX_ARCHIVES = 30
    }

    private val dbFile = MomentDatabase.fileFor(context)

    // ---------------------------------------------------------------- 备份

    /**
     * 执行一次完整备份。
     *
     * @param treeUri 用户通过文件框架选定的目录（`%i`），归档落在 `%i/MomentJournal/Backups`。
     * @return 产出的归档文件，失败抛异常，不静默返回。
     */
    suspend fun backup(treeUri: Uri): BackupResult = withContext(Dispatchers.IO) {
        val root = DocumentFile.fromTreeUri(context, treeUri)
            ?: throw IllegalStateException("无法访问所选目录，请重新选择备份位置")

        val appDir = root.findFile(BACKUP_DIR_NAME) ?: root.createDirectory(BACKUP_DIR_NAME)
            ?: throw IllegalStateException("无法在所选目录创建 $BACKUP_DIR_NAME")
        val backupsDir = appDir.findFile(BACKUP_SUBDIR) ?: appDir.createDirectory(BACKUP_SUBDIR)
            ?: throw IllegalStateException("无法创建备份目录")

        // 1) 一致性快照。VACUUM INTO 要求目标文件不存在。
        //
        //    必须用独立连接对数据库**文件**执行快照，而不是复用 Room 的连接：
        //    Room 的 writableDatabase 是它自己持有的共享句柄，关掉它会顺手
        //    关闭整个应用的数据库连接，把后续所有查询一起搞崩。
        //    独立连接读同一个文件，VACUUM 会与 Room 的写入自动串行化。
        val snapshot = File(context.cacheDir, "snapshot-$STAMP.db")
        snapshot.delete()
        try {
            android.database.sqlite.SQLiteDatabase
                .openDatabase(dbFile.absolutePath, null, android.database.sqlite.SQLiteDatabase.OPEN_READWRITE)
                .use { conn ->
                    conn.execSQL("VACUUM INTO ?", arrayOf(snapshot.absolutePath))
                }
        } catch (t: Throwable) {
            snapshot.delete()
            throw IllegalStateException("数据库快照失败，已中止备份：${t.message}", t)
        }
        if (!snapshot.exists() || snapshot.length() == 0L) {
            snapshot.delete()
            throw IllegalStateException("数据库快照失败，已中止备份")
        }

        // 2) 统计并打包
        val dao = MomentDatabase.get(context).dao()
        val entryCount = dao.countEntries()
        val bookCount = dao.countBooks()
        val images = collectImages()

        val fileName = "MomentJournal-$STAMP.$EXT"
        val target = backupsDir.createFile("application/zip", fileName)
            ?: run {
                snapshot.delete()
                throw IllegalStateException("无法创建归档文件 $fileName")
            }

        try {
            context.contentResolver.openOutputStream(target.uri)?.use { raw ->
                ZipOutputStream(BufferedOutputStream(raw)).use { zip ->
                    // manifest 放第一个：读取端可以边解压边决策
                    val manifest = MjbManifest(
                        formatVersion = MjbManifest.CURRENT_FORMAT_VERSION,
                        appVersion = appVersionName(),
                        createdAt = System.currentTimeMillis(),
                        entryCount = entryCount,
                        bookCount = bookCount,
                        imageCount = images.size,
                    )
                    zip.putNextEntry(ZipEntry(ENTRY_MANIFEST))
                    zip.write(manifest.toJson().toByteArray())
                    zip.closeEntry()

                    zip.putNextEntry(ZipEntry(ENTRY_DB))
                    snapshot.inputStream().use { it.copyTo(zip, DEFAULT_BUFFER) }
                    zip.closeEntry()

                    images.forEach { file ->
                        zip.putNextEntry(ZipEntry(ENTRY_IMAGES_PREFIX + file.name))
                        file.inputStream().use { it.copyTo(zip, DEFAULT_BUFFER) }
                        zip.closeEntry()
                    }
                }
            } ?: throw IllegalStateException("无法写入归档文件")
        } catch (t: Throwable) {
            runCatching { target.delete() } // 半成品归档绝不留下
            throw t
        } finally {
            snapshot.delete()
        }

        pruneOldArchives(backupsDir)

        BackupResult(
            uri = target.uri,
            displayName = fileName,
            sizeBytes = target.length(),
            entryCount = entryCount,
            imageCount = images.size,
        )
    }

    private fun collectImages(): List<File> {
        val dir = File(context.filesDir, com.nemuri.journal.md.MomentMarkdown.IMAGE_DIR)
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles { f -> f.isFile }?.toList() ?: emptyList()
    }

    /** 保留最近 [MAX_ARCHIVES] 份，其余删除。 */
    private fun pruneOldArchives(dir: DocumentFile) {
        val files = dir.listFiles()
            .filter { it.isFile && it.name?.endsWith(".$EXT") == true }
            .sortedByDescending { it.name.orEmpty() }
        files.drop(MAX_ARCHIVES).forEach { runCatching { it.delete() } }
    }

    // ---------------------------------------------------------------- 恢复

    /**
     * 从归档恢复。
     *
     * 恢复是**破坏性**操作：成功路径为
     * 「校验 → 关闭数据库单例 → 换入新文件 → 重新打开」。
     * 校验在动任何现有数据之前完成，校验不过则数据库完全没有被触碰。
     */
    suspend fun restore(archive: Uri): RestoreResult = withContext(Dispatchers.IO) {
        val staging = File(context.cacheDir, "restore-${System.currentTimeMillis()}")
        staging.mkdirs()
        try {
            val manifest = extractTo(archive, staging)

            val stagedDb = File(staging, ENTRY_DB)
            if (!stagedDb.exists()) throw MjbException.MissingDatabase()
            verifyDatabase(stagedDb)

            val imageCount = File(staging, "images").listFiles()?.size ?: 0

            // ---- 校验全部通过，从这里开始才动现有数据 ----
            MomentDatabase.closeForRestore()

            MomentDatabase.fileFor(context).let { live ->
                live.parentFile?.mkdirs()
                // 清理 WAL/SHM，否则旧日志会被误当成新库的一部分
                MomentDatabase.auxFilesFor(context).forEach { runCatching { it.delete() } }
                runCatching { live.delete() }
                if (!stagedDb.renameTo(live)) {
                    stagedDb.copyTo(live, overwrite = true)
                    stagedDb.delete()
                }
            }

            val liveImages = File(context.filesDir, com.nemuri.journal.md.MomentMarkdown.IMAGE_DIR)
            liveImages.mkdirs()
            File(staging, "images").listFiles()?.forEach { img ->
                val dest = File(liveImages, img.name)
                img.copyTo(dest, overwrite = true)
            }

            // 重新打开并按 manifest 自检
            val count = MomentDatabase.get(context).dao().countEntries()

            RestoreResult(
                manifest = manifest,
                restoredEntryCount = count,
                restoredImageCount = imageCount,
                manifestMismatch = manifest.entryCount >= 0 && manifest.entryCount != count,
            )
        } finally {
            staging.deleteRecursively()
        }
    }

    /**
     * 只读取归档的清单，不落任何数据。
     *
     * 用于「先把归档内容展示给用户看过，再让他决定是否覆盖现有数据」。
     * 与 [restore] 共用同一套版本校验（见 [validateManifest]），
     * 避免出现「预览说能读、恢复却说版本不支持」这种自相矛盾。
     */
    suspend fun readManifest(archive: Uri): MjbManifest = withContext(Dispatchers.IO) {
        val staging = File(context.cacheDir, "inspect-${System.currentTimeMillis()}")
        staging.mkdirs()
        try {
            extractTo(archive, staging, extractPayload = false)
        } finally {
            staging.deleteRecursively()
        }
    }

    /**
     * 解压到暂存区并读取 manifest。
     *
     * [extractPayload] 为 false 时只读清单、不写出任何文件，
     * 供 [readManifest] 做无副作用的预览。
     */
    private fun extractTo(
        archive: Uri,
        dest: File,
        extractPayload: Boolean = true,
    ): MjbManifest {
        var manifest: MjbManifest? = null
        val input: InputStream = context.contentResolver.openInputStream(archive)
            ?: throw MjbException.NotAnArchive()

        BufferedInputStream(input).use { raw ->
            // ZIP 必须有可用的中央目录；非 ZIP 会在构造时抛异常
            val zip = try {
                ZipInputStream(raw)
            } catch (t: Throwable) {
                throw MjbException.NotAnArchive()
            }
            var sawAnyEntry = false
            while (true) {
                val entry = try {
                    zip.nextEntry ?: break
                } catch (t: Throwable) {
                    throw MjbException.NotAnArchive()
                }
                sawAnyEntry = true
                val name = entry.name

                // 防御路径穿越：归档内的条目名不得跳出暂存目录
                if (name.contains("..") || name.startsWith("/")) {
                    zip.closeEntry()
                    continue
                }

                when {
                    name == ENTRY_MANIFEST -> {
                        val text = zip.readBytes().toString(Charsets.UTF_8)
                        manifest = try {
                            MjbManifest.fromJson(text)
                        } catch (t: Throwable) {
                            throw MjbException.BadManifest(t.message ?: "无法解析")
                        }
                    }

                    extractPayload && name.startsWith(ENTRY_IMAGES_PREFIX) -> {
                        val file = File(dest, name)
                        file.parentFile?.mkdirs()
                        file.outputStream().use { zip.copyTo(it, DEFAULT_BUFFER) }
                    }

                    extractPayload && name == ENTRY_DB -> {
                        File(dest, ENTRY_DB).outputStream().use { zip.copyTo(it, DEFAULT_BUFFER) }
                    }

                    else -> Unit // 未知条目忽略，保持向前兼容
                }
                zip.closeEntry()
            }

            if (!sawAnyEntry) throw MjbException.NotAnArchive()
        }

        return validateManifest(manifest)
    }

    /** 清单必须存在、有版本号，且不高于本应用能读的上限。 */
    private fun validateManifest(manifest: MjbManifest?): MjbManifest {
        val m = manifest ?: throw MjbException.MissingManifest()
        if (m.formatVersion <= 0) throw MjbException.BadManifest("缺少 formatVersion")
        if (m.formatVersion > MjbManifest.MAX_READABLE_FORMAT_VERSION) {
            throw MjbException.UnsupportedVersion(
                m.formatVersion, MjbManifest.MAX_READABLE_FORMAT_VERSION,
            )
        }
        return m
    }

    /**
     * 打开暂存数据库并读一次，确认它能用。
     * 损坏的归档必须在这里被挡住，而不是换入之后再炸。
     */
    private fun verifyDatabase(file: File) {
        try {
            android.database.sqlite.SQLiteDatabase.openDatabase(
                file.absolutePath, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY,
            ).use { db ->
                db.rawQuery("SELECT COUNT(*) FROM entries", null).use { c ->
                    if (!c.moveToFirst()) throw MjbException.CorruptDatabase("entries 表不可读")
                }
            }
        } catch (e: MjbException) {
            throw e
        } catch (t: Throwable) {
            throw MjbException.CorruptDatabase(t.message ?: "无法打开")
        }
    }

    private fun appVersionName(): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    }.getOrDefault("")

    private val STAMP: String
        get() = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
}

data class BackupResult(
    val uri: Uri,
    val displayName: String,
    val sizeBytes: Long,
    val entryCount: Int,
    val imageCount: Int,
)

data class RestoreResult(
    val manifest: MjbManifest,
    val restoredEntryCount: Int,
    val restoredImageCount: Int,
    /** manifest 记录的条数与恢复后实际条数不一致。为 true 时应提示用户。 */
    val manifestMismatch: Boolean,
)

/** 读取 ZIP 条目全部内容的小工具。 */
private fun ZipInputStream.readBytes(): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    copyTo(out, 8192)
    return out.toByteArray()
}

private const val DEFAULT_BUFFER = 64 * 1024
