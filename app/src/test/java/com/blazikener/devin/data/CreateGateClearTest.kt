package com.blazikener.devin.data

import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class CreateGateClearTest {
    private val original = attempt("Original", "original-version")
    private val newer = attempt("Newer shared draft", "newer-version")

    @Test fun clearConsumesOnlyItsSubmittedVersionAndPreservesNewerDraftAndAttempt() = runTest {
        val store = DraftStore(listOf(original, newer), newer.submitted!!.draftVersion)
        val gate = CreateSessionGate(store, { error("Clear must not post") })
        gate.clear(original)
        assertEquals(listOf(newer), gate.attempts.value)
        assertEquals(listOf("original-version"), store.consumed)
        assertEquals("newer-version", store.savedDraftVersion)
        gate.clear(newer)
        assertTrue(gate.attempts.value.isEmpty())
        assertNull(store.savedDraftVersion)
    }

    @Test fun staleClearCannotRemoveOrConsumeTheReplacementRetryAttempt() = runTest {
        val store = DraftStore(listOf(original), original.submitted!!.draftVersion)
        val gate = CreateSessionGate(store, { throw IOException("Unknown outcome") }, now = { 2 })
        gate.retryAnyway(original)
        val retry = gate.attempts.value.single()
        assertNotEquals(original, retry)
        gate.clear(original)
        assertEquals(listOf(retry), gate.attempts.value)
        assertTrue(store.consumed.isEmpty())
        assertEquals("original-version", store.savedDraftVersion)
    }

    private fun attempt(prompt: String, version: String): CreateAttempt {
        val request = CreateSessionRequest(prompt)
        return CreateAttempt(createFingerprint(prompt), 1, submitted = CreateSnapshot(request, request.toCreateDraft(), version))
    }

    private class DraftStore(
        private var records: List<CreateAttempt>,
        var savedDraftVersion: String?,
    ) : CreateAttemptStore {
        val consumed = mutableListOf<String>()
        override fun read() = records
        override suspend fun write(attempts: List<CreateAttempt>, consumeDraftVersion: String?): Boolean {
            records = attempts
            consumeDraftVersion?.let {
                consumed += it
                if (savedDraftVersion == it) savedDraftVersion = null
            }
            return true
        }
    }
}
