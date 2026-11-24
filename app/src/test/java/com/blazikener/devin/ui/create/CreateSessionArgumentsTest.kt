package com.blazikener.devin.ui.create

import com.blazikener.devin.data.DraftAttachment
import com.blazikener.devin.data.NewSessionDraft
import com.blazikener.devin.data.json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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

    @Test fun keepDraftDefersIncomingModeAndTaskUntilAccepted() {
        val saved = NewSessionDraft(prompt = "Saved task", mode = "fast", draftVersion = "saved")
        assertTrue(saved.needsSharedChoice("Incoming task"))
        val beforeDecision = saved.seedCreateDraft("Incoming task", "plan")
        assertEquals(saved, beforeDecision)
        val accepted = beforeDecision.withCreateArguments("Incoming task", "plan")
        assertEquals("Saved task\n\nIncoming task", accepted.prompt)
        assertEquals("plan", accepted.mode)
    }

    @Test fun repeatedHomeContributionDoesNotReopenChoiceOrDuplicateEdits() {
        val edited = NewSessionDraft().seedCreateDraft("Home task", "fast").copy(prompt = "Refined Home task")
        assertFalse(edited.needsSharedChoice("Home task"))
        assertEquals(edited, edited.seedCreateDraft("Home task", "fast"))
    }
}
