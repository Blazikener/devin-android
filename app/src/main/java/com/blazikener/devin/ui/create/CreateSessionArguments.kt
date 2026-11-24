package com.blazikener.devin.ui.create

import com.blazikener.devin.data.NewSessionDraft

private const val DEFAULT_MODE_OVERRIDE = "default"

internal fun createModeOverride(modeChosen: Boolean, chosenMode: String?): String? =
    if (modeChosen) chosenMode ?: DEFAULT_MODE_OVERRIDE else null

internal fun NewSessionDraft.withCreateArguments(prompt: String?, modeOverride: String?): NewSessionDraft {
    val draft = withShared(prompt)
    return if (modeOverride == null) draft else draft.copy(mode = modeOverride.takeUnless { it == DEFAULT_MODE_OVERRIDE })
}
