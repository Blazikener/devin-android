package com.blazikener.devin.data

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
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
    private fun pending(draft: NewSessionDraft, acu: Int? = null) = CreateAttempt(
        createFingerprint(draft.prompt), 1,
        submitted = CreateSnapshot(draft.toCreateRequest(null).let {
            if (acu == null) it else it.copy(maxAcuLimit = acu)
        }, draft, draft.versionKey),
    )
    @Before fun setup() { clean(); store = SettingsStore(context) }
    @After fun clean() {
        listOf("settings", "drafts", "outbox", "pins").forEach {
            context.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit()
        }
    }

    @Test fun olderSuccessNeverDeletesNewerSavedShare() = runBlocking {
        store.saveNewSessionDraft(original)
        val attempt = pending(original)
        store.createAttempts.write(listOf(attempt))
        store.saveNewSessionDraft(newer)
        store.createAttempts.write(listOf(attempt.copy(sessionId = "session", submitted = null)), original.versionKey)
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
        store.createAttempts.write(listOf(pending(newer).copy(sessionId = "session", submitted = null)), newer.versionKey)
        assertTrue(store.wasNewSessionDraftConsumed(newer))
        assertFalse(store.hasNewSessionDraft())
        val independent = original.copy(draftVersion = "independent")
        assertFalse(store.wasNewSessionDraftConsumed(independent))
        store.saveNewSessionDraft(independent)
        assertEquals(independent, store.newSessionDraft())
    }

    @Test fun multipleSnapshotsSurviveRestoreRetryAndStaleAcknowledgement() = runBlocking {
        store.saveNewSessionDraft(newer)
        val older = pending(original, acu = 20)
        val recent = pending(newer)
        store.createAttempts.write(listOf(recent, older))
        assertEquals(listOf(recent, older), SettingsStore(context).createAttempts.read())
        var sent: CreateSessionRequest? = null
        val gate = CreateSessionGate(store.createAttempts, { sent = it; Session("old-session") })
        assertEquals(CreateResult.Open("old-session"), gate.retryAnyway(older))
        assertEquals(20, sent?.maxAcuLimit)
        assertEquals(original.prompt, sent?.prompt)
        assertEquals(newer, store.newSessionDraft())
        gate.clear(older)
        gate.acknowledgeOpened("old-session")
        assertEquals(listOf(recent), SettingsStore(context).createAttempts.read())
    }

    @Test fun explicitClearOfAnOldFormDoesNotDeleteNewerDraft() {
        store.saveNewSessionDraft(newer)
        store.consumeNewSessionDraft(original)
        assertEquals(newer, store.newSessionDraft())
        assertTrue(store.wasNewSessionDraftConsumed(original))
    }

    @Test fun clearingOlderSamePromptEditorNeverConsumesNewerAttemptOrSetup() = runBlocking {
        val samePrompt = original.copy(acu = "50", mode = "ultra", draftVersion = "newer")
        val attempt = pending(samePrompt)
        store.saveNewSessionDraft(samePrompt)
        store.createAttempts.write(listOf(attempt))
        store.consumeNewSessionDraft(original)
        assertEquals(samePrompt, store.newSessionDraft())
        assertEquals(listOf(attempt), SettingsStore(context).createAttempts.read())
        assertFalse(store.wasNewSessionDraftConsumed(samePrompt))
    }

    @Test fun setupEditsCanClearDraftAndThenExplicitlyClearPreviousTask() = runBlocking {
        val gate = CreateSessionGate(store.createAttempts, { throw ApiException(500, "Uncertain") })
        gate.submit(original.toCreateRequest(null), original, original.versionKey)
        val attempt = gate.attempts.value.single()
        val edited = original.copy(acu = "50", draftVersion = "edited")
        store.saveNewSessionDraft(edited)
        store.consumeNewSessionDraft(edited)
        assertEquals(listOf(attempt), SettingsStore(context).createAttempts.read())
        gate.clear(attempt)
        assertTrue(SettingsStore(context).createAttempts.read().isEmpty())
        assertFalse(store.hasNewSessionDraft())
        assertTrue(store.wasNewSessionDraftConsumed(original))
        assertTrue(store.wasNewSessionDraftConsumed(edited))
    }

    @Test fun resolvedRequestDefaultNeverBecomesAnEditableOverrideAfterRejection() = runBlocking {
        val editable = original.copy(acu = "")
        val submitted = pending(editable, acu = 20)
        store.saveNewSessionDraft(editable)
        store.createAttempts.write(listOf(submitted))
        assertEquals(20, SettingsStore(context).createAttempts.read().single().submitted?.request?.maxAcuLimit)
        assertEquals("", store.newSessionDraft().acu)
        store.createAttempts.write(emptyList())
        assertEquals(editable, SettingsStore(context).newSessionDraft())
    }

    @Test fun legacyWarningMigratesAndDismissStillRequiresConfirmationUntilClear() = runBlocking {
        store.saveNewSessionDraft(original)
        context.getSharedPreferences("drafts", Context.MODE_PRIVATE).edit()
            .putString("__new_session_attempt__", "").commit()
        val gate = CreateSessionGate(store.createAttempts, { error("Unexpected POST") })
        val legacy = gate.attempts.value.single()
        assertNull(legacy.sessionId)
        assertEquals(original, legacy.submitted?.draft)
        gate.dismiss(legacy)
        val dismissed = SettingsStore(context).createAttempts.read().single()
        assertTrue(dismissed.dismissed)
        assertEquals(CreateResult.NeedsConfirmation(dismissed), gate.submit(original.toCreateRequest(null)))
        gate.clear(dismissed)
        assertTrue(SettingsStore(context).createAttempts.read().isEmpty())
        assertFalse(store.hasNewSessionDraft())
        assertTrue(store.wasNewSessionDraftConsumed(original))
    }

    @Test fun unrelatedPendingTasksNeverReplaceEachOtherAndClearIsExact() = runBlocking {
        val gate = CreateSessionGate(store.createAttempts, { throw ApiException(500, "Uncertain") })
        gate.submit(original.toCreateRequest(null), original, original.versionKey)
        val first = gate.attempts.value.single()
        gate.submit(newer.toCreateRequest(null), newer, newer.versionKey)
        assertEquals(2, SettingsStore(context).createAttempts.read().size)
        gate.clear(first)
        assertTrue(SettingsStore(context).createAttempts.read().single().matches(newer.prompt))
    }

    @Test fun legacySubmittedSnapshotWinsOverNewerEditableSlot() = runBlocking {
        store.saveNewSessionDraft(newer)
        val submitted = original.copy(acu = "20")
        context.getSharedPreferences("drafts", Context.MODE_PRIVATE).edit()
            .putString("__new_session_attempt__", "")
            .putString("__new_session_attempt_draft__", json.encodeToString(submitted))
            .putString("__new_session_attempt_version__", original.versionKey).commit()
        val migrated = SettingsStore(context).createAttempts.read().single()
        assertEquals(original.prompt, migrated.submitted?.request?.prompt)
        assertEquals(20, migrated.submitted?.request?.maxAcuLimit)
        assertEquals(original.versionKey, migrated.submitted?.draftVersion)
        assertEquals(newer, store.newSessionDraft())
    }
}
