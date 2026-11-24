package com.blazikener.devin.ui.home

import androidx.lifecycle.viewModelScope
import com.blazikener.devin.Launch
import com.blazikener.devin.savedSessionOnLaunch
import com.blazikener.devin.data.CreateAttempt
import com.blazikener.devin.data.CreateAttemptStore
import com.blazikener.devin.data.CreateSessionGate
import com.blazikener.devin.data.CreateSessionRequest
import com.blazikener.devin.data.CreateSnapshot
import com.blazikener.devin.data.toCreateDraft
import com.blazikener.devin.data.Session
import com.blazikener.devin.data.createFingerprint
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class HomeLaunchViewModelTest {
    private val models = mutableListOf<HomeLaunchViewModel>()
    @Before fun setUp() = Dispatchers.setMain(StandardTestDispatcher())
    @After fun tearDown() { models.forEach { it.viewModelScope.cancel() }; Dispatchers.resetMain() }
    private fun model(store: FakeAttempts = FakeAttempts(), create: suspend (CreateSessionRequest) -> Session) =
        model(CreateSessionGate(store, create))
    private fun model(gate: CreateSessionGate) = HomeLaunchViewModel(gate).also { models += it }

    @Test fun rapidTapsAndBlankPromptsNeverCreateDuplicateWork() = runTest {
        val response = CompletableDeferred<Session>()
        var calls = 0
        val vm = model { calls++; response.await() }
        vm.start(CreateSessionRequest("  "))
        runCurrent()
        assertEquals(0, calls)
        vm.start(CreateSessionRequest("Ship it"))
        vm.start(CreateSessionRequest("Ship it"))
        assertTrue(vm.state.value.busy)
        runCurrent()
        assertEquals(1, calls)
        response.complete(Session("created"))
        runCurrent()
        assertEquals("created", vm.state.value.createdId)
        assertFalse(vm.state.value.busy)
        assertEquals(0, currentTime) // Success is not gated on the 2200ms decorative cue.
        vm.start(CreateSessionRequest("Ship it"))
        runCurrent()
        assertEquals(1, calls)
    }

    @Test fun failureWaitsForExplicitRetryAndPreservesSubmittedOptions() = runTest {
        var calls = 0
        val request = CreateSessionRequest("Keep my draft", devinMode = "lite", maxAcuLimit = 12)
        val vm = model {
            assertEquals(request, it)
            calls++
            if (calls == 1) throw IOException("Offline")
            Session("retry-success")
        }
        vm.start(request)
        runCurrent()
        assertEquals("Offline", vm.state.value.error)
        assertFalse(vm.state.value.busy)
        advanceUntilIdle()
        assertEquals(1, calls)
        vm.start(request)
        runCurrent()
        assertEquals(1, calls)
        val pending = checkNotNull(vm.state.value.confirmation)
        vm.retryAnyway(pending)
        runCurrent()
        assertEquals("retry-success", vm.state.value.createdId)
        vm.consumeCreated("retry-success")
        runCurrent()
        assertEquals(HomeLaunchState(), vm.state.value)
    }

    @Test fun coldHomeObservesPendingAttemptAndNeverRepeatsItsPromptSilently() = runTest {
        val store = FakeAttempts()
        var calls = 0
        val original = model(store) { calls++; CompletableDeferred<Session>().await() }
        original.start(CreateSessionRequest("An interrupted task"))
        runCurrent()
        original.viewModelScope.cancel()
        runCurrent()
        val restored = model(store) { calls++; Session("duplicate") }
        val pending = restored.attempts.value.single()
        assertFalse(pending.dismissed)
        assertNull(pending.sessionId)
        restored.start(CreateSessionRequest("  An interrupted task  "))
        runCurrent()
        assertEquals(pending, restored.state.value.confirmation)
        assertEquals(1, calls)
    }

    @Test fun dismissAndClearDoNotBlockAnUnrelatedTaskOrSilentlyRepeatTheOldOne() = runTest {
        var calls = 0
        val gate = CreateSessionGate(FakeAttempts(), { request ->
            calls++
            if (calls == 1) throw IOException("Uncertain")
            Session(request.prompt)
        })
        val vm = model(gate)
        val old = CreateSessionRequest("Old task")
        vm.start(old)
        runCurrent()
        vm.dismiss(gate.attempts.value.single())
        runCurrent()
        val dismissed = gate.attempts.value.single()
        assertTrue(dismissed.dismissed)
        vm.start(old)
        runCurrent()
        assertEquals(dismissed, vm.state.value.confirmation)
        assertEquals(1, calls)
        vm.start(CreateSessionRequest("Unrelated task"))
        runCurrent()
        assertEquals("Unrelated task", vm.state.value.createdId)
        vm.consumeCreated("Unrelated task")
        runCurrent()
        vm.clearAttempt(dismissed)
        runCurrent()
        assertTrue(gate.attempts.value.isEmpty())
        vm.start(old)
        runCurrent()
        assertEquals("Old task", vm.state.value.createdId)
        assertEquals(3, calls)
    }

    @Test fun theAppScopedGatePreventsConcurrentStartsAcrossHomeInstances() = runTest {
        val response = CompletableDeferred<Session>()
        var calls = 0
        val gate = CreateSessionGate(FakeAttempts(), { calls++; response.await() })
        val first = model(gate)
        val second = model(gate)
        first.start(CreateSessionRequest("First"))
        runCurrent()
        assertTrue(second.inFlight.value)
        second.start(CreateSessionRequest("Second"))
        runCurrent()
        assertEquals(1, calls)
        assertNotNull(second.state.value.error)
        response.complete(Session("first"))
        runCurrent()
        assertFalse(second.inFlight.value)
    }

    @Test fun savedSuccessOpensWithoutPostingAndLaunchRecoveryNeverOverridesExplicitIntents() = runTest {
        val saved = CreateAttempt(createFingerprint("Known task"), 1, sessionId = "known")
        val vm = model(FakeAttempts(listOf(saved))) { error("Must not create again") }
        assertEquals("known", savedSessionOnLaunch(null, "home", vm.attempts.value))
        assertNull(savedSessionOnLaunch(Launch.NewSession("Shared task"), "home", vm.attempts.value))
        assertNull(savedSessionOnLaunch(Launch.OpenSession("linked"), "home", vm.attempts.value))
        assertNull(savedSessionOnLaunch(null, "settings", vm.attempts.value))
        vm.start(CreateSessionRequest("Known task"))
        runCurrent()
        assertEquals("known", vm.state.value.createdId)
        assertEquals(saved.fingerprint, vm.state.value.createdFingerprint)
        vm.consumeCreated("known")
        runCurrent()
        assertTrue(vm.attempts.value.isEmpty())
    }

    @Test fun openingRecoveryIdentifiesOnlyTheOriginalDraftForCleanup() = runTest {
        val saved = CreateAttempt(createFingerprint("Original task"), 1, sessionId = "known")
        val vm = model(FakeAttempts(listOf(saved))) { error("Must not create again") }
        vm.openSaved(saved)
        assertEquals("known", vm.state.value.createdId)
        assertEquals(createFingerprint("Original task"), vm.state.value.createdFingerprint)
        assertNotEquals(createFingerprint("Unrelated Home draft"), vm.state.value.createdFingerprint)
    }

    @Test fun clearingOnlyTheHomePromptPreservesTheSavedCreateTaskAndItsDuplicateGuard() = runTest {
        val request = CreateSessionRequest("Same prompt", attachmentUrls = listOf("mock://uploaded-file"), devinMode = "lite", maxAcuLimit = 12)
        val snapshot = CreateSnapshot(request, request.toCreateDraft(), "create-owned-version")
        val pending = CreateAttempt(createFingerprint(request.prompt), 1, submitted = snapshot)
        val vm = model(FakeAttempts(listOf(pending))) { error("Must not duplicate the saved task") }
        vm.start(CreateSessionRequest("Same prompt"))
        runCurrent()
        assertEquals(pending, vm.state.value.confirmation)
        vm.clearPrompt()
        runCurrent()
        assertEquals(HomeLaunchState(), vm.state.value)
        assertEquals(listOf(pending), vm.attempts.value)
        vm.start(CreateSessionRequest("Same prompt"))
        runCurrent()
        assertEquals(pending, vm.state.value.confirmation)
        assertEquals(request, vm.state.value.confirmation!!.submitted!!.request)
    }

    private class FakeAttempts(private var records: List<CreateAttempt> = emptyList()) : CreateAttemptStore {
        override fun read() = records
        override suspend fun write(attempts: List<CreateAttempt>, consumeDraftVersion: String?): Boolean {
            records = attempts
            return true
        }
    }
}
