package com.nemuri.journal.ui.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.nemuri.journal.R
import com.nemuri.journal.md.InlineSpan
import com.nemuri.journal.md.MarkdownLine
import com.nemuri.journal.md.MomentMarkdownParser
import com.nemuri.journal.util.ImageMetrics
import com.nemuri.journal.util.ImageStore
import com.nemuri.journal.util.TimeFormat

/**
 * 把隐式扩展 Markdown 渲染成只读视图。
 *
 * 编辑器本身编辑的是**源文本**（光标进得去，见 EditorScreen），
 * 本文件供预览与未来的阅读模式使用。二者共用 [MomentMarkdownParser]，
 * 因此语法只有一处定义。
 */
@Composable
fun MdBody(
    source: String,
    modifier: Modifier = Modifier,
) {
    val document = remember(source) { MomentMarkdownParser.parse(source) }
    Column(modifier) {
        document.lines.forEachIndexed { index, line ->
            MdLine(line)
            if (index != document.lines.lastIndex) Spacer(Modifier.height(6.dp))
        }
    }
}

@Composable
private fun MdLine(line: MarkdownLine) {
    val textStyle = when (line.level) {
        1 -> MaterialTheme.typography.headlineSmall
        2 -> MaterialTheme.typography.titleLarge
        3 -> MaterialTheme.typography.titleMedium
        in 4..6 -> MaterialTheme.typography.titleSmall
        else -> MaterialTheme.typography.bodyLarge
    }

    // 空行用 if/else 两枝表达，不用提前 return。
    // 提前 return 会让组合结构在两次重组间变化（有内容时要走下面的 Row），
    // 现在虽然不崩，但只要在 return 之后加一个 remember 就会崩，
    // 因此不留下这种写法。
    if (line.spans.isEmpty()) {
        Spacer(Modifier.height(textStyle.lineHeight.value.dp.takeIf { it > 0.dp } ?: 6.dp))
    } else {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            if (line.bullet) {
                Text("•  ", style = textStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            RenderSpans(line.spans, textStyle)
        }
    }
}

/**
 * 行内嵌入（图片）无法用单一 AnnotatedString 表达，所以拆成
 * 「文本段 + 嵌入块」交替渲染：遇到图片先把已累积的文本冲刷成一个 Text，
 * 再渲染图片块，然后继续累积。
 */
@Composable
private fun RenderSpans(spans: List<InlineSpan>, textStyle: TextStyle) {
    val buffer = mutableListOf<InlineSpan>()

    Row(verticalAlignment = Alignment.CenterVertically) {
        spans.forEach { span ->
            if (span is InlineSpan.Image) {
                FlushText(buffer, textStyle)
                InlineThumbnail(span.relativePath)
            } else {
                buffer += span
            }
        }
        FlushText(buffer, textStyle)
    }
}

@Composable
private fun FlushText(buffer: MutableList<InlineSpan>, textStyle: TextStyle) {
    if (buffer.isEmpty()) {
        // 没有内容可画。同样不用提前 return —— 见 MdLine 中的说明。
        return@FlushText
    }

    val annotated = buildAnnotatedString {
        buffer.forEach { span ->
            when (span) {
                is InlineSpan.Text -> append(span.text)
                is InlineSpan.Styled -> withStyle(span.styles.toSpanStyle()) { append(span.text) }
                is InlineSpan.Time -> withStyle(
                    SpanStyle(
                        color = EMBED_COLOR,
                        fontWeight = FontWeight.Medium,
                    )
                ) { append("🕘 " + TimeFormat.displayFromIso(span.isoText)) }

                is InlineSpan.Location -> withStyle(SpanStyle(color = EMBED_COLOR)) {
                    append("📍 " + span.name.ifEmpty { "${span.latitude},${span.longitude}" })
                }

                is InlineSpan.Image -> Unit // 已由 RenderSpans 单独处理
            }
        }
    }
    Text(annotated, style = textStyle)
    buffer.clear()
}

@Composable
private fun InlineThumbnail(relativePath: String) {
    val context = LocalContext.current
    val file = remember(relativePath) { ImageStore.resolve(context, relativePath) }
    if (file == null) {
        // 文件缺失（被外部删除、恢复不完整）时显示占位，既不崩溃也不静默隐藏
        Text(
            stringResource(R.string.export_missing_image, relativePath),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        return
    }

    // 按图片自身的宽高比算高度，而不是给一个固定值。
    // 此前写死 180dp，配合 FillWidth 会把竖图裁成一条 —— 所有图片都错，
    // 只是横图看起来没那么明显。
    val widthPx = with(LocalDensity.current) {
        LocalConfiguration.current.screenWidthDp.dp.toPx() - 80f
    }
    val measured = remember(file) { ImageMetrics.heightForWidth(file, widthPx) }
    val capped = remember(file) { ImageMetrics.isHeightCapped(file, widthPx) }

    if (measured == null) {
        // 尺寸读不出来（文件损坏、非图片）时给出占位，而不是画一个瞎猜的高度
        Text(
            stringResource(R.string.export_missing_image, relativePath),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        return
    }

    AsyncImage(
        model = file,
        contentDescription = null,
        // 被限高时用 Fit 保证完整可见；未限高时 FillWidth 正好铺满宽度
        contentScale = if (capped) ContentScale.Fit else ContentScale.FillWidth,
        modifier = Modifier
            .fillMaxWidth()
            .height(with(LocalDensity.current) { measured.toDp() })
            .padding(vertical = 4.dp),
    )
}

private fun Set<InlineSpan.Style>.toSpanStyle(): SpanStyle {
    var style = SpanStyle()
    forEach { s ->
        style = when (s) {
            InlineSpan.Style.BOLD -> style.copy(fontWeight = FontWeight.Bold)
            InlineSpan.Style.ITALIC -> style.copy(fontStyle = FontStyle.Italic)
            InlineSpan.Style.UNDERLINE -> style.copy(textDecoration = TextDecoration.Underline)
            InlineSpan.Style.STRIKETHROUGH -> style.copy(textDecoration = TextDecoration.LineThrough)
        }
    }
    return style
}

private val EMBED_COLOR = androidx.compose.ui.graphics.Color(0xFF4A6350)
