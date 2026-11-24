package com.blazikener.devin.ui.create

import com.blazikener.devin.data.CreateAttempt
import com.blazikener.devin.data.NewSessionDraft

private const val DEFAULT_MODE_OVERRIDE = "default"

internal fun createModeOverride(modeChosen: Boolean, chosenMode: String?): String? =
    if (modeChosen) chosenMode ?: DEFAULT_MODE_OVERRIDE else null

internal fun NewSessionDraft.withCreateArguments(
    prompt: String?,
    modeOverride: String?,
    attempts: List<CreateAttempt> = emptyList(),
    freshIncoming: Boolean = false,
): NewSessionDraft {
    val incoming = prompt?.trim()?.takeIf(String::isNotEmpty)
    // Back from Create keeps both Home's text and the edited form. Reopening must not
    // append the same source again, even if the form changed or the process was recreated.
    val draft = when {
        incoming == null -> this
        // Home reopening the same handoff must retain later edits, even when those edits
        // now have an uncertain launch of their own. External shares are fresh inputs.
        incoming == lastInitialPrompt && !freshIncoming -> this
        // Never concatenate around a submitted task: that changes its fingerprint and
        // silently bypasses the shared duplicate guard. This takes precedence over metadata.
        attempts.any { it.matches(incoming) || it.matches(this.prompt) } ->
            copy(prompt = incoming, lastInitialPrompt = incoming)
        else -> {
            val combined = if (incoming == this.prompt.trim()) this else withShared(incoming)
            combined.copy(lastInitialPrompt = incoming)
        }
    }
    return if (modeOverride == null) draft else draft.copy(mode = modeOverride.takeUnless { it == DEFAULT_MODE_OVERRIDE })
}

internal fun NewSessionDraft.shouldPreserveOnRecovery(matching: CreateAttempt?): Boolean =
    matching != null && !isEmpty && !matching.matches(prompt)
