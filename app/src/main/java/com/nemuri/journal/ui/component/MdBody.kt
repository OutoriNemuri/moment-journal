package com.nemuri.journal.ui.screen

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.nemuri.journal.md.InlineSpan
import com.nemuri.journal.md.MarkdownDocument
import com.nemuri.journal.md.MarkdownLine
import com.nemuri.journal.md.MomentMarkdownParser
import com.nemuri.journal.util.ImageStore
import com.nemuri.journal.util.TimeFormat
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle

/**
 * 把隐式扩展 Markdown 渲染成只读视图。
 *
 * 编辑器本身编辑的是**源文本**（光标进得去，见 EditorScreen），
 * 本文件供预览与未来的阅读模式使用。二者共用 [MomentMarkdownParser]，
 * 因此语法只有一处定义。
 */
@Composable
fun MdBody(
    document: MarkdownDocument,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        document.lines.forEachIndexed { index, line ->
            MdLine(line)
            if (index != document.lines.lastIndex) Spacer(Modifier.height(6.dp))
        }
    }
}

/** 从源文本解析文档。 */
fun parseDocument(source: String): MarkdownDocument = MomentMarkdownParser.parse(source)

@Composable
private fun MdLine(line: MarkdownLine) {
    val textStyle = when (line.level) {
        1 -> MaterialTheme.typography.headlineSmall
        2 -> MaterialTheme.typography.titleLarge
        3 -> MaterialTheme.typography.titleMedium
        in 4..6 -> MaterialTheme.typography.titleSmall
        else -> MaterialTheme.typography.bodyLarge
    }

    if (line.spans.isEmpty()) {
        Spacer(Modifier.height(6.dp))
        return
    }

    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        if (line.bullet) {
            Text("•  ", style = textStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Column {
            RenderSpans(line.spans, textStyle)
        }
    }
}

@Composable
private fun RenderSpans(
    spans: List<InlineSpan>,
    textStyle: androidx.compose.ui.text.TextStyle,
) {
    // 行内嵌入（图片）无法用单一 AnnotatedString 表达，因此拆成"文本段 + 嵌入块"交替渲染。
    val buffer = mutableListOf<InlineSpan>()

    fun flushText() {
        if (buffer.isEmpty()) return
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
                    is InlineSpan.Location -> withStyle(
                        SpanStyle(color = EMBED_COLOR)
                    ) { append("📍 " + (span.name.ifEmpty { "${span.latitude},${span.longitude}" })) }
                    is InlineSpan.Image -> Unit // 交给下面的块渲染
                }
            }
        }
        Text(annotated, style = textStyle)
        buffer.clear()
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        spans.forEach { span ->
            if (span is InlineSpan.Image) {
                flushText()
                InlineThumbnail(span.relativePath)
            } else {
                buffer += span
            }
        }
        flushText()
    }
}

@Composable
private fun InlineThumbnail(relativePath: String) {
    val context = LocalContext.current
    val file = androidx.compose.runtime.remember(relativePath) {
        ImageStore.resolve(context, relativePath)
    }
    if (file == null) {
        // 文件缺失（被外部删除、恢复不完整）时显示占位而不是崩溃，也不静默隐藏
        Text(
            "[图片缺失: ${file?.name ?: relativePath}]",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        return
    }
    AsyncImage(
        model = file,
        contentDescription = null,
        contentScale = ContentScale.FillWidth,
        modifier = Modifier.fillMaxWidth().height(180.dp).padding(vertical = 4.dp),
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
