package com.blazikener.devin.data

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class DraftPresenceTest {
    private val context = object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            super.getSharedPreferences("test.draft_presence.$name", mode)
    }
    private lateinit var store: SettingsStore

    @Before fun setup() {
        clean()
        store = SettingsStore(context)
        store.update { it.copy(defaultMode = "fast", defaultAcuLimit = 10) }
    }

    @After fun clean() {
        listOf("settings", "drafts", "outbox", "pins").forEach {
            context.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit()
        }
    }

    @Test fun accountDefaultsDoNotOccupyTheDraftSlot() {
        assertEquals("fast", store.newSessionDraft().mode)
        assertEquals("10", store.newSessionDraft().acu)
        assertFalse(store.hasNewSessionDraft())
    }

    @Test fun submittingSharedFormLeavesAnEmptySlotEvenWithDefaults() {
        val original = NewSessionDraft(prompt = "Original unsent prompt")
        store.saveNewSessionDraft(original)
        assertTrue(store.hasNewSessionDraft())
        store.saveNewSessionDraft(NewSessionDraft(prompt = "Different shared prompt"))
        store.saveNewSessionDraft(NewSessionDraft()) // Confirmed Create success.
        assertFalse(store.hasNewSessionDraft())
        store.saveNewSessionDraft(original) // Returning to the still-unsent parent.
        assertTrue(store.hasNewSessionDraft())
        assertEquals(original, store.newSessionDraft())
    }

    @Test fun legacyDraftCountsAsOccupiedUntilExplicitlyCleared() {
        context.getSharedPreferences("drafts", Context.MODE_PRIVATE).edit()
            .putString("__new__", "Unsent legacy prompt").commit()
        assertTrue(store.hasNewSessionDraft())
        assertEquals("Unsent legacy prompt", store.newSessionDraft().prompt)
        store.clearDrafts()
        assertFalse(store.hasNewSessionDraft())
    }
}
