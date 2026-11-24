package com.blazikener.devin.ui.session

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.blazikener.devin.data.SessionAttachment
import com.blazikener.devin.ui.theme.DevinDimens

internal fun SessionAttachment.displayType(): String = when {
    name.endsWith(".md", true) || contentType?.contains("markdown") == true -> "Markdown"
    name.endsWith(".pdf", true) || contentType == "application/pdf" -> "PDF"
    contentType?.startsWith("text/") == true || name.endsWith(".txt", true) -> "Plain text"
    contentType?.startsWith("image/") == true -> "Image"
    else -> "File"
}

@Composable
internal fun SessionFileCard(file: SessionAttachment, onOpen: () -> Unit) {
    val type = file.displayType()
    Surface(
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp).testTag("file-${file.id}")
            .semantics(mergeDescendants = true) { onClick(label = "Open ${file.name}", action = null) },
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Outlined.Description, null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(file.name, style = MaterialTheme.typography.titleSmall)
                Text("$type · ${if (type == "Markdown" || type == "Plain text") "Tap to read" else "Open options"}", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun SessionFiles(index: FilesIndex, list: LazyListState, retry: () -> Unit,
    onChat: () -> Unit, onOpen: (SessionAttachment) -> Unit,
) {
    LazyColumn(state = list, modifier = Modifier.fillMaxSize().testTag("files"),
        contentPadding = PaddingValues(DevinDimens.screenPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item(key = "heading") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Files shared in this session", style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f).semantics { heading() })
                TextButton(onClick = retry, enabled = !index.refreshing) { Text("Refresh") }
            }
        }
        if (index.refreshing) item(key = "loading") {
            Column(Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(if (index.saved == null) "Loading files…" else "Refreshing files…",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (index.error != null) item(key = "error") {
            FilesState(
                title = when {
                    index.offline && index.saved != null -> "Offline — saved file list"
                    index.offline -> "You’re offline"
                    index.saved != null -> "Couldn’t refresh files"
                    else -> "Couldn’t load files"
                },
                detail = when {
                    index.offline && index.rows.isNotEmpty() -> "These are the last saved files. A file may need a connection to open."
                    index.offline && index.saved != null -> "The saved list has no files. Connect and retry to check for new files."
                    index.offline -> "No file list is saved on this device. Connect and retry."
                    index.saved != null -> "Your saved list is still here. ${index.error}"
                    else -> index.error
                }, offline = index.offline, retry = retry, retryEnabled = !index.refreshing,
            )
        } else if (index.fromCache && !index.refreshing) item(key = "saved") {
            Text("Saved file list", style = MaterialTheme.typography.bodySmall)
        }
        items(index.rows, key = { "file-${it.id}" }) { file -> SessionFileCard(file) { onOpen(file) } }
        if (index.confirmedEmpty) item(key = "empty") {
            Column(Modifier.fillMaxWidth().padding(vertical = 48.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Icon(Icons.Outlined.Description, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
                Text("No shared files yet", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                Text("Files shared by you or Devin will appear here.", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = onChat) { Text("Back to Chat") }
            }
        }
        if (index.rows.isNotEmpty()) item(key = "web-hint") {
            Text("Looking for a web document? Open its link from the conversation.",
                Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun FilesState(title: String, detail: String, offline: Boolean, retry: () -> Unit, retryEnabled: Boolean) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(if (offline) Icons.Outlined.CloudOff else Icons.Outlined.ErrorOutline, null)
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            Text(detail, style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = retry, enabled = retryEnabled) { Text("Retry") }
        }
    }
}
