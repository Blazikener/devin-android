package com.blazikener.devin.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NewSessionDraftTest {
    @Test fun sharedTextIsAppendedToExistingPrompt() {
        assertEquals("Fix login\n\nhttps://x.dev/1", NewSessionDraft(prompt = "Fix login\n").withShared(" https://x.dev/1 ").prompt)
    }

    @Test fun sharedTextFillsEmptyPrompt() {
        assertEquals("hello", NewSessionDraft().withShared("hello").prompt)
        assertEquals("keep", NewSessionDraft(prompt = "keep").withShared(null).prompt)
    }

    @Test fun modeAndAcuAloneDoNotMakeADraft() {
        assertTrue(NewSessionDraft(mode = "fast", acu = "10").isEmpty)
        assertFalse(NewSessionDraft(repos = listOf("acme/web")).isEmpty)
        assertFalse(NewSessionDraft(attachments = listOf(DraftAttachment("a.txt", "https://u"))).isEmpty)
    }

    @Test fun roundTripsThroughJson() {
        val d = NewSessionDraft("p", "t", "a, b", "5", "ultra", listOf("acme/web"), "playbook-1", listOf(DraftAttachment("a.txt", "https://u")))
        assertEquals(d, json.decodeFromString(NewSessionDraft.serializer(), json.encodeToString(NewSessionDraft.serializer(), d)))
    }
}
