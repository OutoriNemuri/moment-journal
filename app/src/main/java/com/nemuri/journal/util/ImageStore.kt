package com.nemuri.journal.util

import android.content.Context
import android.net.Uri
import com.nemuri.journal.md.MomentMarkdown
import java.io.File
import java.util.UUID

/**
 * 图片落盘。正文里只出现相对路径，字节永远在私有目录，不进数据库。
 *
 * 理由：单张照片动辄数百 KB，进库会让 `journal.db` 迅速膨胀，
 * `.mjb` 归档也变得难以打包/传输。
 */
object ImageStore {

    private val ALLOWED = setOf("jpg", "jpeg", "png", "webp", "gif", "heic", "heif", "bmp")

    fun dir(context: Context): File =
        File(context.filesDir, MomentMarkdown.IMAGE_DIR).apply { mkdirs() }

    /** 从相册/文件选择器导入一张图片，返回可写入正文的相对路径。 */
    fun import(context: Context, source: Uri, displayName: String? = null): String? {
        val ext = displayName
            ?.substringAfterLast('.', "")
            ?.lowercase()
            ?.takeIf { it in ALLOWED }
            ?: "jpg"

        val fileName = "${UUID.randomUUID()}.$ext"
        val dest = File(dir(context), fileName)

        // 采用"先写临时文件再改名"的方式：中途失败不会留下半张图被正文引用
        val tmp = File(dir(context), "$fileName.part")
        return try {
            context.contentResolver.openInputStream(source)?.use { input ->
                tmp.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
            } ?: return null
            if (tmp.length() == 0L) {
                tmp.delete()
                return null
            }
            if (!tmp.renameTo(dest)) {
                tmp.copyTo(dest, overwrite = true)
                tmp.delete()
            }
            MomentMarkdown.imageRelativePath(fileName)
        } catch (t: Throwable) {
            tmp.delete()
            null
        }
    }

    /** 把正文里的相对路径还原成绝对文件。不存在时返回 null，调用方负责显示占位。 */
    fun resolve(context: Context, relativePath: String): File? {
        if (relativePath.contains("..")) return null // 拒绝跳出私有目录
        val f = File(context.filesDir, relativePath)
        return if (f.exists()) f else null
    }

    /**
     * 清掉正文已不再引用的图片。
     * 删除日记后调用；只删除孤立文件，不动任何仍被引用的图。
     */
    fun pruneOrphans(context: Context, referenced: Set<String>): Int {
        var removed = 0
        dir(context).listFiles()?.forEach { file ->
            val rel = MomentMarkdown.imageRelativePath(file.name)
            if (file.name.endsWith(".part")) {
                file.delete(); removed++
            } else if (rel !in referenced) {
                if (file.delete()) removed++
            }
        }
        return removed
    }
}
