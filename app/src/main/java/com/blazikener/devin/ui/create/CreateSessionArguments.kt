package com.blazikener.devin.ui.create

import com.blazikener.devin.data.CreateAttempt
import com.blazikener.devin.data.CreateAttemptStore
import com.blazikener.devin.data.NewSessionDraft
import com.blazikener.devin.data.withVersionFrom

private const val DEFAULT_MODE_OVERRIDE = "default"

internal fun createModeOverride(modeChosen: Boolean, chosenMode: String?): String? =
    if (modeChosen) chosenMode ?: DEFAULT_MODE_OVERRIDE else null

internal fun CreateAttemptStore.discardCompletedForIncomingPrompt(prompt: String?) {
    if (prompt.isNullOrBlank()) return
    val completed = read() as? CreateAttempt.Created ?: return
    acknowledgeCreated(completed.sessionId)
}

internal fun NewSessionDraft.withCreateArguments(prompt: String?, modeOverride: String?): NewSessionDraft {
    val incoming = prompt?.trim()?.takeIf(String::isNotEmpty)
    // Back from Create keeps both Home's text and the edited form. Reopening must not
    // append the same source again, even if the form changed or the process was recreated.
    val draft = if (incoming == null || incoming == lastInitialPrompt) this else {
        val combined = if (incoming == this.prompt.trim()) this else withShared(incoming)
        combined.copy(lastInitialPrompt = incoming)
    }
    return if (modeOverride == null) draft else draft.copy(mode = modeOverride.takeUnless { it == DEFAULT_MODE_OVERRIDE })
}

internal fun NewSessionDraft.needsSharedChoice(prompt: String?): Boolean {
    val incoming = prompt?.trim()?.takeIf(String::isNotEmpty) ?: return false
    return this.prompt.isNotBlank() && this.prompt.trim() != incoming && lastInitialPrompt != incoming
}

/** Do not apply any incoming setup until the user accepts the new task contribution. */
internal fun NewSessionDraft.seedCreateDraft(prompt: String?, modeOverride: String?): NewSessionDraft =
    if (needsSharedChoice(prompt)) this else withCreateArguments(prompt, modeOverride).withVersionFrom(this)
