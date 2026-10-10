package com.nemuri.journal.export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.nemuri.journal.md.InlineSpan
import com.nemuri.journal.md.MomentMarkdownParser
import java.io.File
import java.io.FileOutputStream

/**
 * 把一篇日记渲染成一张长图（或切分成多张）。
 *
 * ## 为什么不复用屏幕上的 Composable
 * 屏幕上的排版受屏幕尺寸约束，导出需要固定的输出宽度和不受限的高度。
 * 因此这里走一条**独立的离屏渲染路径**，直接用 Canvas 绘制。
 *
 * ## 为什么必须先测量后绘制
 * Canvas 不会在绘制前告诉你未知内容的最终高度：一行文字折成几行、
 * 一张图片占多高，都要先排一遍才知道。所以本类分两遍 ——
 * 先排版并**把排好的 StaticLayout 留着**，再按总高建位图去画。
 * 留着 layout 而不是在绘制时重排一次，是为了保证两次的换行结果完全一致：
 * 重排一次理论上应该相同，但字体回退、断行策略任何细微差异都会让高度对不上，
 * 而高度对不上就会让后面所有内容错位。
 *
 * ## OOM 与自动切分
 * 位图占用 = 宽 × 高 × 4 字节。1080 × 100000 约 432 MB，必然 OOM。
 * 因此切分模式下每次只分配一张位图，画完立即 recycle。
 */
class LongImageRenderer(private val context: Context) {

    companion object {
        /** 输出宽度。1080 足够清晰，且多数聊天软件能完整发送。 */
        const val DEFAULT_WIDTH = 1080

        private const val PADDING = 48f
        private const val TITLE_SIZE = 56f
        private const val BODY_SIZE = 38f
        private const val META_SIZE = 30f
        private const val LINE_SPACING = 1.4f
        private const val BLOCK_GAP = 28f

        /** 单张位图的像素高度上限。1080 × 24000 × 4 ≈ 99 MB。 */
        const val MAX_HEIGHT_PER_IMAGE = 24_000

        /** 超过此高度即自动切分，不再尝试单张输出。 */
        const val SINGLE_IMAGE_HEIGHT_LIMIT = 12_000
    }

    private val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = TITLE_SIZE
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        color = Color.BLACK
    }
    private val bodyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = BODY_SIZE
        color = Color.BLACK
    }
    private val metaPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = META_SIZE
        color = Color.GRAY
    }

    /** 已排好版的块。高度在构造时就确定，绘制阶段不再重排。 */
    private sealed interface Block {
        val height: Float

        data class Text(override val height: Float, val draw: (Canvas, Float) -> Unit) : Block
        data class Image(
            override val height: Float,
            val file: File,
            val drawWidth: Int,
        ) : Block

        data class Gap(override val height: Float) : Block
    }

    data class RenderResult(
        val files: List<File>,
        /** 内容总高度（像素）。切分时用于向用户说明为何分成了多张。 */
        val totalHeight: Int,
        val sliced: Boolean,
    )

    /**
     * 渲染。
     *
     * @param forceSlice 即使未超限也切分；对应「导出切分的几张短图」。
     */
    fun render(
        title: String,
        body: String,
        meta: String,
        outDir: File,
        baseName: String,
        forceSlice: Boolean = false,
    ): RenderResult {
        outDir.mkdirs()
        val contentWidth = DEFAULT_WIDTH - (PADDING * 2).toInt()

        val blocks = layoutBlocks(title, body, meta, contentWidth)
        val totalHeight = blocks.sumOf { it.height.toDouble() }.toInt()

        val sliced = forceSlice || totalHeight > SINGLE_IMAGE_HEIGHT_LIMIT
        val files = mutableListOf<File>()

        fun emit(slice: List<Block>, index: Int?, suffix: String) {
            val h = slice.sumOf { it.height.toDouble() }.toInt() + (PADDING * 2).toInt()
            if (h <= 0) return
            val bmp = Bitmap.createBitmap(DEFAULT_WIDTH, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            canvas.drawColor(Color.WHITE)
            var y = PADDING
            slice.forEach { block ->
                when (block) {
                    is Block.Text -> block.draw(canvas, y)
                    is Block.Image -> drawImage(canvas, block, y)
                    is Block.Gap -> Unit
                }
                y += block.height
            }
            val name = if (index == null) "$baseName$suffix.png" else "${baseName}_$index$suffix.png"
            val f = File(outDir, name)
            FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bmp.recycle()   // 长图很占内存，画完立即释放
            files += f
        }

        if (!sliced) {
            emit(blocks, null, "")
        } else {
            var i = 0
            var index = 1
            while (i < blocks.size) {
                val slice = mutableListOf<Block>()
                var h = 0f
                while (i < blocks.size && h + blocks[i].height <= MAX_HEIGHT_PER_IMAGE) {
                    slice += blocks[i]
                    h += blocks[i].height
                    i++
                }
                // 单个块自身就超过上限：也必须画出去，否则会死循环
                if (slice.isEmpty()) {
                    slice += blocks[i]
                    i++
                }
                emit(slice, index, "")
                index++
            }
        }
        return RenderResult(files, totalHeight, sliced)
    }

    // -------------------------------------------------------------- 排版

    private fun layoutBlocks(
        title: String,
        body: String,
        meta: String,
        width: Int,
    ): List<Block> {
        val blocks = mutableListOf<Block>()

        if (title.isNotBlank()) {
            val l = layout(title, titlePaint, width)
            blocks += Block.Text(l.height.toFloat() + BLOCK_GAP) { c, y -> drawLayout(c, l, y) }
        }
        val metaLayout = layout(meta, metaPaint, width)
        blocks += Block.Text(metaLayout.height.toFloat() + BLOCK_GAP) { c, y -> drawLayout(c, metaLayout, y) }

        MomentMarkdownParser.parse(body).lines.forEach { line ->
            if (line.raw.isBlank()) {
                blocks += Block.Gap(BLOCK_GAP)
                return@forEach
            }
            line.spans.forEach { span ->
                when (span) {
                    is InlineSpan.Image -> {
                        val file = resolveImage(span.relativePath)
                        val size = file?.let { scaledSize(it, width) }
                        if (file != null && size != null) {
                            val (w, h) = size
                            blocks += Block.Image(h + BLOCK_GAP, file, w.toInt())
                        } else {
                            // 图片缺失时留一行说明。静默跳过会让导出结果与用户
                            // 预期不符，而他无从察觉缺了什么。
                            val l = layout("[图片缺失: ${span.relativePath}]", metaPaint, width)
                            blocks += Block.Text(l.height.toFloat() + BLOCK_GAP) { c, y -> drawLayout(c, l, y) }
                        }
                    }

                    is InlineSpan.Time -> {
                        val l = layout(span.isoText, metaPaint, width)
                        blocks += Block.Text(l.height.toFloat() + BLOCK_GAP / 2) { c, y -> drawLayout(c, l, y) }
                    }

                    is InlineSpan.Location -> {
                        val label = span.name.ifEmpty { "${span.latitude},${span.longitude}" }
                        val l = layout(label, metaPaint, width)
                        blocks += Block.Text(l.height.toFloat() + BLOCK_GAP / 2) { c, y -> drawLayout(c, l, y) }
                    }

                    is InlineSpan.Text -> {
                        val l = layout(span.text, bodyPaint, width)
                        blocks += Block.Text(l.height.toFloat()) { c, y -> drawLayout(c, l, y) }
                    }

                    is InlineSpan.Styled -> {
                        val p = TextPaint(bodyPaint)
                        if (span.styles.contains(InlineSpan.Style.BOLD)) {
                            p.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                        }
                        val l = layout(span.text, p, width)
                        blocks += Block.Text(l.height.toFloat()) { c, y ->
                            drawLayout(c, l, y)
                            if (span.styles.contains(InlineSpan.Style.UNDERLINE) ||
                                span.styles.contains(InlineSpan.Style.STRIKETHROUGH)
                            ) {
                                // 下划线/删除线用一条横线近似，StaticLayout 不直接支持
                                drawRule(c, l, y, span.styles)
                            }
                        }
                    }
                }
            }
        }
        return blocks
    }

    private fun layout(text: String, paint: TextPaint, width: Int): StaticLayout =
        StaticLayout.Builder
            .obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, LINE_SPACING)
            .setIncludePad(false)
            .build()

    private fun drawLayout(canvas: Canvas, l: StaticLayout, y: Float) {
        canvas.save()
        canvas.translate(PADDING, y)
        l.draw(canvas)
        canvas.restore()
    }

    private fun drawRule(canvas: Canvas, l: StaticLayout, y: Float, styles: Set<InlineSpan.Style>) {
        // 简化处理：整段画一条线。逐行处理会准确很多，但当前主要用于长图，
        // 宁可保守（少画）也不要画错位置。
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            strokeWidth = 2f
        }
        val top = y + l.getLineBaseline(0) + 6f
        canvas.drawLine(PADDING, top, PADDING + l.width, top, paint)
        if (styles.contains(InlineSpan.Style.STRIKETHROUGH)) {
            val mid = y + l.getLineBaseline(0) - BODY_SIZE * 0.3f
            canvas.drawLine(PADDING, mid, PADDING + l.width, mid, paint)
        }
    }

    private fun drawImage(canvas: Canvas, block: Block.Image, y: Float) {
        val bmp = decodeSampled(block.file, block.drawWidth) ?: return
        val dest = Rect(
            PADDING.toInt(), y.toInt(),
            (PADDING + block.drawWidth).toInt(),
            (y + block.height - BLOCK_GAP).toInt(),
        )
        canvas.drawBitmap(bmp, null, dest, null)
        bmp.recycle()
    }

    // -------------------------------------------------------------- 工具

    private fun resolveImage(relativePath: String): File? {
        if (relativePath.contains("..")) return null
        val f = File(context.filesDir, relativePath)
        return if (f.exists()) f else null
    }

    private fun scaledSize(file: File, maxWidth: Int): Pair<Float, Float>? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, opts)
        if (opts.outWidth <= 0 || opts.outHeight <= 0) return null
        val scale = maxWidth.toFloat() / opts.outWidth
        return maxWidth.toFloat() to (opts.outHeight * scale)
    }

    /** 按目标宽度降采样解码，避免把整张原图读进内存。 */
    private fun decodeSampled(file: File, targetWidth: Int): Bitmap? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, opts)
        if (opts.outWidth <= 0) return null
        var sample = 1
        while (opts.outWidth / (sample * 2) >= targetWidth) sample *= 2
        return BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sample },
        )
    }
}
