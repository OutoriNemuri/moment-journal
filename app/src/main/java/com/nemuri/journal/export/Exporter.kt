package com.nemuri.journal.export

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/**
 * 导出的落盘与分享。
 *
 * ## 两种落地方式
 * - **文本（TXT / Markdown）**：走 SAF 的 `CreateDocument`，由用户选择保存位置。
 *   与备份目录同一套权限模型，不申请存储权限。
 * - **图片（长图 / 切图）**：先渲染到应用缓存目录，再通过 `FileProvider`
 *   以分享或「另存为」的方式交给系统。图片是多文件产物（切图可能是几张），
 *   逐个走 SAF 选择位置体验很差，因此交给系统的分享面板统一处理。
 *
 * ## 缓存目录
 * 图片写在 `cacheDir/exports/`。缓存目录由系统在空间紧张时清理，
 * 这对临时产物是合适的 —— 用户真正想留下的那份，通过分享面板存到了别处。
 */
object Exporter {

    private const val EXPORT_DIR = "exports"
    private const val AUTHORITY_SUFFIX = ".fileprovider"

    enum class TextFormat(val label: String, val extension: String, val mime: String) {
        TXT("纯文本 (.txt)", "txt", "text/plain"),
        MARKDOWN("Markdown (.md)", "md", "text/markdown"),
    }

    data class ImageResult(
        val files: List<File>,
        val totalHeight: Int,
        val sliced: Boolean,
    )

    /** 渲染长图或切图，返回缓存中的文件。 */
    fun renderImages(
        context: Context,
        title: String,
        body: String,
        meta: String,
        baseName: String,
        slice: Boolean,
    ): ImageResult {
        val dir = File(context.cacheDir, EXPORT_DIR).apply {
            // 先清掉上一次的产物，避免缓存目录无限增长
            listFiles()?.forEach { it.delete() }
            mkdirs()
        }
        val result = LongImageRenderer(context).render(
            title = title,
            body = body,
            meta = meta,
            outDir = dir,
            baseName = baseName,
            forceSlice = slice,
        )
        return ImageResult(result.files, result.totalHeight, result.sliced)
    }

    /** 分享若干文件。单张也是走同一条路径，避免两套逻辑。 */
    fun shareFiles(context: Context, files: List<File>, mimeType: String, subject: String) {
        if (files.isEmpty()) return
        val authority = context.packageName + AUTHORITY_SUFFIX
        val uris = files.map {
            FileProvider.getUriForFile(context, authority, it)
        }.toTypedArray()

        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).apply {
                type = mimeType
                putExtra(Intent.EXTRA_STREAM, uris[0])
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = mimeType
                putExtra(Intent.EXTRA_STREAM, uris.toList().toTypedArray())
            }
        }.apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            putExtra(Intent.EXTRA_SUBJECT, subject)
        }

        context.startActivity(
            Intent.createChooser(intent, subject).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
