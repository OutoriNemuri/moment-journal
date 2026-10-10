package com.nemuri.journal.md

/**
 * 解析后的行内片段。
 *
 * 与 [MomentMarkdown] 的语法一一对应；未知标签降级为 [Text]，原文保留。
 */
sealed interface InlineSpan {

    /** 由源文本中的字符区间定位，长度=原文字符数，供编辑器做局部替换。 */
    val sourceRange: IntRange

    /** 纯文本。 */
    data class Text(
        val text: String,
        override val sourceRange: IntRange,
    ) : InlineSpan

    /** 带样式的文本。样式可叠加。 */
    data class Styled(
        val text: String,
        val styles: Set<Style>,
        override val sourceRange: IntRange,
    ) : InlineSpan

    enum class Style { BOLD, ITALIC, UNDERLINE, STRIKETHROUGH }

    /** 时间点嵌入。 */
    data class Time(
        val isoText: String,
        val epochMillis: Long?,
        override val sourceRange: IntRange,
    ) : InlineSpan

    /** 地点嵌入。[name] 可为空串。 */
    data class Location(
        val latitude: Double,
        val longitude: Double,
        val name: String,
        override val sourceRange: IntRange,
    ) : InlineSpan

    /** 图片嵌入。[relativePath] 相对私有 `files/` 目录。 */
    data class Image(
        val relativePath: String,
        override val sourceRange: IntRange,
    ) : InlineSpan
}

/**
 * 解析后的一行。
 *
 * [level] > 0 表示标题层级（1..6），[bullet] 表示无序列表项。
 * [spans] 为该行去掉行首标记后的行内片段。
 */
data class MarkdownLine(
    val raw: String,
    val level: Int = 0,
    val bullet: Boolean = false,
    val spans: List<InlineSpan> = emptyList(),
)

/** 整篇文档的解析结果。 */
data class MarkdownDocument(val lines: List<MarkdownLine>)
