package work.gadmin.finora.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import java.net.URI
import org.commonmark.ext.gfm.tables.*
import org.commonmark.node.*
import org.commonmark.node.Text as MarkdownText
import org.commonmark.parser.Parser

private val chatMarkdownParser =
    Parser.builder().extensions(listOf(TablesExtension.create())).build()

/** Browser links only. No script, file, intent, credential-bearing or protocol-relative URLs. */
fun safeChatLink(destination: String): String? = runCatching {
    if (destination.any { it.isISOControl() } || destination.contains('\\')) return null
    val uri = URI(destination)
    if (
        uri.scheme?.lowercase() !in setOf("https", "http") ||
            uri.host.isNullOrBlank() ||
            uri.userInfo != null
    )
        null
    else uri.toASCIIString()
}
    .getOrNull()

internal fun markdownChildren(node: Node): List<Node> = buildList {
    var child = node.firstChild
    while (child != null) {
        add(child)
        child = child.next
    }
}

/**
 * CommonMark is rendered as native text; HTML is never executed and images never fetch remotely.
 */
@Composable
fun ChatMarkdown(source: String, color: Color = Ink) {
    val document = remember(source) { chatMarkdownParser.parse(source.take(100_000)) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        markdownChildren(document).forEach { MarkdownBlock(it, color, 0) }
    }
}

@Composable
private fun MarkdownBlock(node: Node, color: Color, depth: Int) {
    if (depth > 16) return
    val inline = remember(node, color) { markdownInline(node, color) }
    when (node) {
        is org.commonmark.node.Paragraph ->
            Text(inline, color = color, style = MaterialTheme.typography.bodyMedium)
        is Heading ->
            Text(
                inline,
                color = color,
                style =
                    when (node.level) {
                        1 -> MaterialTheme.typography.headlineSmall
                        2 -> MaterialTheme.typography.titleLarge
                        else -> MaterialTheme.typography.titleMedium
                    },
                fontWeight = FontWeight.SemiBold,
            )
        is FencedCodeBlock -> MarkdownCode(node.literal, color)
        is IndentedCodeBlock -> MarkdownCode(node.literal, color)
        is ThematicBreak -> HorizontalDivider(Modifier.padding(vertical = 4.dp))
        is HtmlBlock,
        is HtmlInline -> Unit
        is BulletList,
        is OrderedList ->
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                markdownChildren(node).forEachIndexed { index, item ->
                    Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                        Text(
                            if (node is OrderedList) "${node.markerStartNumber + index}." else "•",
                            color = color,
                        )
                        Column(
                            Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            markdownChildren(item).forEach { MarkdownBlock(it, color, depth + 1) }
                        }
                    }
                }
            }
        is BlockQuote ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.width(3.dp).heightIn(min = 28.dp).background(Green))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    markdownChildren(node).forEach { MarkdownBlock(it, color, depth + 1) }
                }
            }
        is TableBlock ->
            Column(Modifier.horizontalScroll(rememberScrollState())) {
                markdownChildren(node).flatMap(::markdownChildren).forEach { row ->
                    Row(Modifier.height(IntrinsicSize.Min)) {
                        markdownChildren(row).filterIsInstance<TableCell>().forEach { cell ->
                            Surface(
                                color = if (cell.isHeader) SoftGreen else SurfaceColor,
                                modifier = Modifier.width(148.dp).fillMaxHeight(),
                            ) {
                                Text(
                                    markdownInline(cell, color),
                                    Modifier.padding(10.dp),
                                    color = color,
                                    fontWeight =
                                        if (cell.isHeader) FontWeight.SemiBold
                                        else FontWeight.Normal,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                    HorizontalDivider()
                }
            }
        else -> markdownChildren(node).forEach { MarkdownBlock(it, color, depth + 1) }
    }
}

@Composable
private fun MarkdownCode(code: String, color: Color) {
    Surface(color = SoftGreen, shape = RoundedCornerShape(10.dp)) {
        Text(
            code.trimEnd(),
            Modifier.horizontalScroll(rememberScrollState()).padding(12.dp),
            color = color,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

internal fun markdownInline(node: Node, color: Color): AnnotatedString = buildAnnotatedString {
    fun appendNode(current: Node, depth: Int) {
        if (depth > 32) return
        fun children() {
            markdownChildren(current).forEach { appendNode(it, depth + 1) }
        }
        when (current) {
            is MarkdownText -> append(current.literal)
            is SoftLineBreak -> append(" ")
            is HardLineBreak -> append("\n")
            is Code ->
                withStyle(
                    SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        background = color.copy(alpha = .08f),
                    )
                ) {
                    append(current.literal)
                }
            is StrongEmphasis -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { children() }
            is Emphasis -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { children() }
            is Link -> {
                val url = safeChatLink(current.destination)
                if (url == null) children()
                else
                    withLink(
                        LinkAnnotation.Url(
                            url,
                            TextLinkStyles(
                                SpanStyle(
                                    textDecoration = TextDecoration.Underline,
                                    fontWeight = FontWeight.Medium,
                                )
                            ),
                        )
                    ) {
                        children()
                    }
            }
            is HtmlInline,
            is HtmlBlock -> Unit
            else -> children()
        }
    }
    markdownChildren(node).forEach { appendNode(it, 0) }
}
