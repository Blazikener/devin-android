package com.blazikener.devin.ui.create

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.blazikener.devin.data.CreateSessionRequest
import com.blazikener.devin.data.NewSessionDraft
import com.blazikener.devin.data.Session
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class CreateSessionViewModelTest {
    private val store = ViewModelStore()
    private val draft = NewSessionDraft(prompt = "Start a session")

    @Before
    fun setUp() = Dispatchers.setMain(StandardTestDispatcher())

    @After
    fun tearDown() {
        store.clear()
        Dispatchers.resetMain()
    }

    private fun model(createSession: suspend (CreateSessionRequest) -> Session, clearDraft: () -> Unit) =
        ViewModelProvider(store, viewModelFactory {
            initializer { CreateSessionViewModel(createSession, clearDraft) }
        })[CreateSessionViewModel::class.java]

    @Test
    fun recreatedScreenKeepsTheOriginalSubmissionAndResult() = runTest {
        val response = CompletableDeferred<Session>()
        var requests = 0
        var draftsCleared = 0
        val original = model(createSession = { requests++; response.await() }, clearDraft = { draftsCleared++ })
        original.submit(draft, null)
        runCurrent()

        val recreated = model(createSession = { error("Unexpected new request") }, clearDraft = {})
        assertSame(original, recreated)
        assertTrue(recreated.ui.value.submitting)
        recreated.submit(draft, null)
        runCurrent()
        assertEquals(1, requests)
        assertEquals(0, draftsCleared)

        response.complete(Session("original-session"))
        runCurrent()
        assertEquals("original-session", recreated.ui.value.sessionId)
        assertEquals(1, draftsCleared)

        recreated.submit(draft, null)
        runCurrent()
        assertEquals(1, requests)
    }

    @Test
    fun failedSubmissionReleasesGuardAndKeepsDraftUntilRetrySucceeds() = runTest {
        var requests = 0
        var draftsCleared = 0
        val vm = model(
            createSession = {
                if (++requests == 1) throw IOException("Offline")
                Session("retry-session")
            },
            clearDraft = { draftsCleared++ },
        )

        vm.submit(draft, null)
        runCurrent()
        assertFalse(vm.ui.value.submitting)
        assertEquals("Offline", vm.ui.value.error)
        assertEquals(0, draftsCleared)

        vm.submit(draft, null)
        runCurrent()
        assertEquals("retry-session", vm.ui.value.sessionId)
        assertEquals(1, draftsCleared)
    }

    @Test
    fun homeModeOverridesDraftModeInFormAndRequest() = runTest {
        val savedDraft = NewSessionDraft(prompt = "Existing draft", mode = "fast")
        val selectedMode = createModeOverride(modeChosen = true, chosenMode = "ultra")
        val form = savedDraft.withCreateArguments("From Home", selectedMode)
        var request: CreateSessionRequest? = null
        val vm = model(
            createSession = { request = it; Session("new-session") },
            clearDraft = {},
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
            clearDraft = {},
        )

        assertEquals("fast", savedDraft.withCreateArguments(null, noSelection).mode)
        val form = savedDraft.withCreateArguments(null, explicitDefault)
        assertEquals(null, form.mode)
        vm.submit(form, null)
        runCurrent()
        assertEquals(CreateSessionRequest(prompt = draft.prompt), request)
    }
}
