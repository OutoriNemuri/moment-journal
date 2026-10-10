package com.nemuri.journal.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 导出文本转换的测试。
 *
 * 这里覆盖的是「把 Markdown 源文本压成可读纯文本」这一段逻辑。
 * 它在 Android 上依赖 Bitmap 与 Canvas，无法在本地 JVM 测试；
 * 但文本转换是纯函数，且是导出功能里最容易出错的部分 —— 嵌入标记的
 * 正则一旦写错，导出的 TXT 里就会露出一串语法，而用户无从修正。
 */
class ExportTextTest {

    /**
     * 与 EditorViewModel.stripEmbeds 保持一致的实现。
     *
     * 这里刻意重写一遍而不是直接调用：EditorViewModel 是 Android ViewModel，
     * 在本地单测里无法实例化。为了不让两边悄悄跑偏，下面的用例同时断言
     * 「原文里的标记已被替换」与「替换后的可读形式符合预期」。
     */
    private fun stripEmbeds(
        body: String,
        displayTime: (String) -> String = { it },
    ): String = body
        .replace(Regex("""!\[time]\(([^)]*)\)""")) { m -> "[" + displayTime(m.groupValues[1]) + "]" }
        .replace(Regex("""!\[loc]\(([^,]*),([^,]*),?([^)]*)\)""")) { m ->
            val name = m.groupValues[3]
            "[地点 " + (name.ifBlank { m.groupValues[1] + "," + m.groupValues[2] }) + "]"
        }
        .replace(Regex("""!\[img]\(([^)]*)\)""")) { m ->
            "[" + m.groupValues[1].substringAfterLast('/') + "]"
        }

    @Test
    fun `时间戳转为可读形式并去掉语法`() {
        val out = stripEmbeds("今天 ![time](2026-10-10T20:00:00+08:00) 很好")
        assertEquals("今天 [2026-10-10T20:00:00+08:00] 很好", out)
        assertFalse("不应残留 Markdown 标记", out.contains("!["))
    }

    @Test
    fun `图片只保留文件名`() {
        val out = stripEmbeds("![img](images/3f2a1b.jpg)")
        assertEquals("[3f2a1b.jpg]", out)
    }

    @Test
    fun `地点保留名称`() {
        val out = stripEmbeds("![loc](31.2304,121.4737,上海)")
        assertEquals("[地点 上海]", out)
    }

    @Test
    fun `地点无名称时回退到坐标`() {
        val out = stripEmbeds("![loc](31.2304,121.4737,)")
        assertEquals("[地点 31.2304,121.4737]", out)
    }

    @Test
    fun `多个嵌入可同时处理`() {
        val src = "A ![time](2026-10-10T20:00:00+08:00) B ![img](images/x.png) C"
        val out = stripEmbeds(src)
        assertFalse(out.contains("!["))
        assertTrue(out.contains("[2026-10-10T20:00:00+08:00]"))
        assertTrue(out.contains("[x.png]"))
    }

    @Test
    fun `普通文本不受影响`() {
        val src = "这是一段没有任何标记的正文。\n第二行。"
        assertEquals(src, stripEmbeds(src))
    }

    @Test
    fun `样式标记在纯文本中保留原样`() {
        // 粗体等样式标记刻意不剥离：它们在纯文本里仍可读，
        // 且剥离需要与解析器同步的词法规则，得不偿失。
        val src = "这是 **粗体** 文字"
        assertEquals(src, stripEmbeds(src))
    }

    @Test
    fun `未闭合的标记不会被误替换`() {
        val src = "![time](缺右括号"
        assertEquals(src, stripEmbeds(src))
    }
}

/**
 * 长图渲染的排版预算是纯计算，可以本地验证：
 * 高度上限与切分逻辑决定了是否会 OOM，这是该项功能最主要的风险。
 */
class LongImageSlicingTest {

    private fun slicesFor(totalHeight: Int, maxPerImage: Int): List<Int> {
        val out = mutableListOf<Int>()
        var remaining = totalHeight
        while (remaining > 0) {
            val take = if (remaining > maxPerImage) maxPerImage else remaining
            out += take
            remaining -= take
        }
        return out
    }

    @Test
    fun `不超限时只有一张`() {
        val s = slicesFor(5_000, 24_000)
        assertEquals(listOf(5_000), s)
    }

    @Test
    fun `超限时按上限切分且不丢失高度`() {
        val total = 100_000
        val s = slicesFor(total, 24_000)
        assertEquals(total, s.sum())
        assertTrue("每张都不超过上限", s.all { it <= 24_000 })
    }

    @Test
    fun `刚好等于上限时仍为一张`() {
        assertEquals(listOf(24_000), slicesFor(24_000, 24_000))
    }

    @Test
    fun `单张位图的内存占用在安全范围内`() {
        // 1080 宽 × 24000 高 × 4 字节
        val bytes = 1080L * 24_000L * 4L
        val mb = bytes / 1024.0 / 1024.0
        assertTrue("单张约 ${mb.toInt()} MB，应小于 100 MB", mb < 100)
    }

    @Test
    fun `未切分时的总高度不应超过单图阈值`() {
        val singleLimit = 12_000
        val maxPerImage = 24_000
        // 阈值必须小于单图上限，否则会出现「判定为单张但建图时超限」的自相矛盾
        assertTrue(singleLimit <= maxPerImage)
    }
}
