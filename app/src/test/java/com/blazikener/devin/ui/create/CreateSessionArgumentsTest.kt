package com.blazikener.devin.ui.create

import com.blazikener.devin.data.DraftAttachment
import com.blazikener.devin.data.CreateAttempt
import com.blazikener.devin.data.CreateAttemptStore
import com.blazikener.devin.data.CreateResult
import com.blazikener.devin.data.CreateSessionGate
import com.blazikener.devin.data.CreateSessionRequest
import com.blazikener.devin.data.createFingerprint
import com.blazikener.devin.data.NewSessionDraft
import com.blazikener.devin.data.json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import kotlinx.coroutines.test.runTest

class CreateSessionArgumentsTest {
    @Test fun cancelEditRestoreAndReopenDoesNotDuplicateHomeText() {
        val original = NewSessionDraft(prompt = "Previous context", repos = listOf("acme/app"),
            attachments = listOf(DraftAttachment("spec.md", "https://example.com/spec.md")))
        val opened = original.withCreateArguments("Home task", "fast")
        assertEquals("Previous context\n\nHome task", opened.prompt)
        val edited = opened.copy(prompt = "Previous context\n\nHome task, refined", title = "Refined task")
        // Both rememberSaveable and persistent drafts use this serialization round trip.
        val restored = json.decodeFromString(NewSessionDraft.serializer(),
            json.encodeToString(NewSessionDraft.serializer(), edited))
        assertEquals(edited, restored.withCreateArguments(" Home task ", null))
        assertEquals(edited, restored.withCreateArguments("Home task", null).withCreateArguments("Home task", null))
    }

    @Test fun distinctTextAppendsButExplicitDefaultStillOverridesMode() {
        val first = NewSessionDraft().withCreateArguments("First task", "fast")
        val second = first.withCreateArguments("Second task", "default")
        assertEquals("First task\n\nSecond task", second.prompt)
        assertNull(second.mode)
        assertEquals(second, second.withCreateArguments(null, null))
        assertEquals("First task", NewSessionDraft().withCreateArguments("First task", null).prompt)
    }

    @Test fun existingMatchingPromptIsNotDuplicatedWhenAdoptingMarker() {
        val existing = NewSessionDraft(prompt = "Home task")
        assertEquals("Home task", existing.withCreateArguments("Home task", null).prompt)
    }

    @Test fun matchingShareCannotBypassTheGateByAppendingToAnUnrelatedDraftOrOldMarker() = runTest {
        val pending = CreateAttempt(createFingerprint("Submitted task"), 1)
        val saved = NewSessionDraft(prompt = "Unfinished work", lastInitialPrompt = "Submitted task",
            attachments = listOf(DraftAttachment("spec.md", "mock://spec")))
        val store = object : CreateAttemptStore {
            override fun read() = listOf(pending)
            override suspend fun write(attempts: List<CreateAttempt>, consumeDraftVersion: String?) = error("Must not overwrite recovery")
        }
        val gate = CreateSessionGate(store, { error("Must not POST a matching share") })
        val form = saved.withCreateArguments(" Submitted task ", null, gate.attempts.value, freshIncoming = true)
        assertEquals("Submitted task", form.prompt)
        assertEquals(CreateResult.NeedsConfirmation(pending), gate.submit(CreateSessionRequest(form.prompt)))
        assertTrue(saved.shouldPreserveOnRecovery(pending))
        assertEquals(listOf(DraftAttachment("spec.md", "mock://spec")), saved.attachments)
        assertFalse(form.shouldPreserveOnRecovery(pending))
        assertFalse(NewSessionDraft().shouldPreserveOnRecovery(pending))
        assertFalse(saved.shouldPreserveOnRecovery(null))
    }

    @Test fun unrelatedShareReplacesSubmittedTextButOrdinaryNewContextStillAppendsOnce() {
        val pending = CreateAttempt(createFingerprint("Submitted task"), 1)
        val form = NewSessionDraft(prompt = "Submitted task").withCreateArguments("Fresh task", null, listOf(pending))
        assertEquals("Fresh task", form.prompt)
        assertEquals(form, form.withCreateArguments("Fresh task", null, listOf(pending)))
        assertEquals("Fresh task\n\nMore context", form.withCreateArguments("More context", null, listOf(pending)).prompt)
    }

    @Test fun homeReopeningAnEditedUncertainTaskKeepsItsSubmittedPromptAndGuard() = runTest {
        val edited = NewSessionDraft(prompt = "Home task, refined", lastInitialPrompt = "Home task")
        val pending = CreateAttempt(createFingerprint(edited.prompt), 1)
        val gate = CreateSessionGate(object : CreateAttemptStore {
            override fun read() = listOf(pending)
            override suspend fun write(attempts: List<CreateAttempt>, consumeDraftVersion: String?) = error("Must not alter pending task")
        }, { error("Must not POST old Home text") })
        val reopened = edited.withCreateArguments("Home task", null, gate.attempts.value)
        assertEquals(edited, reopened)
        assertEquals(CreateResult.NeedsConfirmation(pending), gate.submit(CreateSessionRequest(reopened.prompt)))
    }
}
