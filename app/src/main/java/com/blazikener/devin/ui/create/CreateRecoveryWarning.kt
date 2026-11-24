package com.blazikener.devin.ui.create

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.blazikener.devin.data.CreateAttempt
import com.blazikener.devin.data.CreateSessionRequest
import com.blazikener.devin.data.DEVIN_MODES

internal fun CreateSessionRequest.retrySettingsSummary(): String {
    val mode = DEVIN_MODES.firstOrNull { it.first == devinMode }?.second ?: devinMode ?: "Default"
    return "Max ACUs: ${maxAcuLimit ?: "not set"} · Mode: $mode"
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CreateRecoveryWarning(
    attempt: CreateAttempt,
    onCheckRecents: () -> Unit,
    onDismiss: () -> Unit,
    onRetry: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Text("The previous session may already have started. Check Recents before retrying.", color = MaterialTheme.colorScheme.error)
        val original = attempt.submitted?.request
        if (onRetry != null && original != null) {
            val style = MaterialTheme.typography.bodySmall
            Text("Retry resends the original prompt and settings.", style = style)
            Text("Original prompt: ${original.prompt}", style = style, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(original.retrySettingsSummary(), style = style)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onCheckRecents) { Text("Check Recents") }
            if (onRetry != null) TextButton(onClick = onRetry) { Text("Retry anyway") }
            TextButton(onClick = onDismiss) { Text("Dismiss") }
        }
    }
}
