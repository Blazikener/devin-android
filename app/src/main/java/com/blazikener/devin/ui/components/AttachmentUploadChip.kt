package com.blazikener.devin.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
internal fun AttachmentUploadChip(upload: AttachmentUpload, compact: Boolean = false, onRemove: () -> Unit) {
    AssistChip(
        onClick = onRemove,
        modifier = Modifier.widthIn(max = 300.dp).heightIn(min = 48.dp).semantics {
            contentDescription = "Remove ${upload.name}"
            // TalkBack inserts its own separator between state and action.
            stateDescription = upload.error?.trimEnd { it.isWhitespace() || it == '.' }
                ?: if (upload.uploading) "Uploading" else "Ready"
        },
        label = {
            // The action's description/state above already include this information.
            Column(Modifier.clearAndSetSemantics { }) {
                Text(if (compact && upload.error != null) "Failed: ${upload.name}" else upload.name,
                    color = if (compact && upload.error != null) MaterialTheme.colorScheme.error else LocalContentColor.current,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                // Keep the full error in the spoken state, but reserve room for
                // the editor/actions above a landscape IME at large font sizes.
                if (!compact) upload.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        leadingIcon = {
            if (upload.uploading) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
            else Icon(Icons.Outlined.Description, null, Modifier.size(16.dp))
        },
        trailingIcon = { Icon(Icons.Outlined.Close, null, Modifier.size(16.dp)) },
    )
}
