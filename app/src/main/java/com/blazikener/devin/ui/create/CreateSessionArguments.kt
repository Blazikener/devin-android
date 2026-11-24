package com.blazikener.devin.ui.create

import com.blazikener.devin.data.CreateAttempt
import com.blazikener.devin.data.NewSessionDraft

private const val DEFAULT_MODE_OVERRIDE = "default"

internal fun createModeOverride(modeChosen: Boolean, chosenMode: String?): String? =
    if (modeChosen) chosenMode ?: DEFAULT_MODE_OVERRIDE else null

internal fun NewSessionDraft.withCreateArguments(
    incoming: String?,
    modeOverride: String?,
    attempts: List<CreateAttempt> = emptyList(),
): NewSessionDraft {
    val draft = when {
        incoming.isNullOrBlank() || prompt.trim() == incoming.trim() -> this
        attempts.any { it.matches(incoming) || it.matches(prompt) } -> copy(prompt = incoming.trim())
        else -> withShared(incoming)
    }
    return if (modeOverride == null) draft else draft.copy(mode = modeOverride.takeUnless { it == DEFAULT_MODE_OVERRIDE })
}

internal fun NewSessionDraft.shouldPreserveOnRecovery(matching: CreateAttempt?): Boolean =
    matching != null && matching.sessionId == null && !isEmpty && !matching.matches(prompt)
