package com.nemuri.journal.backup

import org.json.JSONObject

/**
 * `.mjb` 归档的清单。**这是长期契约** —— 本地备份、云备份与恢复全部依赖它，
 * 字段只能增，不能改语义。
 *
 * 归档结构：
 * ```
 * journal.mjb (ZIP)
 * ├── manifest.json    本清单
 * ├── journal.db       一致性快照（VACUUM INTO 产出，不含 -wal / -shm）
 * └── images/          正文引用的全部图片，路径与正文里的相对路径一致
 * ```
 */
data class MjbManifest(
    /** 归档格式版本。恢复流程必须按它决定如何解读归档。 */
    val formatVersion: Int,
    /** 产生归档的应用版本名，如 "0.1"。 */
    val appVersion: String,
    /** 归档产生时间（epoch millis）。 */
    val createdAt: Long,
    /** 日记条数，用于恢复后自检。 */
    val entryCount: Int,
    /** 日记本数量。 */
    val bookCount: Int,
    /** 图片文件数量。 */
    val imageCount: Int,
) {
    fun toJson(): String = JSONObject()
        .put(KEY_FORMAT_VERSION, formatVersion)
        .put(KEY_APP_VERSION, appVersion)
        .put(KEY_CREATED_AT, createdAt)
        .put(KEY_ENTRY_COUNT, entryCount)
        .put(KEY_BOOK_COUNT, bookCount)
        .put(KEY_IMAGE_COUNT, imageCount)
        .toString(2)

    companion object {
        const val FILE_NAME = "manifest.json"

        const val KEY_FORMAT_VERSION = "formatVersion"
        const val KEY_APP_VERSION = "appVersion"
        const val KEY_CREATED_AT = "createdAt"
        const val KEY_ENTRY_COUNT = "entryCount"
        const val KEY_BOOK_COUNT = "bookCount"
        const val KEY_IMAGE_COUNT = "imageCount"

        /**
         * 当前写入的格式版本。
         * 0.1 定版时条目结构为 {formatVersion, appVersion, createdAt, entryCount, bookCount, imageCount}。
         */
        const val CURRENT_FORMAT_VERSION = 1

        /**
         * 本版恢复逻辑能读的**最高**格式版本。
         * 归档版本高于它时拒绝恢复，而不是猜着读 —— 猜错会污染用户数据。
         */
        const val MAX_READABLE_FORMAT_VERSION = 1

        fun fromJson(json: String): MjbManifest {
            val o = JSONObject(json)
            return MjbManifest(
                formatVersion = o.optInt(KEY_FORMAT_VERSION, 0),
                appVersion = o.optString(KEY_APP_VERSION, ""),
                createdAt = o.optLong(KEY_CREATED_AT, 0L),
                entryCount = o.optInt(KEY_ENTRY_COUNT, -1),
                bookCount = o.optInt(KEY_BOOK_COUNT, -1),
                imageCount = o.optInt(KEY_IMAGE_COUNT, -1),
            )
        }
    }
}

/**
 * 备份/恢复失败的原因。所有失败都必须能被用户看见，不允许静默吞掉。
 *
 * **这些消息刻意不抽成字符串资源。** 它们是诊断信息，不是产品文案：
 * 内容面向"哪里出了问题"而非"用户该怎么办"。正确做法是给用户一句统一的
 * 提示语（"归档无法读取"之类），把具体原因留给日志。
 * 在完成这个区分之前，不把它们固化成"需要翻译的字符串" ——
 * 那等于把内部错误原文当成产品文案来翻译。
 */
sealed class MjbException(message: String) : Exception(message) {
    class NotAnArchive : MjbException("文件不是有效的 .mjb 归档")
    class MissingManifest : MjbException("归档缺少 manifest.json")
    class BadManifest(detail: String) : MjbException("归档清单损坏：$detail")
    class UnsupportedVersion(found: Int, max: Int) :
        MjbException("归档格式版本 $found 高于本应用支持的 $max，请升级应用后重试")
    class MissingDatabase : MjbException("归档缺少数据库文件")
    class CorruptDatabase(detail: String) : MjbException("归档中的数据库损坏：$detail")
}
