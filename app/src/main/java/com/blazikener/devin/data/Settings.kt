package com.blazikener.devin.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class DraftAttachment(val name: String, val url: String)

/** Everything typed into the new-session form, kept until the session is started or cleared. */
@Serializable
data class NewSessionDraft(
    val prompt: String = "",
    val title: String = "",
    val tags: String = "",
    val acu: String = "",
    val mode: String? = null,
    val repos: List<String> = emptyList(),
    @SerialName("playbook_id") val playbookId: String? = null,
    val attachments: List<DraftAttachment> = emptyList(),
    // Idempotency marker for a Home/share argument already incorporated into this draft.
    @SerialName("last_initial_prompt") val lastInitialPrompt: String? = null,
    @SerialName("draft_version") val draftVersion: String? = null,
) {
    val isEmpty: Boolean
        get() = prompt.isBlank() && title.isBlank() && tags.isBlank() && acu.isBlank() && mode == null && repos.isEmpty() && playbookId == null && attachments.isEmpty()

    /** Shared text is added to whatever is already drafted so an unfinished prompt is never overwritten. */
    fun withShared(text: String?): NewSessionDraft =
        if (text.isNullOrBlank()) this else copy(prompt = listOf(prompt.trimEnd(), text.trim()).filter { it.isNotEmpty() }.joinToString("\n\n"))
}

enum class ThemeMode { System, Light, Dark }

data class AppSettings(
    val notifyNeedsYou: Boolean = true,
    val notifyFinished: Boolean = true,
    val notifyMessages: Boolean = true,
    val pollMinutes: Int = 15,
    val theme: ThemeMode = ThemeMode.System,
    val dynamicColor: Boolean = false,
    val defaultMode: String? = null,
    val defaultAcuLimit: Int? = null,
    val createAsUserId: String? = null,
    val enterToSend: Boolean = false,
)

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<AppSettings> = _settings
    val value: AppSettings get() = _settings.value

    fun update(f: (AppSettings) -> AppSettings) {
        val s = f(_settings.value)
        prefs.edit()
            .putBoolean("notify_needs_you", s.notifyNeedsYou)
            .putBoolean("notify_finished", s.notifyFinished)
            .putBoolean("notify_messages", s.notifyMessages)
            .putInt("poll_minutes", s.pollMinutes)
            .putString("theme", s.theme.name)
            .putBoolean("dynamic_color", s.dynamicColor)
            .putString("default_mode", s.defaultMode)
            .putInt("default_acu", s.defaultAcuLimit ?: -1)
            .putString("create_as", s.createAsUserId)
            .putBoolean("enter_to_send", s.enterToSend)
            .apply()
        _settings.value = s
    }

    private fun read() = AppSettings(
        notifyNeedsYou = prefs.getBoolean("notify_needs_you", true),
        notifyFinished = prefs.getBoolean("notify_finished", true),
        notifyMessages = prefs.getBoolean("notify_messages", true),
        pollMinutes = prefs.getInt("poll_minutes", 15),
        theme = runCatching { ThemeMode.valueOf(prefs.getString("theme", null) ?: "System") }.getOrDefault(ThemeMode.System),
        dynamicColor = prefs.getBoolean("dynamic_color", false),
        defaultMode = prefs.getString("default_mode", null),
        defaultAcuLimit = prefs.getInt("default_acu", -1).takeIf { it > 0 },
        createAsUserId = prefs.getString("create_as", null)?.takeIf { it.isNotBlank() },
        enterToSend = prefs.getBoolean("enter_to_send", false),
    )

    // Per-session drafts survive process death so a half-written reply is never lost.
    private val drafts = context.getSharedPreferences("drafts", Context.MODE_PRIVATE)
    private val draftLock = Any()
    fun draft(sessionId: String): String = drafts.getString(sessionId, "") ?: ""
    fun saveDraft(sessionId: String, text: String) {
        drafts.edit().apply { if (text.isBlank()) remove(sessionId) else putString(sessionId, text) }.apply()
    }

    /** Storage presence, independent of the account defaults supplied to a fresh form. */
    fun hasNewSessionDraft(): Boolean = drafts.contains(NEW_SESSION_KEY) ||
        !drafts.getString(LEGACY_NEW_SESSION_KEY, null).isNullOrBlank()

    fun newSessionDraft(defaults: AppSettings = value): NewSessionDraft {
        val fresh = NewSessionDraft(acu = defaults.defaultAcuLimit?.toString() ?: "", mode = defaults.defaultMode)
        drafts.getString(NEW_SESSION_KEY, null)?.let { raw ->
            runCatching { json.decodeFromString(NewSessionDraft.serializer(), raw) }.getOrNull()?.let { return it }
        }
        // Older versions stored only the prompt, as plain text under another key.
        return fresh.copy(prompt = drafts.getString(LEGACY_NEW_SESSION_KEY, "").orEmpty())
    }
    fun saveNewSessionDraft(d: NewSessionDraft) {
        synchronized(draftLock) { drafts.edit().writeNewSessionDraft(d).apply() }
    }

    internal fun wasNewSessionDraftConsumed(draft: NewSessionDraft): Boolean =
        draft.versionKey in drafts.getStringSet(CONSUMED_DRAFTS_KEY, emptySet()).orEmpty()

    /** Clear/success consumes only this version, never a newer shared task in the global slot. */
    internal fun consumeNewSessionDraft(draft: NewSessionDraft) = synchronized(draftLock) {
        drafts.edit().consumeDraft(draft).apply()
    }

    private fun SharedPreferences.Editor.consumeDraft(draft: NewSessionDraft) = apply {
        putStringSet(CONSUMED_DRAFTS_KEY, drafts.getStringSet(CONSUMED_DRAFTS_KEY, emptySet()).orEmpty() + draft.versionKey)
        if (hasNewSessionDraft() && newSessionDraft().versionKey == draft.versionKey) {
            remove(NEW_SESSION_KEY)
            remove(LEGACY_NEW_SESSION_KEY)
        }
    }

    private fun SharedPreferences.Editor.writeNewSessionDraft(d: NewSessionDraft) = apply {
        remove(LEGACY_NEW_SESSION_KEY)
        if (d.isEmpty) remove(NEW_SESSION_KEY) else putString(NEW_SESSION_KEY, json.encodeToString(NewSessionDraft.serializer(), d))
    }

    internal val createAttempts: CreateAttemptStore = object : CreateAttemptStore {
        override fun isConsumed(draft: NewSessionDraft) = wasNewSessionDraftConsumed(draft)

        override fun pendingDraft(): NewSessionDraft? = synchronized(draftLock) {
            if (read() != CreateAttempt.Pending) return@synchronized null
            drafts.getString(CREATE_ATTEMPT_DRAFT_KEY, null)?.let { raw ->
                runCatching { json.decodeFromString(NewSessionDraft.serializer(), raw) }.getOrNull()
            } ?: newSessionDraft().takeIf { hasNewSessionDraft() }
        }

        override fun read(): CreateAttempt? = when (val value = drafts.getString(CREATE_ATTEMPT_KEY, null)) {
            null -> null
            "" -> CreateAttempt.Pending
            else -> CreateAttempt.Created(value)
        }

        override suspend fun write(attempt: CreateAttempt?, draft: NewSessionDraft?): Boolean =
            withContext(Dispatchers.IO) {
                synchronized(draftLock) {
                    val ownsAttempt = draft == null || drafts.getString(CREATE_ATTEMPT_VERSION_KEY, null) == draft.versionKey
                    if (attempt == null && !ownsAttempt) return@synchronized false
                    if (attempt == CreateAttempt.Pending && read() == CreateAttempt.Pending &&
                        drafts.contains(CREATE_ATTEMPT_VERSION_KEY) && !ownsAttempt) return@synchronized false
                    drafts.edit().apply {
                        when (attempt) {
                            null -> if (ownsAttempt) {
                                remove(CREATE_ATTEMPT_KEY)
                                remove(CREATE_ATTEMPT_VERSION_KEY)
                                remove(CREATE_ATTEMPT_DRAFT_KEY)
                            }
                            CreateAttempt.Pending -> {
                                putString(CREATE_ATTEMPT_KEY, "")
                                putString(CREATE_ATTEMPT_VERSION_KEY, draft?.versionKey)
                                if (draft != null) putString(CREATE_ATTEMPT_DRAFT_KEY, json.encodeToString(NewSessionDraft.serializer(), draft))
                                // A newer share may have saved while this POST was being scheduled.
                                if (draft != null && (!hasNewSessionDraft() || newSessionDraft().versionKey == draft.versionKey)) {
                                    writeNewSessionDraft(draft)
                                }
                            }
                            is CreateAttempt.Created -> {
                                if (ownsAttempt) {
                                    putString(CREATE_ATTEMPT_KEY, attempt.sessionId)
                                    remove(CREATE_ATTEMPT_DRAFT_KEY)
                                }
                                if (draft != null) consumeDraft(draft)
                            }
                        }
                    }.commit()
                }
            }

        override fun acknowledgeCreated(sessionId: String) {
            synchronized(draftLock) {
                if (read() == CreateAttempt.Created(sessionId)) drafts.edit()
                    .remove(CREATE_ATTEMPT_KEY).remove(CREATE_ATTEMPT_VERSION_KEY).remove(CREATE_ATTEMPT_DRAFT_KEY).apply()
            }
        }
    }
    fun clearDrafts() { drafts.edit().clear().apply(); outbox.edit().clear().apply() }

    // Messages that failed to send, as JSON per session, so they can still be retried after leaving the chat.
    private val outbox = context.getSharedPreferences("outbox", Context.MODE_PRIVATE)
    fun outbox(sessionId: String): String? = outbox.getString(sessionId, null)
    fun saveOutbox(sessionId: String, value: String?) {
        outbox.edit().apply { if (value == null) remove(sessionId) else putString(sessionId, value) }.apply()
    }

    private companion object {
        const val NEW_SESSION_KEY = "__new_session__"
        const val LEGACY_NEW_SESSION_KEY = "__new__"
        const val CREATE_ATTEMPT_KEY = "__new_session_attempt__"
        const val CREATE_ATTEMPT_VERSION_KEY = "__new_session_attempt_version__"
        const val CREATE_ATTEMPT_DRAFT_KEY = "__new_session_attempt_draft__"
        const val CONSUMED_DRAFTS_KEY = "__consumed_new_session_drafts__"
    }

    private val pins = context.getSharedPreferences("pins", Context.MODE_PRIVATE)
    private val _pinned = MutableStateFlow(pins.getStringSet("ids", emptySet())!!.toSet())
    val pinned: StateFlow<Set<String>> = _pinned
    fun togglePin(id: String) {
        val s = _pinned.value.toMutableSet().apply { if (!add(id)) remove(id) }
        pins.edit().putStringSet("ids", s).apply()
        _pinned.value = s
    }
}
