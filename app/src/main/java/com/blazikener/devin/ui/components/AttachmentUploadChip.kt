package com.blazikener.devin.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
internal fun AttachmentUploadChip(upload: AttachmentUpload, onRemove: () -> Unit) {
    InputChip(
        selected = upload.error != null,
        onClick = onRemove,
        modifier = Modifier.widthIn(max = 300.dp),
        label = {
            Column {
                Text(upload.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                upload.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        leadingIcon = {
            if (upload.uploading) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
            else Icon(Icons.Outlined.Description, null, Modifier.size(16.dp))
        },
        trailingIcon = { Icon(Icons.Outlined.Close, "Remove ${upload.name}", Modifier.size(16.dp)) },
    )
}
