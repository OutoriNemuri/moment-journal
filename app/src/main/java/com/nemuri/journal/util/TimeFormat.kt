package com.nemuri.journal.util

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 时间显示。
 *
 * 给"嵌入到正文里的时间戳"用：日记是给人的，显示必须是本地时区的人类可读形式，
 * 但存进正文的必须是带时区的 ISO-8601 —— 跨时区打开同一篇日记时，语义才不变。
 */
object TimeFormat {

    private val DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    private val DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val LIST_STAMP = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm")

    fun isoFromEpoch(millis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        Instant.ofEpochMilli(millis).atZone(zone).toOffsetDateTime().toString()

    fun isoNow(zone: ZoneId = ZoneId.systemDefault()): String = isoFromEpoch(System.currentTimeMillis(), zone)

    /** 嵌入时间戳的显示形式。 */
    fun displayFromIso(iso: String): String =
        runCatching {
            java.time.OffsetDateTime.parse(iso)
                .atZoneSameInstant(ZoneId.systemDefault())
                .format(DATE_TIME)
        }.getOrElse { iso }

    fun listStamp(millis: Long): String =
        Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(LIST_STAMP)

    fun dateStamp(millis: Long): String =
        Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(DATE)

    /** 用于生成文件名：不含冒号等在各平台上会出问题的字符。 */
    fun fileStamp(millis: Long): String =
        Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HHmm"))
}
