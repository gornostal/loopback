package name.gornostal.loopback.ui

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownTable
import com.mikepenz.markdown.compose.elements.MarkdownTableHeader
import com.mikepenz.markdown.compose.elements.MarkdownTableRow
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.elements.MarkdownCheckBox
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.markdownDimens
import com.mikepenz.markdown.model.rememberMarkdownState

/**
 * GFM markdown (mikepenz/multiplatform-markdown-renderer) styled to match the app.
 * Tables scroll horizontally once their columns don't fit; cells wrap instead of ellipsizing.
 */
@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
) {
    // Request context is small, so parse synchronously and skip the empty "loading" frame.
    val state = rememberMarkdownState(markdown, immediate = true)
    val colors = MaterialTheme.colorScheme
    val type = MaterialTheme.typography
    val textColor = color.takeOrElse { LocalContentColor.current }

    Markdown(
        markdownState = state,
        modifier = modifier,
        colors = markdownColor(
            text = textColor,
            codeBackground = colors.surfaceVariant,
            tableBackground = colors.surfaceVariant.copy(alpha = 0.5f),
        ),
        typography = markdownTypography(
            h1 = type.titleLarge,
            h2 = type.titleMedium,
            h3 = type.titleSmall,
            h4 = type.titleSmall,
            h5 = type.titleSmall,
            h6 = type.titleSmall,
            table = type.bodyMedium,
            textLink = TextLinkStyles(SpanStyle(color = colors.primary)),
        ),
        dimens = markdownDimens(
            tableCellWidth = 140.dp,
            tableCellPadding = 10.dp,
        ),
        components = markdownComponents(
            checkbox = { MarkdownCheckBox(it.content, it.node, it.typography.text) },
            table = { model ->
                MarkdownTable(
                    content = model.content,
                    node = model.node,
                    style = model.typography.table,
                    headerBlock = { content, header, tableWidth, style ->
                        MarkdownTableHeader(
                            content = content,
                            header = header,
                            tableWidth = tableWidth,
                            style = style,
                            verticalAlignment = Alignment.Top,
                            maxLines = Int.MAX_VALUE,
                            overflow = TextOverflow.Clip,
                        )
                    },
                    rowBlock = { content, row, tableWidth, style ->
                        MarkdownTableRow(
                            content = content,
                            header = row,
                            tableWidth = tableWidth,
                            style = style,
                            verticalAlignment = Alignment.Top,
                            maxLines = Int.MAX_VALUE,
                            overflow = TextOverflow.Clip,
                        )
                    },
                )
            },
        ),
    )
}
