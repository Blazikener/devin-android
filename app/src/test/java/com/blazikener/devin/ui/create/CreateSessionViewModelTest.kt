package com.blazikener.devin.ui.create

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.blazikener.devin.Launch
import com.blazikener.devin.data.ApiException
import com.blazikener.devin.data.CreateAttempt
import com.blazikener.devin.data.CreateAttemptStore
import com.blazikener.devin.data.CreateSessionRequest
import com.blazikener.devin.data.NewSessionDraft
import com.blazikener.devin.data.Session
import com.blazikener.devin.initialLaunch
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

    @Before
    fun setUp() = Dispatchers.setMain(StandardTestDispatcher())

    @After
    fun tearDown() {
        store.clear()
        Dispatchers.resetMain()
    }

    private fun model(createSession: suspend (CreateSessionRequest) -> Session) =
        ViewModelProvider(store, viewModelFactory {
            initializer { CreateSessionViewModel(createSession, attempts) }
        })[CreateSessionViewModel::class.java]

    @Test
    fun recreatedScreenKeepsTheOriginalSubmissionAndResult() = runTest {
        val response = CompletableDeferred<Session>()
        var requests = 0
        val original = model(createSession = { requests++; response.await() })
        original.submit(draft, null)
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
        val vm = model(
            createSession = {
                if (++requests == 1) throw ApiException(403, "Rejected")
                Session("retry-session")
            },
        )

        vm.submit(draft, null)
        runCurrent()
        assertFalse(vm.ui.value.submitting)
        assertEquals("Rejected", vm.ui.value.error)
        assertFalse(vm.ui.value.uncertain)
        assertNull(attempts.read())
        assertEquals(draft, attempts.draft)

        vm.submit(draft, null)
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
        assertTrue(vm.ui.value.uncertain)
        assertEquals("Server error", vm.ui.value.error)
        assertEquals(CreateAttempt.Pending, attempts.read())

        vm.submit(draft, null)
        runCurrent()
        assertEquals(1, requests)

        vm.submit(draft, null, confirmedRetry = true)
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

            assertTrue(vm.ui.value.uncertain)
            assertEquals(CreateAttempt.Pending, attempts.read())
            store.clear()
            attempts.write(null)
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
            assertEquals(CreateAttempt.Pending, attempts.read())
            assertEquals(draft, attempts.draft)
            requests++
            awaitCancellation()
        }
        original.submit(draft, null)
        runCurrent()
        store.clear()

        val restored = model { requests++; Session("retry-session") }
        assertTrue(restored.ui.value.uncertain)
        restored.submit(draft, null)
        runCurrent()
        assertEquals(1, requests)
        assertEquals(CreateAttempt.Pending, attempts.read())

        restored.submit(draft, null, confirmedRetry = true)
        restored.submit(draft, null, confirmedRetry = true)
        runCurrent()
        assertEquals(2, requests)
        assertEquals("retry-session", restored.ui.value.sessionId)
        assertEquals(CreateAttempt.Created("retry-session"), attempts.read())
        restored.acknowledgeCreated("retry-session")
        assertNull(attempts.read())
    }

    @Test
    fun restoredKnownResultOpensWithoutAnotherRequest() = runTest {
        attempts.write(CreateAttempt.Created("original-session"), NewSessionDraft())
        val restored = model { error("Unexpected new request") }
        assertEquals("original-session", restored.ui.value.sessionId)
        assertFalse(restored.ui.value.uncertain)
        restored.submit(draft, null)
        runCurrent()
        restored.acknowledgeCreated("original-session")
        assertNull(attempts.read())
    }

    @Test
    fun incomingSharedPromptTakesPriorityOverCompletedSession() = runTest {
        attempts.write(CreateAttempt.Created("old-session"), NewSessionDraft())
        val incoming = "New shared text"
        attempts.discardCompletedForIncomingPrompt(incoming)
        val vm = model { error("Unexpected new request") }

        assertNull(vm.ui.value.sessionId)
        assertFalse(vm.ui.value.uncertain)
        assertEquals(incoming, attempts.draft?.withCreateArguments(incoming, null)?.prompt)
    }

    @Test
    fun restoredCreateRouteWithOriginalPromptKeepsCompletedSession() = runTest {
        attempts.write(CreateAttempt.Created("original-session"), NewSessionDraft())
        val originalLaunch = Launch.NewSession("Original shared text")
        val restoredLaunch = initialLaunch(launchPending = false) { originalLaunch }
        assertNull(restoredLaunch)
        assertEquals(originalLaunch, initialLaunch(launchPending = true) { originalLaunch })
        assertEquals(originalLaunch, initialLaunch(launchPending = null) { originalLaunch })

        val restored = model { error("Unexpected new request") }
        assertEquals("original-session", restored.ui.value.sessionId)
    }

    @Test
    fun incomingSharedPromptDoesNotDiscardUncertainAttempt() = runTest {
        attempts.write(CreateAttempt.Pending, draft)
        attempts.discardCompletedForIncomingPrompt("New shared text")
        val vm = model { error("Unexpected new request") }

        assertTrue(vm.ui.value.uncertain)
        assertEquals(CreateAttempt.Pending, attempts.read())
    }

    @Test
    fun failedDurableWritePreventsCreateRequest() = runTest {
        attempts.failWrites = true
        val vm = model { error("Unexpected new request") }
        vm.submit(draft, null)
        runCurrent()
        assertEquals("Couldn't save the draft. The session was not started.", vm.ui.value.error)
        assertNull(attempts.read())
    }

    private class FakeCreateAttempts : CreateAttemptStore {
        private var attempt: CreateAttempt? = null
        var draft: NewSessionDraft? = null
        var failWrites = false

        override fun read() = attempt

        override suspend fun write(attempt: CreateAttempt?, draft: NewSessionDraft?): Boolean {
            if (failWrites) return false
            this.attempt = attempt
            if (draft != null) this.draft = draft
            return true
        }

        override fun acknowledgeCreated(sessionId: String) {
            if (attempt == CreateAttempt.Created(sessionId)) attempt = null
        }
    }
}
