package name.gornostal.loopback.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
) {
    val blocks = remember(markdown) { parseBlocks(markdown) }
    val linkColor = MaterialTheme.colorScheme.primary
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        blocks.forEach { block -> Block(block, color, linkColor) }
    }
}

@Composable
private fun Block(block: MdBlock, color: Color, linkColor: Color) {
    when (block) {
        is MdBlock.Heading -> {
            val style = when (block.level) {
                1 -> MaterialTheme.typography.titleLarge
                2 -> MaterialTheme.typography.titleMedium
                else -> MaterialTheme.typography.titleSmall
            }
            Text(parseInline(block.text, linkColor), color = color, style = style)
        }

        is MdBlock.Paragraph ->
            Text(parseInline(block.text, linkColor), color = color)

        is MdBlock.CodeBlock -> CodeBlock(block.code)

        is MdBlock.BulletList ->
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                block.items.forEach { item -> ListRow("•", parseInline(item, linkColor), color) }
            }

        is MdBlock.OrderedList ->
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                block.items.forEachIndexed { i, item ->
                    ListRow("${i + 1}.", parseInline(item, linkColor), color)
                }
            }

        is MdBlock.Quote -> {
            val fallback = LocalContentColor.current
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    parseInline(block.text, linkColor),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    color = color.takeOrElse { fallback }.copy(alpha = 0.85f),
                    style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
                )
            }
        }

        MdBlock.Divider -> HorizontalDivider()
    }
}

@Composable
private fun ListRow(marker: String, content: AnnotatedString, color: Color) {
    Row {
        Text("$marker ", color = color)
        Text(content, color = color)
    }
}

@Composable
private fun CodeBlock(code: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(6.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = code,
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(10.dp),
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun Color.takeOrElse(block: () -> Color): Color = if (this == Color.Unspecified) block() else this


private sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Paragraph(val text: String) : MdBlock
    data class CodeBlock(val code: String, val lang: String) : MdBlock
    data class BulletList(val items: List<String>) : MdBlock
    data class OrderedList(val items: List<String>) : MdBlock
    data class Quote(val text: String) : MdBlock
    data object Divider : MdBlock
}

private val HEADING = Regex("^(#{1,6})\\s+(.*)$")
private val BULLET = Regex("^[-*+]\\s+(.*)$")
private val ORDERED = Regex("^\\d+[.)]\\s+(.*)$")
private val RULE = Regex("^(-{3,}|\\*{3,}|_{3,})$")

private fun parseBlocks(markdown: String): List<MdBlock> {
    val lines = markdown.replace("\r\n", "\n").split("\n")
    val blocks = mutableListOf<MdBlock>()
    val paragraph = StringBuilder()

    fun flushParagraph() {
        if (paragraph.isNotBlank()) blocks.add(MdBlock.Paragraph(paragraph.toString().trim()))
        paragraph.setLength(0)
    }

    var i = 0
    while (i < lines.size) {
        val raw = lines[i]
        val line = raw.trim()
        when {
            line.startsWith("```") -> {
                flushParagraph()
                val lang = line.removePrefix("```").trim()
                val code = StringBuilder()
                i++
                while (i < lines.size && !lines[i].trim().startsWith("```")) {
                    code.append(lines[i]).append('\n')
                    i++
                }
                i++
                blocks.add(MdBlock.CodeBlock(code.toString().trimEnd('\n'), lang))
            }

            line.isEmpty() -> {
                flushParagraph()
                i++
            }

            RULE.matches(line) -> {
                flushParagraph()
                blocks.add(MdBlock.Divider)
                i++
            }

            HEADING.matches(line) -> {
                flushParagraph()
                val m = HEADING.find(line)!!
                blocks.add(MdBlock.Heading(m.groupValues[1].length, m.groupValues[2].trim()))
                i++
            }

            BULLET.matches(line) -> {
                flushParagraph()
                val items = mutableListOf<String>()
                while (i < lines.size) {
                    val m = BULLET.find(lines[i].trim()) ?: break
                    items.add(m.groupValues[1])
                    i++
                }
                blocks.add(MdBlock.BulletList(items))
            }

            ORDERED.matches(line) -> {
                flushParagraph()
                val items = mutableListOf<String>()
                while (i < lines.size) {
                    val m = ORDERED.find(lines[i].trim()) ?: break
                    items.add(m.groupValues[1])
                    i++
                }
                blocks.add(MdBlock.OrderedList(items))
            }

            line.startsWith(">") -> {
                flushParagraph()
                val quote = StringBuilder()
                while (i < lines.size && lines[i].trim().startsWith(">")) {
                    if (quote.isNotEmpty()) quote.append(' ')
                    quote.append(lines[i].trim().removePrefix(">").trim())
                    i++
                }
                blocks.add(MdBlock.Quote(quote.toString()))
            }

            else -> {
                if (paragraph.isNotEmpty()) paragraph.append(' ')
                paragraph.append(line)
                i++
            }
        }
    }
    flushParagraph()
    return blocks
}


private val codeSpan = SpanStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp)
private val bold = SpanStyle(fontWeight = FontWeight.Bold)
private val italic = SpanStyle(fontStyle = FontStyle.Italic)
private val strike = SpanStyle(textDecoration = TextDecoration.LineThrough)

private fun parseInline(text: String, linkColor: Color): AnnotatedString = buildAnnotatedString {
    appendInline(text, linkColor)
}

private fun AnnotatedString.Builder.appendInline(text: String, linkColor: Color) {
    var i = 0
    val n = text.length
    while (i < n) {
        val c = text[i]
        when {
            c == '`' -> {
                val end = text.indexOf('`', i + 1)
                if (end == -1) {
                    append(c); i++
                } else {
                    withStyle(codeSpan) { append(text.substring(i + 1, end)) }
                    i = end + 1
                }
            }

            c == '[' -> {
                val consumed = appendLink(text, i, linkColor)
                if (consumed > 0) i += consumed else { append(c); i++ }
            }

            text.startsWith("**", i) || (text.startsWith("__", i) && isBoundary(text, i - 1)) -> {
                val consumed = appendDelimited(text, i, text.substring(i, i + 2), bold, linkColor)
                if (consumed > 0) i += consumed else { append(c); i++ }
            }

            text.startsWith("~~", i) -> {
                val consumed = appendDelimited(text, i, "~~", strike, linkColor)
                if (consumed > 0) i += consumed else { append(c); i++ }
            }

            c == '*' || (c == '_' && isBoundary(text, i - 1)) -> {
                val consumed = appendDelimited(text, i, c.toString(), italic, linkColor)
                if (consumed > 0) i += consumed else { append(c); i++ }
            }

            else -> {
                append(c); i++
            }
        }
    }
}

private fun AnnotatedString.Builder.appendDelimited(
    text: String,
    start: Int,
    marker: String,
    style: SpanStyle,
    linkColor: Color,
): Int {
    val contentStart = start + marker.length
    val end = text.indexOf(marker, contentStart)
    if (end <= contentStart) return 0
    if (marker[0] == '_' && !isBoundary(text, end + marker.length)) return 0
    withStyle(style) { appendInline(text.substring(contentStart, end), linkColor) }
    return end + marker.length - start
}

private fun AnnotatedString.Builder.appendLink(text: String, start: Int, linkColor: Color): Int {
    val close = text.indexOf(']', start + 1)
    if (close == -1 || close + 1 >= text.length || text[close + 1] != '(') return 0
    val urlEnd = text.indexOf(')', close + 2)
    if (urlEnd == -1) return 0
    val label = text.substring(start + 1, close)
    val url = text.substring(close + 2, urlEnd)
    val styles = TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline))
    withLink(LinkAnnotation.Url(url, styles)) { appendInline(label, linkColor) }
    return urlEnd + 1 - start
}

private fun isBoundary(text: String, index: Int): Boolean {
    if (index < 0 || index >= text.length) return true
    return !text[index].isLetterOrDigit()
}
