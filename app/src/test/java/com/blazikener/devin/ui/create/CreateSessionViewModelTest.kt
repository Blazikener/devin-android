package com.blazikener.devin.ui.create

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.blazikener.devin.Launch
import com.blazikener.devin.data.ApiException
import com.blazikener.devin.data.CreateAttempt
import com.blazikener.devin.data.CreateAttemptStore
import com.blazikener.devin.data.CreateResult
import com.blazikener.devin.data.CreateSessionGate
import com.blazikener.devin.data.CreateSessionRequest
import com.blazikener.devin.data.DraftAttachment
import com.blazikener.devin.data.NewSessionDraft
import com.blazikener.devin.data.Session
import com.blazikener.devin.data.createFingerprint
import com.blazikener.devin.data.toCreateRequest
import com.blazikener.devin.initialLaunch
import com.blazikener.devin.savedSessionOnLaunch
import com.blazikener.devin.ui.home.recoveredHomePrompt
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.SocketTimeoutException

@OptIn(ExperimentalCoroutinesApi::class)
class CreateSessionViewModelTest {
    private val store = ViewModelStore()
    private val draft = NewSessionDraft(prompt = "Start a session")
    private val attempts = FakeCreateAttempts()
    private fun pending() = attempts.read().firstOrNull { it.matches(draft.prompt) }
    private fun gate(create: suspend (CreateSessionRequest) -> Session) = CreateSessionGate(attempts, create)

    @Before
    fun setUp() = Dispatchers.setMain(StandardTestDispatcher())

    @After
    fun tearDown() {
        store.clear()
        Dispatchers.resetMain()
    }

    private fun model(createSession: suspend (CreateSessionRequest) -> Session) =
        ViewModelProvider(store, viewModelFactory {
            initializer { CreateSessionViewModel(gate(createSession)) }
        })[CreateSessionViewModel::class.java]

    @Test
    fun recreatedScreenKeepsTheOriginalSubmissionAndResult() = runTest {
        val response = CompletableDeferred<Session>()
        var requests = 0
        attempts.saveDraft(draft, "original-draft")
        val original = model(createSession = { requests++; response.await() })
        original.submit(draft, null, draftVersion = "original-draft")
        runCurrent()

        val recreated = model(createSession = { error("Unexpected new request") })
        assertSame(original, recreated)
        assertTrue(recreated.ui.value.submitting)
        recreated.submit(draft, null)
        runCurrent()
        assertEquals(1, requests)
        assertEquals(draft, attempts.draft)

        response.complete(Session("original-session"))
        runCurrent()
        assertEquals("original-session", recreated.ui.value.sessionId)
        assertEquals(NewSessionDraft(), attempts.draft)

        recreated.submit(draft, null)
        runCurrent()
        assertEquals(1, requests)
    }

    @Test
    fun failedSubmissionReleasesGuardAndKeepsDraftUntilRetrySucceeds() = runTest {
        var requests = 0
        attempts.saveDraft(draft, "retry-draft")
        val vm = model(
            createSession = {
                if (++requests == 1) throw ApiException(403, "Rejected")
                Session("retry-session")
            },
        )

        vm.submit(draft, null, draftVersion = "retry-draft")
        runCurrent()
        assertFalse(vm.ui.value.submitting)
        assertEquals("Rejected", vm.ui.value.error)
        assertNull(vm.ui.value.confirmation)
        assertNull(pending())
        assertEquals(draft, attempts.draft)

        vm.submit(draft, null, draftVersion = "retry-draft")
        runCurrent()
        assertEquals("retry-session", vm.ui.value.sessionId)
        assertEquals(NewSessionDraft(), attempts.draft)
    }

    @Test
    fun serverErrorRequiresExplicitRetryBecauseCreateMayHaveSucceeded() = runTest {
        var requests = 0
        val vm = model {
            if (++requests == 1) throw ApiException(500, "Server error")
            Session("retry-session")
        }

        vm.submit(draft, null)
        runCurrent()
        assertEquals("Server error", vm.ui.value.error)
        val interrupted = checkNotNull(pending())
        assertNull(interrupted.sessionId)

        vm.submit(draft, null)
        runCurrent()
        assertEquals(1, requests)

        vm.submit(draft, null, retry = interrupted)
        runCurrent()
        assertEquals(2, requests)
        assertEquals("retry-session", vm.ui.value.sessionId)
    }

    @Test
    fun timeoutPreservesPendingAttempt() = runTest {
        for (failure in listOf(ApiException(408, "Request timeout"), SocketTimeoutException("Read timeout"))) {
            val vm = model { throw failure }
            vm.submit(draft, null)
            runCurrent()

            assertNull(checkNotNull(pending()).sessionId)
            store.clear()
            attempts.write(emptyList())
        }
    }

    @Test
    fun homeModeOverridesDraftModeInFormAndRequest() = runTest {
        val savedDraft = NewSessionDraft(prompt = "Existing draft", mode = "fast")
        val selectedMode = createModeOverride(modeChosen = true, chosenMode = "ultra")
        val form = savedDraft.withCreateArguments("From Home", selectedMode)
        var request: CreateSessionRequest? = null
        val vm = model(
            createSession = { request = it; Session("new-session") },
        )

        assertEquals("ultra", form.mode)
        vm.submit(form, null)
        runCurrent()
        assertEquals("ultra", request?.devinMode)
        assertEquals("Existing draft\n\nFrom Home", request?.prompt)
    }

    @Test
    fun explicitDefaultClearsDraftModeButNoSelectionPreservesIt() = runTest {
        val savedDraft = draft.copy(mode = "fast")
        val explicitDefault = createModeOverride(modeChosen = true, chosenMode = null)
        val noSelection = createModeOverride(modeChosen = false, chosenMode = null)
        var request: CreateSessionRequest? = null
        val vm = model(
            createSession = { request = it; Session("new-session") },
        )

        assertEquals("fast", savedDraft.withCreateArguments(null, noSelection).mode)
        val form = savedDraft.withCreateArguments(null, explicitDefault)
        assertEquals(null, form.mode)
        vm.submit(form, null)
        runCurrent()
        assertEquals(CreateSessionRequest(prompt = draft.prompt), request)
    }

    @Test
    fun restoredPendingCreateBlocksOrdinarySubmitUntilExplicitRetry() = runTest {
        var requests = 0
        val original = model {
            assertNull(checkNotNull(pending()).sessionId)
            assertEquals(draft, pending()?.submitted?.draft)
            requests++
            awaitCancellation()
        }
        original.submit(draft, null)
        runCurrent()
        store.clear()

        val restored = model { requests++; Session("retry-session") }
        val interrupted = checkNotNull(pending())
        restored.submit(draft, null)
        runCurrent()
        assertEquals(1, requests)
        assertEquals(interrupted, restored.ui.value.confirmation)

        restored.submit(draft, null, retry = interrupted)
        restored.submit(draft, null, retry = interrupted)
        runCurrent()
        assertEquals(2, requests)
        assertEquals("retry-session", restored.ui.value.sessionId)
        assertEquals("retry-session", pending()?.sessionId)
        gate { error("Unexpected new request") }.acknowledgeOpened("retry-session")
        assertNull(pending())
    }

    @Test
    fun restoredKnownResultOpensWithoutAnotherRequest() = runTest {
        attempts.saveDraft(NewSessionDraft(), "completed")
        attempts.write(listOf(CreateAttempt(createFingerprint(draft.prompt), 1, "original-session")))
        val restored = gate { error("Unexpected new request") }
        assertEquals("original-session", savedSessionOnLaunch(null, "home", restored.attempts.value))
        assertEquals("original-session", restored.openSaved(draft.prompt))
        assertEquals(CreateResult.Open("original-session"), restored.submit(CreateSessionRequest(draft.prompt)))
        restored.acknowledgeOpened("original-session")
        assertNull(pending())
    }

    @Test
    fun launcherRecoveryKeepsGuardUntilHomeClearsOnlyTheMatchingRestoredPrompt() = runTest {
        val completed = CreateAttempt(createFingerprint(draft.prompt), 1, "original-session")
        attempts.write(listOf(completed))
        val restored = gate { error("Unexpected new request") }
        val id = savedSessionOnLaunch(null, "home", restored.attempts.value)
        assertEquals("original-session", id)
        assertEquals(CreateResult.Open("original-session"), restored.submit(CreateSessionRequest(draft.prompt)))
        assertEquals("", recoveredHomePrompt(" ${draft.prompt} ", completed))
        assertEquals("New Home draft", recoveredHomePrompt("New Home draft", completed))
        restored.acknowledgeOpened(checkNotNull(id))
        assertNull(pending())
    }

    @Test
    fun incomingSharedPromptTakesPriorityOverCompletedSession() = runTest {
        attempts.saveDraft(NewSessionDraft(), "completed")
        attempts.write(listOf(CreateAttempt(createFingerprint(draft.prompt), 1, "old-session")))
        val incoming = "New shared text"
        val restored = gate { error("Unexpected new request") }

        assertNull(savedSessionOnLaunch(Launch.NewSession(incoming), "home", restored.attempts.value))
        assertNull(savedSessionOnLaunch(Launch.OpenSession("linked"), "home", restored.attempts.value))
        assertNull(restored.openSaved(incoming))
        assertEquals(incoming, attempts.draft?.withCreateArguments(incoming, null)?.prompt)
    }

    @Test
    fun restoredCreateRouteWithOriginalPromptKeepsCompletedSession() = runTest {
        val originalLaunch = Launch.NewSession("Original shared text")
        attempts.write(listOf(CreateAttempt(createFingerprint("Original shared text"), 1, "original-session")))
        val restoredLaunch = initialLaunch(launchPending = false) { originalLaunch }
        assertNull(restoredLaunch)
        assertEquals(originalLaunch, initialLaunch(launchPending = true) { originalLaunch })
        assertEquals(originalLaunch, initialLaunch(launchPending = null) { originalLaunch })

        val restored = gate { error("Unexpected new request") }
        assertEquals("original-session", restored.openSaved("Original shared text"))
    }

    @Test
    fun incomingSharedPromptDoesNotDiscardUncertainAttempt() = runTest {
        val interrupted = CreateAttempt(createFingerprint(draft.prompt), 1)
        attempts.saveDraft(draft, "pending")
        attempts.write(listOf(interrupted))
        var requests = 0
        val shared = gate { requests++; Session("new-session") }
        assertEquals(CreateResult.Open("new-session"), shared.submit(CreateSessionRequest("New shared text")))
        assertEquals(CreateResult.NeedsConfirmation(interrupted), shared.submit(CreateSessionRequest("  ${draft.prompt}  ")))
        assertEquals(1, requests)
    }

    @Test
    fun sharedPromptMatchingAnAttemptIsNotAppendedToAnUnrelatedDraft() = runTest {
        val interrupted = CreateAttempt(createFingerprint(draft.prompt), 1)
        attempts.write(listOf(interrupted))
        val shared = gate { error("Unexpected new request") }
        val editable = draft.copy(prompt = "Unrelated text")
        val form = editable.withCreateArguments(draft.prompt, null, shared.attempts.value)

        assertEquals(draft.prompt, form.prompt)
        assertEquals(CreateResult.NeedsConfirmation(interrupted), shared.submit(form.toCreateRequest(null)))
    }

    @Test
    fun unrelatedSharedPromptDoesNotAppendSubmittedOrIdenticalText() {
        val interrupted = CreateAttempt(createFingerprint(draft.prompt), 1)
        val incoming = "New shared text"
        val form = draft.withCreateArguments(incoming, null, listOf(interrupted))

        assertEquals(incoming, form.prompt)
        assertEquals(form, form.withCreateArguments(incoming, null, listOf(interrupted)))
        assertEquals(
            "$incoming\n\nMore context",
            form.withCreateArguments("More context", null, listOf(interrupted)).prompt,
        )
    }

    @Test
    fun homeQuickSendAndCreateUseTheSameGuardAndClearIsScoped() = runTest {
        val interrupted = CreateAttempt(createFingerprint(draft.prompt), 1)
        val other = CreateAttempt(createFingerprint("An older prompt"), 0)
        attempts.write(listOf(interrupted, other))
        var requests = 0
        val shared = gate { requests++; Session("new-session") }
        val home = CreateSessionViewModel(shared)
        home.submit(draft, null)
        runCurrent()
        assertEquals(interrupted, home.ui.value.confirmation)
        assertEquals(0, requests)
        home.dismiss(interrupted)
        runCurrent()
        val dismissed = checkNotNull(pending())
        assertTrue(dismissed.dismissed)
        home.submit(draft, null)
        runCurrent()
        assertEquals(dismissed, home.ui.value.confirmation)
        assertEquals(0, requests)
        val create = CreateSessionViewModel(shared)
        create.clear(dismissed)
        runCurrent()
        assertEquals(listOf(other), shared.attempts.value)
        create.submit(draft, null)
        runCurrent()
        assertEquals(1, requests)
    }

    @Test
    fun failedDurableWritePreventsCreateRequest() = runTest {
        attempts.failWrites = true
        val vm = model { error("Unexpected new request") }
        vm.submit(draft, null)
        runCurrent()
        assertEquals("Couldn't save the draft. The session was not started.", vm.ui.value.error)
        assertTrue(attempts.read().isEmpty())
    }

    @Test
    fun retryUsesSubmittedConfigurationAndOnlyConsumesItsOwnDraftVersion() = runTest {
        val original = draft.copy(
            acu = "29", mode = "fast", repos = listOf("org/repo"), playbookId = "playbook",
            attachments = listOf(DraftAttachment("plan.md", "https://example.test/plan")),
        )
        val originalRequest = original.toCreateRequest("original-user")
        attempts.saveDraft(original, "submitted")
        val initial = gate { throw ApiException(503, "Server error") }
        assertEquals(CreateResult.Failed("Server error"), initial.submit(originalRequest, original, "submitted"))
        val newer = original.copy(acu = "81", mode = "ultra")
        attempts.saveDraft(newer, "newer")
        val restored = model {
            assertEquals(originalRequest, it)
            Session("recovered")
        }
        val interrupted = checkNotNull(pending())
        assertEquals(original, interrupted.submitted?.draft)
        assertEquals("submitted", interrupted.submitted?.draftVersion)
        assertEquals("Max ACUs: 29 · Mode: Fast", interrupted.submitted?.request?.retrySettingsSummary())
        assertEquals("Max ACUs: not set · Mode: Default", CreateSessionRequest("Default settings").retrySettingsSummary())
        restored.submit(newer, "other-user", retry = interrupted, draftVersion = "newer")
        runCurrent()
        assertEquals("recovered", restored.ui.value.sessionId)
        assertEquals(newer, attempts.draft)
    }

    @Test
    fun clearConsumesOnlyTheRemovedAttemptsDraftVersion() = runTest {
        val shared = gate { throw ApiException(503, "Server error") }
        val request = draft.toCreateRequest(null)
        attempts.saveDraft(draft, "first")
        shared.submit(request, draft, "first")
        shared.clear(shared.attempts.value.single())
        assertTrue(shared.attempts.value.isEmpty())
        assertEquals(NewSessionDraft(), attempts.draft)

        attempts.saveDraft(draft, "second")
        shared.submit(request, draft, "second")
        val newer = draft.copy(prompt = "Newer share")
        attempts.saveDraft(newer, "third")
        shared.clear(shared.attempts.value.single())
        assertTrue(shared.attempts.value.isEmpty())
        assertEquals(newer, attempts.draft)
    }

    private class FakeCreateAttempts : CreateAttemptStore {
        private var records = emptyList<CreateAttempt>()
        var draft: NewSessionDraft? = null
            private set
        private var version: String? = null
        var failWrites = false

        fun saveDraft(draft: NewSessionDraft, version: String) {
            this.draft = draft
            this.version = version
        }

        override fun read() = records

        override suspend fun write(attempts: List<CreateAttempt>, consumeDraftVersion: String?): Boolean {
            if (failWrites) return false
            records = attempts
            if (consumeDraftVersion != null && consumeDraftVersion == version) {
                draft = NewSessionDraft()
                version = null
            }
            return true
        }

    }
}
