package com.blazikener.devin.ui.create

import com.blazikener.devin.data.DraftAttachment
import com.blazikener.devin.data.NewSessionDraft
import com.blazikener.devin.data.CreateAttempt
import com.blazikener.devin.data.createFingerprint
import com.blazikener.devin.data.json
import com.blazikener.devin.data.versionKey
import com.blazikener.devin.data.withVersionFrom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CreateSessionArgumentsTest {
    @Test fun changedHomeModeAppliesWithoutReplayingItsOldTask() {
        val original = NewSessionDraft().seedCreateDraft("Home task", "ultra")
        val edited = original.copy(prompt = "Edited task", mode = "fast", draftVersion = "edited")
        val changed = edited.seedCreateDraft("Home task", "plan")
        assertEquals("Edited task", changed.prompt)
        assertEquals("plan", changed.mode)
        assertEquals(changed, changed.seedCreateDraft("Home task", "plan"))
        assertNull(changed.seedCreateDraft("Home task", "default").mode)
    }

    @Test fun firstExplicitHomeModeAfterAnUnselectedModeRemainsActionable() {
        val edited = NewSessionDraft().seedCreateDraft("Home task", null).copy(prompt = "Edited task", mode = "fast")
        val changed = edited.seedCreateDraft("Home task", "ultra")
        assertEquals("Edited task", changed.prompt)
        assertEquals("ultra", changed.mode)
    }

    @Test fun freshSharesDoNotEraseThePreviousHomeModeHistory() {
        val edited = NewSessionDraft().seedCreateDraft("Home task", "ultra").copy(prompt = "Edited task", mode = "fast")
        val accepted = edited.withCreateArguments("Home task", null, freshIncoming = true)
        assertEquals("Edited task\n\nHome task", accepted.prompt)
        assertEquals(accepted, accepted.seedCreateDraft("Home task", "ultra"))
    }

    @Test fun sourceModeMetadataNeverChangesDraftOwnershipOrLegacyContentKeys() {
        val legacy = NewSessionDraft(prompt = "Edited task", mode = "fast", lastInitialPrompt = "Home task")
        val adopted = legacy.seedCreateDraft("Home task", "ultra")
        assertEquals("fast", adopted.mode)
        assertEquals(legacy.versionKey, adopted.versionKey)
        val versioned = legacy.copy(draftVersion = "owned")
        assertEquals("owned", adopted.withVersionFrom(versioned).draftVersion)
        val restored = json.decodeFromString(NewSessionDraft.serializer(), json.encodeToString(NewSessionDraft.serializer(), adopted))
        assertEquals(adopted, restored)
        assertEquals("plan", restored.seedCreateDraft("Home task", "plan").mode)
    }

    @Test fun repeatedHomeArgumentPreservesEditedTaskModeAndSubmittedIdentity() {
        val original = NewSessionDraft().seedCreateDraft("Home task", "fast")
        val edited = original.copy(prompt = "Edited task", mode = "ultra", draftVersion = "edited")
        assertFalse(edited.needsSharedChoice("Home task"))
        assertEquals(edited, edited.seedCreateDraft("Home task", "fast"))
    }

    @Test fun freshShareRepeatingAnOldHomeArgumentStillRequiresConsentAndDoesNotOverwrite() {
        val original = NewSessionDraft().seedCreateDraft("Home task", "fast")
        val edited = original.copy(prompt = "Unrelated unfinished task", mode = "ultra", draftVersion = "edited")
        assertTrue(edited.needsSharedChoice("Home task", freshIncoming = true))
        assertEquals(edited, edited.seedCreateDraft("Home task", "fast", freshIncoming = true))
        val accepted = edited.withCreateArguments("Home task", null, freshIncoming = true)
        assertEquals("Unrelated unfinished task\n\nHome task", accepted.prompt)
        assertEquals("ultra", accepted.mode)
    }

    @Test fun matchingShareKeepsUnrelatedDraftAndNeverCombinesPastItsPendingGuard() {
        val saved = NewSessionDraft(prompt = "Unfinished task", mode = "fast", acu = "50", draftVersion = "saved")
        val pending = listOf(CreateAttempt(createFingerprint("Shared task"), 1))
        assertFalse(saved.needsSharedChoice("Shared task", pending))
        assertEquals(saved, saved.seedCreateDraft("Shared task", "ultra", pending))
        assertEquals(saved, saved.withCreateArguments("Shared task", "ultra", pending))
    }

    @Test fun acceptingUnrelatedShareNeverAppendsAnAlreadySubmittedTask() {
        val saved = NewSessionDraft(prompt = "Submitted task", mode = "fast")
        val pending = listOf(CreateAttempt(createFingerprint(saved.prompt), 1))
        assertTrue(saved.needsSharedChoice("New task", pending))
        assertEquals(saved, saved.seedCreateDraft("New task", "plan", pending))
        val accepted = saved.withCreateArguments("New task", "plan", pending)
        assertEquals("New task", accepted.prompt)
        assertEquals("plan", accepted.mode)
    }
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
