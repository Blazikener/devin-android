package com.blazikener.devin.data

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class DraftOwnershipTest {
    private val context = object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            super.getSharedPreferences("test.draft_ownership.$name", mode)
    }
    private lateinit var store: SettingsStore
    private val original = NewSessionDraft(prompt = "Original task", draftVersion = "original")
    private val newer = NewSessionDraft(prompt = "New shared task", draftVersion = "newer")

    @Before fun setup() { clean(); store = SettingsStore(context) }
    @After fun clean() {
        listOf("settings", "drafts", "outbox", "pins").forEach {
            context.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit()
        }
    }

    @Test fun olderSuccessNeverDeletesNewerSavedShare() = runBlocking {
        store.saveNewSessionDraft(original)
        store.createAttempts.write(CreateAttempt.Pending, original)
        store.saveNewSessionDraft(newer)
        store.createAttempts.write(CreateAttempt.Created("session"), original)
        assertEquals(newer, store.newSessionDraft())
        assertTrue(store.wasNewSessionDraftConsumed(original))
        assertFalse(store.wasNewSessionDraftConsumed(newer))
    }

    @Test fun clearOrSuccessConsumesSameVersionButNotIndependentParent() = runBlocking {
        store.saveNewSessionDraft(original)
        store.consumeNewSessionDraft(original)
        assertTrue(store.wasNewSessionDraftConsumed(original))
        assertFalse(store.hasNewSessionDraft())
        store.saveNewSessionDraft(newer)
        store.createAttempts.write(CreateAttempt.Pending, newer)
        store.createAttempts.write(CreateAttempt.Created("session"), newer)
        assertTrue(store.wasNewSessionDraftConsumed(newer))
        assertFalse(store.hasNewSessionDraft())
        val independent = original.copy(draftVersion = "independent")
        assertFalse(store.wasNewSessionDraftConsumed(independent))
        store.saveNewSessionDraft(independent)
        assertEquals(independent, store.newSessionDraft())
    }

    @Test fun delayedPendingWriteAndOldResultDoNotReplaceNewerWork() = runBlocking {
        store.saveNewSessionDraft(newer)
        store.createAttempts.write(CreateAttempt.Pending, original)
        assertEquals(newer, store.newSessionDraft())
        assertEquals(original, SettingsStore(context).createAttempts.pendingDraft())
        assertFalse(store.createAttempts.write(null, newer))
        assertTrue(store.createAttempts.write(null, original))
        store.createAttempts.write(CreateAttempt.Pending, newer)
        store.createAttempts.write(CreateAttempt.Created("old-session"), original)
        assertEquals(CreateAttempt.Pending, store.createAttempts.read())
        assertEquals(newer, store.newSessionDraft())
        assertFalse(store.createAttempts.write(null, original))
        assertEquals(CreateAttempt.Pending, SettingsStore(context).createAttempts.read())
    }

    @Test fun explicitClearOfAnOldFormDoesNotDeleteNewerDraft() {
        store.saveNewSessionDraft(newer)
        store.consumeNewSessionDraft(original)
        assertEquals(newer, store.newSessionDraft())
        assertTrue(store.wasNewSessionDraftConsumed(original))
    }
}
