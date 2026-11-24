package com.blazikener.devin.data

import java.security.MessageDigest
import java.util.UUID

/** Metadata is not task content. Reopening the same contribution keeps its version. */
internal fun NewSessionDraft.sameContentAs(other: NewSessionDraft): Boolean =
    copy(draftVersion = null, lastInitialPrompt = null, lastInitialModeOverride = null) ==
        other.copy(draftVersion = null, lastInitialPrompt = null, lastInitialModeOverride = null)

internal fun NewSessionDraft.withVersionFrom(previous: NewSessionDraft): NewSessionDraft =
    copy(draftVersion = if (sameContentAs(previous)) previous.draftVersion else UUID.randomUUID().toString())

/** Old saved forms have no UUID; use a stable content key until their first edit. */
internal val NewSessionDraft.versionKey: String
    get() = draftVersion ?: MessageDigest.getInstance("SHA-256")
        .digest(json.encodeToString(NewSessionDraft.serializer(), copy(draftVersion = null, lastInitialPrompt = null, lastInitialModeOverride = null)).toByteArray())
        .joinToString("") { "%02x".format(it) }
