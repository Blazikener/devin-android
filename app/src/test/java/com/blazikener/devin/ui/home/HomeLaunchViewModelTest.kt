package com.blazikener.devin.ui.home

import androidx.lifecycle.viewModelScope
import com.blazikener.devin.data.CreateSessionRequest
import com.blazikener.devin.data.Session
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
    private fun model(create: suspend (CreateSessionRequest) -> Session) = HomeLaunchViewModel(create).also { models += it }

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
        assertEquals("retry-success", vm.state.value.createdId)
        vm.consumeCreated()
        assertEquals(HomeLaunchState(), vm.state.value)
    }
}
