package com.blazikener.devin.ui.create

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.blazikener.devin.Launch
import com.blazikener.devin.initialLaunch
import com.blazikener.devin.savedSessionOnLaunch
import com.blazikener.devin.data.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CreateSessionViewModelTest {
    private val models = ViewModelStore()
    private val draft = NewSessionDraft(prompt = "Original task", draftVersion = "a", mode = "fast")
    private val attempts = FakeAttempts(draft)
    @Before fun setUp() = Dispatchers.setMain(StandardTestDispatcher())
    @After fun tearDown() { models.clear(); Dispatchers.resetMain() }
    private fun gate(create: suspend (CreateSessionRequest) -> Session) = CreateSessionGate(attempts, create)
    private fun model(gate: CreateSessionGate, prompt: String = draft.prompt, key: String = "main") =
        ViewModelProvider(models, viewModelFactory {
            initializer { CreateSessionViewModel(gate, prompt) { it.versionKey in attempts.consumed } }
        })[key, CreateSessionViewModel::class.java]

    @Test fun persistsBeforePostAndIgnoresDuplicateTapsWhileStarting() = runTest {
        val response = CompletableDeferred<Session>()
        var calls = 0
        val vm = model(gate {
            calls++
            assertEquals(draft, attempts.records.single().submitted?.draft)
            response.await()
        })
        vm.submit(draft, null)
        vm.submit(draft, null)
        runCurrent()
        assertEquals(1, calls)
        assertTrue(vm.ui.value.submitting)
        response.complete(Session("created"))
        runCurrent()
        assertEquals("created", vm.ui.value.sessionId)
        assertEquals(setOf("a"), attempts.consumed)
        assertNull(attempts.editable)
    }

    @Test fun pendingRestoresAcrossProcessDeathAndOrdinaryStartDoesNotPost() = runTest {
        var calls = 0
        model(gate { calls++; throw ApiException(408, "Timeout") }).submit(draft, null)
        runCurrent()
        val restored = model(gate { calls++; Session("duplicate") }, key = "restored")
        assertTrue(restored.ui.value.uncertain)
        restored.submit(draft, null)
        runCurrent()
        assertEquals(1, calls)
        assertTrue(restored.ui.value.needsRetryConfirmation(draft))
    }

    @Test fun retryUsesExactSnapshotIncludingResolvedDefaultAndCreateAsUser() = runTest {
        val sent = mutableListOf<CreateSessionRequest>()
        val original = draft.copy(attachments = listOf(DraftAttachment("context.txt", "https://files.test/context")))
        val vm = model(gate { request ->
            sent += request
            if (sent.size == 1) throw ApiException(503, "Uncertain") else Session("retried")
        })
        vm.submit(original, "original-user", defaultAcuLimit = 20)
        runCurrent()
        assertEquals("", attempts.records.single().submitted?.draft?.acu)
        assertEquals(20, attempts.records.single().submitted?.request?.maxAcuLimit)
        assertTrue(attempts.records.single().retryDescription().contains("ACU limit: 20"))
        assertTrue(attempts.records.single().retryDescription().contains("Create as: original-user"))
        assertTrue(attempts.records.single().retryDescription().contains("Attached files: 1"))
        assertEquals("", attempts.editable?.acu)
        vm.submit(draft.copy(prompt = "New editor contents", acu = "99", mode = "ultra"), "different-user", confirmedRetry = true, defaultAcuLimit = 30)
        runCurrent()
        assertEquals(sent.first(), sent.last())
        assertEquals("original-user", sent.last().createAsUserId)
        assertEquals("retried", vm.ui.value.sessionId)
    }

    @Test fun invalidAcuAndBlankTaskNeverPost() = runTest {
        var calls = 0
        val vm = model(gate { calls++; Session("invalid") })
        listOf("0", "-1", "abc", "1.2", "2147483648").forEach {
            vm.submit(draft.copy(acu = it), null)
            assertNotNull(vm.ui.value.error)
        }
        vm.submit(draft.copy(prompt = "   "), null)
        runCurrent()
        assertEquals(0, calls)
        assertTrue(attempts.records.isEmpty())
    }

    @Test fun explicitAcuAndSetupAreEncodedWithoutLosingSelections() = runTest {
        var sent: CreateSessionRequest? = null
        val form = draft.copy(prompt = " task ", acu = "7", title = " Title ", tags = "one, two,one",
            repos = listOf("org/repo"), playbookId = "playbook",
            attachments = listOf(DraftAttachment("context", "https://files.test/context")))
        model(gate { sent = it; Session("created") }).submit(form, "user", defaultAcuLimit = 20)
        runCurrent()
        assertEquals("task", sent?.prompt)
        assertEquals("Title", sent?.title)
        assertEquals(listOf("one", "two"), sent?.tags)
        assertEquals(7, sent?.maxAcuLimit)
        assertEquals(form.repos, sent?.repos)
        assertEquals("playbook", sent?.playbookId)
        assertEquals(listOf("https://files.test/context"), sent?.attachmentUrls)
        assertEquals("fast", sent?.devinMode)
    }

    @Test fun knownResultRestoresWithoutAnotherPostAndOnlyMatchingPromptOpensIt() = runTest {
        val record = CreateAttempt(createFingerprint(draft.prompt), 1, sessionId = "known")
        attempts.records = listOf(record)
        val g = gate { error("Unexpected POST") }
        assertEquals("known", model(g).ui.value.sessionId)
        assertNull(model(g, "different task", "other").ui.value.sessionId)
        model(g).acknowledgeCreated("known")
        runCurrent()
        assertTrue(attempts.records.isEmpty())
    }

    @Test fun definitiveRejectionAllowsNormalRetry() = runTest {
        var calls = 0
        val vm = model(gate { if (++calls == 1) throw ApiException(422, "Rejected") else Session("valid") })
        vm.submit(draft, null)
        runCurrent()
        assertFalse(vm.ui.value.uncertain)
        assertEquals(draft, attempts.editable)
        vm.submit(draft, null)
        runCurrent()
        assertEquals(2, calls)
        assertEquals("valid", vm.ui.value.sessionId)
    }

    @Test fun ambiguousStatusesAlwaysRequireAnExplicitRetry() = runTest {
        listOf(408, 500, 503).forEach { code ->
            attempts.records = emptyList()
            var calls = 0
            val vm = model(gate { calls++; throw ApiException(code, "Ambiguous") }, key = "code-$code")
            vm.submit(draft, null)
            runCurrent()
            vm.submit(draft, null)
            runCurrent()
            assertEquals(1, calls)
            assertTrue(vm.ui.value.uncertain)
        }
    }

    @Test fun unrelatedStartsAndAcknowledgementsKeepOlderSnapshots() = runTest {
        var originalCalls = 0
        val sent = mutableListOf<CreateSessionRequest>()
        val g = gate { request ->
            sent += request
            if (request.prompt == draft.prompt && ++originalCalls == 1) throw ApiException(500, "Uncertain")
            Session(request.prompt)
        }
        val original = model(g)
        original.submit(draft.copy(acu = "20"), "original")
        runCurrent()
        val other = draft.copy(prompt = "Unrelated task", draftVersion = "b", acu = "5")
        val newer = model(g, other.prompt, "newer")
        assertFalse(newer.ui.value.needsRetryConfirmation(other))
        newer.submit(other, "other")
        runCurrent()
        newer.acknowledgeCreated(other.prompt)
        runCurrent()
        assertEquals(draft.prompt, attempts.records.single().submitted?.request?.prompt)
        original.submit(other, "other", confirmedRetry = true)
        runCurrent()
        assertEquals(sent.first(), sent.last())
        assertEquals(2, originalCalls)
    }

    @Test fun dismissedWarningResurfacesWithoutPostingAndClearOnlyRemovesItsAttempt() = runTest {
        var calls = 0
        val g = gate { calls++; throw ApiException(500, "Uncertain") }
        val vm = model(g)
        vm.submit(draft, null)
        runCurrent()
        vm.dismissWarning()
        runCurrent()
        assertFalse(vm.ui.value.uncertain)
        assertTrue(attempts.records.single().dismissed)
        vm.submit(draft, null)
        runCurrent()
        assertEquals(1, calls)
        assertTrue(vm.ui.value.uncertain)
        g.submit(CreateSessionRequest(prompt = "Different task"))
        vm.resolveUncertain(draft)
        runCurrent()
        assertEquals(1, attempts.records.size)
        assertTrue(attempts.records.single().matches("Different task"))
        assertNull(attempts.editable)
    }

    @Test fun clearingAnUnrelatedFormNeverDropsTheDisplayedWarningProtection() = runTest {
        val g = gate { throw ApiException(500, "Uncertain") }
        val vm = model(g)
        vm.submit(draft, null)
        runCurrent()
        vm.resolveUncertain(draft.copy(prompt = "Different task", draftVersion = "b"))
        runCurrent()
        assertTrue(attempts.records.single().matches(draft.prompt))
        assertEquals(draft, attempts.editable)
        assertTrue(g.submit(draft.toCreateRequest(null)) is CreateResult.NeedsConfirmation)
    }

    @Test fun rejectedRetryPreservesTheEarlierUncertainAttempt() = runTest {
        var calls = 0
        val vm = model(gate { throw ApiException(if (++calls == 1) 500 else 422, "Failure") })
        vm.submit(draft, null)
        runCurrent()
        val previous = attempts.records.single()
        vm.submit(draft, null, confirmedRetry = true)
        runCurrent()
        assertEquals(previous, attempts.records.single())
        assertTrue(vm.ui.value.uncertain)
    }

    @Test fun sharedGateAllowsOnlyOneInFlightCallAcrossEntryPoints() = runTest {
        val response = CompletableDeferred<Session>()
        var calls = 0
        val g = gate { calls++; response.await() }
        model(g).submit(draft, null)
        runCurrent()
        assertEquals(CreateResult.Busy, g.submit(CreateSessionRequest(prompt = "Home task")))
        assertEquals(1, calls)
        response.complete(Session("created"))
        runCurrent()
    }

    @Test fun failedPersistenceAndConsumedDraftsNeverPost() = runTest {
        var calls = 0
        val vm = model(gate { calls++; Session("invalid") })
        attempts.failWrites = true
        vm.submit(draft, null)
        runCurrent()
        assertEquals(0, calls)
        assertNotNull(vm.ui.value.error)
        attempts.failWrites = false
        attempts.consumed += draft.versionKey
        vm.submit(draft, null)
        runCurrent()
        assertEquals(0, calls)
    }

    @Test fun cancellationLeavesDurableRecoveryWithoutAFalseStartingState() = runTest {
        val g = gate { awaitCancellation() }
        model(g).submit(draft, null)
        runCurrent()
        models.clear()
        runCurrent()
        assertFalse(g.inFlight.value)
        assertNotNull(attempts.records.single().submitted)
        assertTrue(model(gate { error("Unexpected POST") }).ui.value.uncertain)
    }

    @Test fun launchRecoveryDoesNotOverrideSharesDeepLinksOrAnExistingDetail() {
        val records = listOf(CreateAttempt(createFingerprint("task"), 1, sessionId = "known"))
        assertEquals("known", savedSessionOnLaunch(null, "home", records))
        assertNull(savedSessionOnLaunch(Launch.NewSession("new task"), "home", records))
        assertNull(savedSessionOnLaunch(Launch.OpenSession("linked"), "home", records))
        assertNull(savedSessionOnLaunch(null, "settings", records))
        assertNull(initialLaunch(false) { error("Consumed launch replayed") })
        assertEquals(Launch.NewSession("waiting"), initialLaunch(true) { Launch.NewSession("waiting") })
    }

    @Test fun freshInputDiscardsStaleSuccessButNotPendingProtectionOrRestoredResults() = runTest {
        val saved = CreateAttempt(createFingerprint(draft.prompt), 1, sessionId = "known")
        val pending = CreateAttempt(createFingerprint("Pending task"), 2,
            submitted = CreateSnapshot(CreateSessionRequest(prompt = "Pending task"), draft.copy(prompt = "Pending task"), "pending"))
        attempts.records = listOf(saved, pending)
        val g = gate { error("Unexpected POST") }
        g.discardCompletedForIncomingPrompt(null)
        assertEquals("known", model(g).ui.value.sessionId)
        g.discardCompletedForIncomingPrompt(draft.prompt)
        assertNull(model(g, key = "fresh").ui.value.sessionId)
        assertEquals(listOf(pending), attempts.records)
    }

    private class FakeAttempts(var editable: NewSessionDraft?) : CreateAttemptStore {
        var records = emptyList<CreateAttempt>()
        val consumed = mutableSetOf<String>()
        var failWrites = false
        override fun read() = records
        override suspend fun write(attempts: List<CreateAttempt>, consumeDraftVersion: String?): Boolean {
            if (failWrites) return false
            records = attempts
            if (consumeDraftVersion != null) {
                consumed += consumeDraftVersion
                if (editable?.versionKey == consumeDraftVersion) editable = null
            }
            return true
        }
    }
}
