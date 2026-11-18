package com.blazikener.devin.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
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

/** Markdown blocks we render. Kept tiny on purpose: Devin's messages use a small subset. */
sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Paragraph(val text: String) : MdBlock
    data class Code(val lang: String, val code: String) : MdBlock
    data class Bullet(val indent: Int, val marker: String, val text: String) : MdBlock
    data class Quote(val text: String) : MdBlock
    data class Table(val header: List<String>, val rows: List<List<String>>) : MdBlock
    data object Rule : MdBlock
}

object MarkdownParser {
    private val ordered = Regex("""^(\s*)(\d+)[.)]\s+(.*)$""")
    private val bullet = Regex("""^(\s*)[-*+]\s+(.*)$""")
    private val heading = Regex("""^(#{1,6})\s+(.*)$""")
    private val tableSeparator = Regex("""^\|?\s*:?-+:?\s*(\|\s*:?-+:?\s*)*\|?$""")

    private val unescapedPipe = Regex("""(?<!\\)\|""")

    private fun cells(row: String): List<String> =
        row.trim().removePrefix("|").let { if (it.endsWith("|") && !it.endsWith("\\|")) it.dropLast(1) else it }
            .split(unescapedPipe).map { it.trim().replace("\\|", "|") }

    fun parse(src: String): List<MdBlock> {
        val out = mutableListOf<MdBlock>()
        val para = StringBuilder()
        fun flush() { if (para.isNotBlank()) out += MdBlock.Paragraph(para.toString().trim()); para.clear() }
        val lines = src.replace("\r\n", "\n").split('\n')
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trimStart()
            when {
                trimmed.startsWith("```") -> {
                    flush()
                    val lang = trimmed.removePrefix("```").trim()
                    val code = StringBuilder()
                    i++
                    while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                        code.append(lines[i]).append('\n'); i++
                    }
                    out += MdBlock.Code(lang, code.toString().trimEnd('\n'))
                }
                trimmed.isEmpty() -> flush()
                heading.matches(trimmed) -> {
                    flush(); val m = heading.find(trimmed)!!
                    out += MdBlock.Heading(m.groupValues[1].length, m.groupValues[2])
                }
                trimmed.matches(Regex("""^([-*_])(\s*\1){2,}\s*$""")) -> { flush(); out += MdBlock.Rule }
                trimmed.startsWith("|") && i + 1 < lines.size && tableSeparator.matches(lines[i + 1].trim()) -> {
                    flush()
                    val header = cells(trimmed)
                    val rows = mutableListOf<List<String>>()
                    i += 2
                    while (i < lines.size && lines[i].trimStart().startsWith("|")) {
                        val row = cells(lines[i])
                        rows += List(header.size) { row.getOrElse(it) { "" } }
                        i++
                    }
                    i--
                    out += MdBlock.Table(header, rows)
                }
                trimmed.startsWith(">") -> {
                    flush()
                    val quote = StringBuilder(trimmed.removePrefix(">").trim())
                    while (i + 1 < lines.size && lines[i + 1].trimStart().startsWith(">")) {
                        i++
                        quote.append('\n').append(lines[i].trimStart().removePrefix(">").trim())
                    }
                    out += MdBlock.Quote(quote.toString().trim())
                }
                bullet.matches(line) -> {
                    flush(); val m = bullet.find(line)!!
                    out += MdBlock.Bullet(m.groupValues[1].length / 2, "•", m.groupValues[2])
                }
                ordered.matches(line) -> {
                    flush(); val m = ordered.find(line)!!
                    out += MdBlock.Bullet(m.groupValues[1].length / 2, m.groupValues[2] + ".", m.groupValues[3])
                }
                else -> { if (para.isNotEmpty()) para.append('\n'); para.append(line) }
            }
            i++
        }
        flush()
        return out
    }

    private val inline = Regex(
        """(`[^`]+`)|(\*\*[^*]+\*\*)|(__[^_]+__)|(\[[^\]]+]\([^)\s]+\))|(https?://[^\s<>()]+[^\s<>().,;:!?'"])|(\*[^*\s][^*]*\*)|(~~[^~]+~~)"""
    )

    fun inline(text: String, codeBg: Color, link: Color): AnnotatedString = buildAnnotatedString {
        var last = 0
        for (m in inline.findAll(text)) {
            append(text.substring(last, m.range.first))
            val t = m.value
            when {
                t.startsWith("`") -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBg, fontSize = 13.sp)) { append(t.trim('`')) }
                t.startsWith("**") || t.startsWith("__") -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(t.substring(2, t.length - 2)) }
                t.startsWith("~~") -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { append(t.substring(2, t.length - 2)) }
                t.startsWith("[") -> {
                    val label = t.substring(1, t.indexOf("]("))
                    val url = t.substring(t.indexOf("](") + 2, t.length - 1)
                    withLink(LinkAnnotation.Url(url, TextLinkStyles(SpanStyle(color = link)))) { append(label) }
                }
                t.startsWith("http") -> withLink(LinkAnnotation.Url(t, TextLinkStyles(SpanStyle(color = link)))) { append(t) }
                t.startsWith("*") -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(t.trim('*')) }
                else -> append(t)
            }
            last = m.range.last + 1
        }
        append(text.substring(last))
    }
}

@Composable
fun Markdown(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurface) {
    val blocks = remember(text) { MarkdownParser.parse(text) }
    val codeBg = MaterialTheme.colorScheme.surfaceContainerHighest
    val link = MaterialTheme.colorScheme.primary
    val body = MaterialTheme.typography.bodyMedium.copy(color = color, lineHeight = 21.sp)
    SelectionContainer(modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (b in blocks) when (b) {
                is MdBlock.Heading -> Text(
                    MarkdownParser.inline(b.text, codeBg, link),
                    style = (if (b.level <= 2) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleSmall).copy(color = color),
                )
                is MdBlock.Paragraph -> Text(MarkdownParser.inline(b.text, codeBg, link), style = body)
                is MdBlock.Bullet -> Row(Modifier.padding(start = (b.indent * 14).dp)) {
                    Text(b.marker, style = body, modifier = Modifier.width(if (b.marker == "•") 14.dp else 22.dp))
                    Text(MarkdownParser.inline(b.text, codeBg, link), style = body)
                }
                is MdBlock.Quote -> Row(Modifier.height(IntrinsicSize.Min)) {
                    Box(Modifier.width(3.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outline))
                    Text(MarkdownParser.inline(b.text, codeBg, link), style = body.copy(color = color.copy(alpha = 0.75f)), modifier = Modifier.padding(start = 8.dp))
                }
                is MdBlock.Code -> CodeBlock(b)
                is MdBlock.Table -> MdTable(b, body, codeBg, link)
                MdBlock.Rule -> Box(Modifier.fillMaxWidth().padding(vertical = 4.dp).size(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
            }
        }
    }
}

@Composable
private fun MdTable(b: MdBlock.Table, body: TextStyle, codeBg: Color, link: Color) {
    val border = MaterialTheme.colorScheme.outlineVariant
    // Column-major with single-line cells so every row lines up; wide tables scroll sideways.
    Row(
        Modifier.horizontalScroll(rememberScrollState())
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, border, RoundedCornerShape(8.dp))
    ) {
        b.header.forEachIndexed { col, title ->
            Column {
                Text(
                    MarkdownParser.inline(title, codeBg, link),
                    style = body.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1, softWrap = false,
                    modifier = Modifier.background(codeBg).padding(horizontal = 10.dp, vertical = 6.dp),
                )
                for (row in b.rows) {
                    HorizontalDivider(color = border)
                    Text(
                        MarkdownParser.inline(row[col], codeBg, link),
                        style = body, maxLines = 1, softWrap = false,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun CodeBlock(b: MdBlock.Code) {
    val clipboard = LocalClipboardManager.current
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest)
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(b.lang.ifBlank { "code" }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            IconButton(onClick = { clipboard.setText(AnnotatedString(b.code)) }, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Outlined.ContentCopy, contentDescription = "Copy code", modifier = Modifier.size(16.dp))
            }
        }
        Text(
            b.code,
            fontFamily = FontFamily.Monospace, fontSize = 12.5.sp, lineHeight = 18.sp,
            modifier = Modifier.horizontalScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
        )
    }
}
