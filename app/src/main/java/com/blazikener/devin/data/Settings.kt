package com.blazikener.devin.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
) {
    val isEmpty: Boolean
        get() = prompt.isBlank() && title.isBlank() && tags.isBlank() && repos.isEmpty() && playbookId == null && attachments.isEmpty()

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
    fun draft(sessionId: String): String = drafts.getString(sessionId, "") ?: ""
    fun saveDraft(sessionId: String, text: String) {
        drafts.edit().apply { if (text.isBlank()) remove(sessionId) else putString(sessionId, text) }.apply()
    }

    fun newSessionDraft(defaults: AppSettings = value): NewSessionDraft {
        val fresh = NewSessionDraft(acu = defaults.defaultAcuLimit?.toString() ?: "", mode = defaults.defaultMode)
        val raw = drafts.getString(NEW_SESSION_KEY, null)?.takeIf { it.isNotBlank() } ?: return fresh
        // Older versions stored only the prompt as plain text.
        return runCatching { json.decodeFromString(NewSessionDraft.serializer(), raw) }.getOrElse { fresh.copy(prompt = raw) }
    }
    fun saveNewSessionDraft(d: NewSessionDraft) {
        drafts.edit().apply {
            if (d.isEmpty) remove(NEW_SESSION_KEY) else putString(NEW_SESSION_KEY, json.encodeToString(NewSessionDraft.serializer(), d))
        }.apply()
    }
    fun clearDrafts() = drafts.edit().clear().apply()

    private companion object { const val NEW_SESSION_KEY = "__new__" }

    private val pins = context.getSharedPreferences("pins", Context.MODE_PRIVATE)
    private val _pinned = MutableStateFlow(pins.getStringSet("ids", emptySet())!!.toSet())
    val pinned: StateFlow<Set<String>> = _pinned
    fun togglePin(id: String) {
        val s = _pinned.value.toMutableSet().apply { if (!add(id)) remove(id) }
        pins.edit().putStringSet("ids", s).apply()
        _pinned.value = s
    }
}
