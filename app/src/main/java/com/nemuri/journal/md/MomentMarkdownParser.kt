package com.nemuri.journal.md

import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

/**
 * 隐式扩展 Markdown 解析器。纯函数，无 Android 依赖，可单元测试。
 *
 * 设计要点：
 * - **不抛异常**。任何无法识别的输入都降级为普通文本，保证用户数据永不因解析失败而丢失。
 * - **sourceRange 与原文严格对齐**，编辑器靠它做局部替换（例如把插入的时间戳转成显示样式）。
 */
object MomentMarkdownParser {

    private val HEADING = Regex("""^(#{1,6})\s+(.*)$""")
    private val BULLET = Regex("""^[-*+]\s+(.*)$""")

    fun parse(source: String): MarkdownDocument =
        MarkdownDocument(source.split('\n').map { parseLine(it) })

    fun parseLine(raw: String): MarkdownLine {
        HEADING.matchEntire(raw)?.let { m ->
            val offset = m.groupValues[0].length - m.groupValues[2].length
            return MarkdownLine(
                raw = raw,
                level = m.groupValues[1].length,
                spans = parseInline(m.groupValues[2], offset),
            )
        }
        BULLET.matchEntire(raw)?.let { m ->
            val offset = raw.length - m.groupValues[1].length
            return MarkdownLine(
                raw = raw,
                bullet = true,
                spans = parseInline(m.groupValues[1], offset),
            )
        }
        return MarkdownLine(raw = raw, spans = parseInline(raw, 0))
    }

    /**
     * 扫描行内内容。
     *
     * [baseOffset] 为该行内容在整篇源文本中的起始偏移（行首标记已消耗的字符数），
     * 便于把行内区间换算回全文坐标。
     */
    fun parseInline(content: String, baseOffset: Int = 0): List<InlineSpan> {
        val spans = mutableListOf<InlineSpan>()
        val pending = StringBuilder()
        var pendingStart = 0
        var i = 0

        fun flush(end: Int) {
            if (pending.isNotEmpty()) {
                spans += InlineSpan.Text(
                    text = pending.toString(),
                    sourceRange = (baseOffset + pendingStart) until (baseOffset + end),
                )
                pending.clear()
            }
        }

        while (i < content.length) {
            // 1) 优先匹配三个嵌入标签
            val embed = tryEmbed(content, i)
            if (embed != null) {
                flush(i)
                val (span, next) = embed
                spans += span.rebase(baseOffset)
                i = next
                pendingStart = i
                continue
            }

            // 2) 样式标记
            val styled = tryStyle(content, i)
            if (styled != null) {
                flush(i)
                val (text, styles, next) = styled
                spans += InlineSpan.Styled(
                    text = text,
                    styles = styles,
                    sourceRange = (baseOffset + i) until (baseOffset + next),
                )
                i = next
                pendingStart = i
                continue
            }

            if (pending.isEmpty()) pendingStart = i
            pending.append(content[i])
            i++
        }
        flush(content.length)
        return spans
    }

    // ---------------- 嵌入标签 ----------------

    private fun tryEmbed(content: String, at: Int): Pair<InlineSpan, Int>? {
        if (!content.startsWith("![", at)) return null
        val close = content.indexOf(')', at + 2)
        if (close < 0) return null

        val head = content.substring(at + 2, close)
        val open = head.indexOf("](")
        if (open < 0 || !head.startsWith("[").not()) return null

        // head 形如 "time](2026-...-08:00"：标签在 ']' 之前，参数在 "(" 之后
        val bracket = head.indexOf(']')
        if (bracket <= 0) return null
        val tag = head.substring(0, bracket)
        val arg = head.substring(bracket + 1).removePrefix("(")
        val end = close + 1
        val range = at until end

        val span: InlineSpan? = when (tag) {
            MomentMarkdown.TAG_TIME -> {
                val iso = arg.trim()
                if (iso.isEmpty()) null
                else InlineSpan.Time(isoText = iso, epochMillis = parseIso(iso), sourceRange = range)
            }

            MomentMarkdown.TAG_LOCATION -> {
                val parts = arg.split(',')
                if (parts.size < 2) null
                else {
                    val lat = parts[0].trim().toDoubleOrNull()
                    val lon = parts[1].trim().toDoubleOrNull()
                    if (lat == null || lon == null) null
                    else InlineSpan.Location(
                        latitude = lat,
                        longitude = lon,
                        name = parts.drop(2).joinToString(",").trim(),
                        sourceRange = range,
                    )
                }
            }

            MomentMarkdown.TAG_IMAGE -> {
                val path = arg.trim()
                if (path.isEmpty()) null else InlineSpan.Image(relativePath = path, sourceRange = range)
            }

            // 未知标签 → 降级为普通文本（格式演进规则 2）
            else -> null
        }
        return span?.let { it to end }
    }

    private fun parseIso(iso: String): Long? = try {
        OffsetDateTime.parse(iso).toInstant().toEpochMilli()
    } catch (_: DateTimeParseException) {
        null
    }

    // ---------------- 样式 ----------------

    private data class StyleResult(val text: String, val styles: Set<InlineSpan.Style>, val end: Int)

    /**
     * 依次尝试匹配样式标记。顺序重要：先匹配双字符标记，避免 `**` 被 `*` 抢先消费。
     */
    private fun tryStyle(content: String, at: Int): StyleResult? {
        val candidates = listOf(
            "**" to InlineSpan.Style.BOLD,
            "__" to InlineSpan.Style.UNDERLINE,
            "~~" to InlineSpan.Style.STRIKETHROUGH,
            "*" to InlineSpan.Style.ITALIC,
        )
        for ((marker, style) in candidates) {
            if (!content.startsWith(marker, at)) continue
            val close = content.indexOf(marker, at + marker.length)
            if (close <= at + marker.length) continue // 空内容或未闭合，视为普通文本
            val inner = content.substring(at + marker.length, close)
            if (inner.isBlank()) continue
            return StyleResult(inner, setOf(style), close + marker.length)
        }
        return null
    }

    private fun InlineSpan.rebase(offset: Int): InlineSpan = when (this) {
        is InlineSpan.Text -> copy(sourceRange = (sourceRange.first + offset)..(sourceRange.last + offset))
        is InlineSpan.Styled -> copy(sourceRange = (sourceRange.first + offset)..(sourceRange.last + offset))
        is InlineSpan.Time -> copy(sourceRange = (sourceRange.first + offset)..(sourceRange.last + offset))
        is InlineSpan.Location -> copy(sourceRange = (sourceRange.first + offset)..(sourceRange.last + offset))
        is InlineSpan.Image -> copy(sourceRange = (sourceRange.first + offset)..(sourceRange.last + offset))
    }
}
