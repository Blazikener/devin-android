package com.blazikener.devin.ui.create

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.blazikener.devin.data.CreateAttempt

internal fun CreateAttempt.retryDescription(): String = submitted?.request?.let { request ->
    buildList {
        add("Task: ${request.prompt}")
        add("Mode: ${request.devinMode ?: "Default"}")
        add("ACU limit: ${request.maxAcuLimit?.toString() ?: "Default"}")
        request.title?.let { add("Title: $it") }
        request.tags?.takeIf { it.isNotEmpty() }?.let { add("Tags: ${it.joinToString()}") }
        request.repos?.takeIf { it.isNotEmpty() }?.let { add("Repositories: ${it.joinToString()}") }
        request.playbookId?.let { add("Playbook: $it") }
        request.attachmentUrls?.takeIf { it.isNotEmpty() }?.let { add("Attached files: ${it.size}") }
        add("Create as: ${request.createAsUserId ?: "Current account"}")
    }.joinToString("\n\n")
} ?: "The original request is unavailable. Check Sessions before starting another task."

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CreateRecoveryWarning(
    attempt: CreateAttempt,
    onCheckRecents: () -> Unit,
    onDismiss: () -> Unit,
    onRetry: (() -> Unit)?,
    onClear: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    var confirmRetry by remember(attempt) { mutableStateOf(false) }
    var confirmClear by remember(attempt) { mutableStateOf(false) }
    Column(modifier) {
        Text("The previous session may already have started. Check Sessions before retrying.", color = MaterialTheme.colorScheme.error)
        attempt.submitted?.request?.prompt?.let { Text("Previous task: ${it.take(160)}", style = MaterialTheme.typography.bodyMedium) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onCheckRecents) { Text("Check Sessions") }
            if (onRetry != null && attempt.submitted != null) TextButton(onClick = { confirmRetry = true }) { Text("Review retry") }
            TextButton(onClick = onDismiss) { Text("Dismiss") }
            if (onClear != null) TextButton(onClick = { confirmClear = true }) { Text("Clear previous task") }
        }
    }
    if (confirmRetry && onRetry != null) AlertDialog(
        onDismissRequest = { confirmRetry = false },
        title = { Text("Retry this task?") },
        text = { Text("This can create a duplicate session. The original task and settings below will be sent, not your current edits.\n\n${attempt.retryDescription()}", Modifier.verticalScroll(rememberScrollState())) },
        confirmButton = { TextButton(onClick = { confirmRetry = false; onRetry() }) { Text("Retry anyway") } },
        dismissButton = { TextButton(onClick = { confirmRetry = false }) { Text("Cancel") } },
    )
    if (confirmClear && onClear != null) AlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { Text("Clear this previous task?") },
        text = { Text("This removes the saved task and its retry protection, not any session that already started. Your current edits are separate.\n\n${attempt.retryDescription()}", Modifier.verticalScroll(rememberScrollState())) },
        confirmButton = { TextButton(onClick = { confirmClear = false; onClear() }) { Text("Clear previous task") } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
    )
}
