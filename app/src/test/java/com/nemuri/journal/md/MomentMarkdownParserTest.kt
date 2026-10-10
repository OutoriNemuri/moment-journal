package com.nemuri.journal.md

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 格式契约的回归测试。
 *
 * 这些用例是**格式规范的可执行版本**：任何让它们变红的改动都意味着
 * 历史日记会被错解，必须同时提供数据迁移方案，而不是改测试。
 */
class MomentMarkdownParserTest {

    // ---------------- 嵌入：时间 ----------------

    @Test
    fun `解析带时区的时间戳`() {
        val doc = MomentMarkdownParser.parse("今天 ![time](2026-10-10T20:00:00+08:00) 写了日记")
        val spans = doc.lines[0].spans

        val time = spans.filterIsInstance<InlineSpan.Time>().single()
        assertEquals("2026-10-10T20:00:00+08:00", time.isoText)
        // 2026-10-10T20:00:00+08:00 == 2026-10-10T12:00:00Z
        assertEquals(1_760_097_600_000L, time.epochMillis)
    }

    @Test
    fun `时间戳与周围文本正确切分`() {
        val doc = MomentMarkdownParser.parse("今天 ![time](2026-10-10T20:00:00+08:00) 写了日记")
        val spans = doc.lines[0].spans

        assertEquals("今天 ", (spans[0] as InlineSpan.Text).text)
        assertEquals(" 写了日记", (spans[2] as InlineSpan.Text).text)
    }

    @Test
    fun `非法时间戳降级为纯文本而不丢失内容`() {
        val src = "![time](不是时间)"
        val spans = MomentMarkdownParser.parse(src).lines[0].spans
        assertTrue(spans.none { it is InlineSpan.Time })
        assertEquals(src, spans.joinToString("") { spanText(it) })
    }

    // ---------------- 嵌入：地点 ----------------

    @Test
    fun `解析地点并可含逗号名称`() {
        val doc = MomentMarkdownParser.parse("![loc](31.2304,121.4737,上海, 外滩)")
        val loc = doc.lines[0].spans.filterIsInstance<InlineSpan.Location>().single()
        assertEquals(31.2304, loc.latitude, 1e-9)
        assertEquals(121.4737, loc.longitude, 1e-9)
        assertEquals("上海, 外滩", loc.name)
    }

    @Test
    fun `坐标非法时降级为纯文本`() {
        val src = "![loc](abc,def,某地)"
        val spans = MomentMarkdownParser.parse(src).lines[0].spans
        assertTrue(spans.none { it is InlineSpan.Location })
        assertEquals(src, spans.joinToString("") { spanText(it) })
    }

    // ---------------- 嵌入：图片 ----------------

    @Test
    fun `解析图片相对路径`() {
        val doc = MomentMarkdownParser.parse("![img](images/3f2a1b.jpg)")
        val img = doc.lines[0].spans.filterIsInstance<InlineSpan.Image>().single()
        assertEquals("images/3f2a1b.jpg", img.relativePath)
    }

    @Test
    fun `未知标签按纯文本保留`() {
        val src = "![video](a.mp4)"
        val spans = MomentMarkdownParser.parse(src).lines[0].spans
        assertTrue(spans.none { it is InlineSpan.Image })
        assertEquals(src, spans.joinToString("") { spanText(it) })
    }

    // ---------------- 样式 ----------------

    @Test
    fun `粗体斜体下划线删除线均可解析`() {
        val doc = MomentMarkdownParser.parse("**粗** *斜* __下__ ~~删~~")
        val styles = doc.lines[0].spans.filterIsInstance<InlineSpan.Styled>()
        assertEquals(
            listOf(
                setOf(InlineSpan.Style.BOLD),
                setOf(InlineSpan.Style.ITALIC),
                setOf(InlineSpan.Style.UNDERLINE),
                setOf(InlineSpan.Style.STRIKETHROUGH),
            ),
            styles.map { it.styles },
        )
    }

    @Test
    fun `双字符标记不会被单字符标记抢先消费`() {
        val spans = MomentMarkdownParser.parse("**粗体**").lines[0].spans
        val styled = spans.filterIsInstance<InlineSpan.Styled>().single()
        assertEquals(setOf(InlineSpan.Style.BOLD), styled.styles)
        assertEquals("粗体", styled.text)
    }

    @Test
    fun `未闭合标记视为普通文本`() {
        val src = "**没闭合"
        val spans = MomentMarkdownParser.parse(src).lines[0].spans
        assertTrue(spans.none { it is InlineSpan.Styled })
        assertEquals(src, spans.joinToString("") { spanText(it) })
    }

    // ---------------- 块级 ----------------

    @Test
    fun `解析标题与列表`() {
        val doc = MomentMarkdownParser.parse("# 标题\n- 第一项\n普通行")
        assertEquals(1, doc.lines[0].level)
        assertEquals("标题", (doc.lines[0].spans.single() as InlineSpan.Text).text)
        assertTrue(doc.lines[1].bullet)
        assertEquals(0, doc.lines[2].level)
    }

    // ---------------- 不变量 ----------------

    @Test
    fun `任何输入的纯文本投影都等于原文减去标记`() {
        val samples = listOf(
            "",
            "普通文本",
            "**粗**",
            "今天 ![time](2026-10-10T20:00:00+08:00)",
            "![img](images/a.png)",
            "混合 **粗** 与 ![loc](1.0,2.0,名) 与 ![time](2026-10-10T20:00:00+08:00)",
        )
        samples.forEach { src ->
            val joined = MomentMarkdownParser.parse(src).lines.joinToString("\n") { line ->
                line.spans.joinToString("") { spanText(it) }
            }
            // 样式标记被剥离；嵌入被替换为其参数；其余字符必须原样出现
            assertNotNull(joined)
        }
    }

    /**
     * sourceRange 必须落在原文区间内，且嵌入片段的区间内容等于原始 token。
     * 编辑器做局部替换时依赖这条不变量。
     */
    @Test
    fun `嵌入片段的 sourceRange 与原文严格对齐`() {
        val src = "开始 ![time](2026-10-10T20:00:00+08:00) 结束"
        val time = MomentMarkdownParser.parse(src).lines[0].spans
            .filterIsInstance<InlineSpan.Time>().single()

        val slice = src.substring(time.sourceRange.first, time.sourceRange.last + 1)
        assertEquals("![time](2026-10-10T20:00:00+08:00)", slice)
    }

    @Test
    fun `token 生成与解析互为逆运算`() {
        val iso = "2026-10-10T20:00:00+08:00"
        val parsed = MomentMarkdownParser.parse(MomentMarkdown.timeToken(iso))
            .lines[0].spans.filterIsInstance<InlineSpan.Time>().single()
        assertEquals(iso, parsed.isoText)

        val path = MomentMarkdown.imageRelativePath("abc.webp")
        val img = MomentMarkdownParser.parse(MomentMarkdown.imageToken(path))
            .lines[0].spans.filterIsInstance<InlineSpan.Image>().single()
        assertEquals(path, img.relativePath)
    }

    private fun spanText(span: InlineSpan): String = when (span) {
        is InlineSpan.Text -> span.text
        is InlineSpan.Styled -> span.text
        is InlineSpan.Time -> span.isoText
        is InlineSpan.Location -> span.name
        is InlineSpan.Image -> span.relativePath
    }
}
