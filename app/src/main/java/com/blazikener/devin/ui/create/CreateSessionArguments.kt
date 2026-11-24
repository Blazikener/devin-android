package com.blazikener.devin.ui.create

import com.blazikener.devin.data.NewSessionDraft

private const val DEFAULT_MODE_OVERRIDE = "default"

internal fun createModeOverride(modeChosen: Boolean, chosenMode: String?): String? =
    if (modeChosen) chosenMode ?: DEFAULT_MODE_OVERRIDE else null

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
