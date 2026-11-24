package com.blazikener.devin.ui.create

import com.blazikener.devin.data.NewSessionDraft
import com.blazikener.devin.data.CreateAttempt
import com.blazikener.devin.data.withVersionFrom

private const val DEFAULT_MODE_OVERRIDE = "default"
private const val NO_MODE_OVERRIDE = "__none__"

internal fun createModeOverride(modeChosen: Boolean, chosenMode: String?): String? =
    if (modeChosen) chosenMode ?: DEFAULT_MODE_OVERRIDE else null

internal fun NewSessionDraft.withCreateArguments(prompt: String?, modeOverride: String?, attempts: List<CreateAttempt> = emptyList(), freshIncoming: Boolean = false): NewSessionDraft {
    val incoming = prompt?.trim()?.takeIf(String::isNotEmpty)
    // A recovery launch must not disguise a submitted prompt by appending saved context,
    // nor overwrite an unrelated unfinished draft merely to display its warning.
    if (incoming != null && attempts.any { it.matches(incoming) }) return this
    if (incoming != null && incoming == lastInitialPrompt && !freshIncoming) {
        // An unchanged Home handoff must not undo mode edits in Create. A deliberate new
        // Home mode still applies without re-appending its old task. Legacy markers have
        // no source-mode history: adopt it without guessing away the user's current mode.
        return when {
            modeOverride == null || modeOverride == lastInitialModeOverride -> this
            lastInitialModeOverride == null -> copy(lastInitialModeOverride = modeOverride)
            else -> copy(mode = modeOverride.takeUnless { it == DEFAULT_MODE_OVERRIDE }, lastInitialModeOverride = modeOverride)
        }
    }
    // Back from Create keeps both Home's text and the edited form. Reopening must not
    // append the same source again, even if the form changed or the process was recreated.
    val draft = if (incoming == null) this else {
        val combined = when {
            incoming == this.prompt.trim() -> this
            attempts.any { it.matches(this.prompt) } -> copy(prompt = incoming)
            else -> withShared(incoming)
        }
        if (freshIncoming) combined else combined.copy(lastInitialPrompt = incoming, lastInitialModeOverride = modeOverride ?: NO_MODE_OVERRIDE)
    }
    return if (modeOverride == null) draft else draft.copy(mode = modeOverride.takeUnless { it == DEFAULT_MODE_OVERRIDE })
}

internal fun NewSessionDraft.needsSharedChoice(prompt: String?, attempts: List<CreateAttempt> = emptyList(), freshIncoming: Boolean = false): Boolean {
    val incoming = prompt?.trim()?.takeIf(String::isNotEmpty) ?: return false
    if (attempts.any { it.matches(incoming) }) return false
    return this.prompt.isNotBlank() && this.prompt.trim() != incoming && (freshIncoming || lastInitialPrompt != incoming)
}

/** Do not apply any incoming setup until the user accepts the new task contribution. */
internal fun NewSessionDraft.seedCreateDraft(prompt: String?, modeOverride: String?, attempts: List<CreateAttempt> = emptyList(), freshIncoming: Boolean = false): NewSessionDraft =
    if (needsSharedChoice(prompt, attempts, freshIncoming)) this else withCreateArguments(prompt, modeOverride, attempts, freshIncoming).withVersionFrom(this)
