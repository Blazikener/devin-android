package com.blazikener.devin.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.blazikener.devin.data.CreateSnapshot
import com.blazikener.devin.data.DEVIN_MODES

/** Review the immutable submitted request, not the current editor or account defaults. */
@Composable
internal fun CreateRequestDetails(snapshot: CreateSnapshot, modifier: Modifier = Modifier) {
    val request = snapshot.request
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(request.prompt, style = MaterialTheme.typography.bodyLarge)
        request.title?.let { Text("Title: $it") }
        Text("Mode: ${DEVIN_MODES.firstOrNull { it.first == request.devinMode }?.second ?: request.devinMode ?: "Default"}")
        Text("ACU limit: ${request.maxAcuLimit?.toString() ?: "Default"}")
        Text("Run as: ${request.createAsUserId ?: "Signed-in user"}")
        request.tags?.takeIf { it.isNotEmpty() }?.let { Text("Tags: ${it.joinToString(", ")}") }
        Text("Repositories: ${request.repos?.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: "None selected"}")
        Text("Playbook: ${request.playbookId ?: "None selected"}")
        val files = request.attachmentUrls.orEmpty()
        Text("Attachments: ${files.size}")
        files.forEachIndexed { index, url ->
            Text(snapshot.draft.attachments.firstOrNull { it.url == url }?.name ?: "File ${index + 1}")
        }
    }
}
