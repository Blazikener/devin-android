package com.blazikener.devin.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.blazikener.devin.ui.components.DevinIconButton
import com.blazikener.devin.ui.components.MarkdownParser
import com.blazikener.devin.ui.components.MdBlock
import com.blazikener.devin.ui.theme.DevinTextStyles

@Composable
internal fun ReaderDocument(document: SavedDocument, blocks: List<MdBlock>?, plain: Boolean, state: LazyListState,
    modifier: Modifier = Modifier, onCopied: (Boolean) -> Unit,
) {
    val source = remember(document.version) { document.text.lines().chunked(24).map { it.joinToString("\n") } }
    SelectionContainer(modifier) {
        LazyColumn(state = state, contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (plain || blocks == null) {
                itemsIndexed(source) { _, text ->
                    Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.fillMaxWidth())
                }
            } else {
                itemsIndexed(blocks) { _, block -> ReaderBlock(block, onCopied) }
            }
            if (document.text.isEmpty()) item { Text("This document is empty.", style = MaterialTheme.typography.bodyLarge) }
        }
    }
}

@Composable
private fun ReaderBlock(block: MdBlock, onCopied: (Boolean) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val body = MaterialTheme.typography.bodyLarge
    fun inline(text: String) = MarkdownParser.inline(text, colors.surfaceContainerHighest, colors.primary)
    when (block) {
        is MdBlock.Heading -> Text(inline(block.text), style = when (block.level) {
            1 -> DevinTextStyles.documentHero
            2 -> MaterialTheme.typography.titleLarge
            else -> MaterialTheme.typography.titleMedium
        }, modifier = Modifier.fillMaxWidth().semantics { heading() })
        is MdBlock.Paragraph -> Text(inline(block.text), style = body)
        is MdBlock.Bullet -> Row(Modifier.padding(start = (block.indent.coerceAtMost(8) * 12).dp)) {
            Text(block.marker, style = body, modifier = Modifier.widthIn(min = 24.dp))
            Text(inline(block.text), style = body, modifier = Modifier.weight(1f))
        }
        is MdBlock.Quote -> Surface(color = colors.surfaceContainer, shape = RoundedCornerShape(12.dp)) {
            Text(inline(block.text), style = body, modifier = Modifier.padding(16.dp))
        }
        is MdBlock.Code -> {
            val clipboard = LocalClipboardManager.current
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(colors.surfaceContainerHighest)) {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(block.lang.ifBlank { "Code" }, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                    DevinIconButton("Copy ${block.lang.ifBlank { "document" }} code", Icons.Outlined.ContentCopy, {
                        onCopied(runCatching { clipboard.setText(AnnotatedString(block.code)) }.isSuccess)
                    })
                }
                Text(block.code, style = DevinTextStyles.codeBlock, softWrap = false,
                    modifier = Modifier.horizontalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 16.dp))
            }
        }
        is MdBlock.Table -> {
            // Fixed cell widths and single-line rows keep columns aligned; overflow stays inside the document.
            Column(Modifier.horizontalScroll(rememberScrollState()).border(1.dp, colors.outline, RoundedCornerShape(8.dp))) {
                (listOf(block.header) + block.rows).forEachIndexed { rowIndex, cells ->
                    Row(Modifier.background(if (rowIndex == 0) colors.surfaceContainerHighest else colors.surface)) {
                        block.header.indices.forEach { column ->
                            Text(inline(cells.getOrElse(column) { "" }), style = body.copy(fontWeight = if (rowIndex == 0) FontWeight.Medium else FontWeight.Normal),
                                softWrap = false, maxLines = 1, modifier = Modifier.width(240.dp).padding(12.dp))
                        }
                    }
                    HorizontalDivider(color = colors.outlineVariant)
                }
            }
        }
        MdBlock.Rule -> HorizontalDivider(color = colors.outline)
    }
}
