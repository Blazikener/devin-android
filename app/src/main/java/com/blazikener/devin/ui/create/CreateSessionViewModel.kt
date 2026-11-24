package com.blazikener.devin.ui.create

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.blazikener.devin.data.ApiException
import com.blazikener.devin.data.CreateAttempt
import com.blazikener.devin.data.CreateAttemptStore
import com.blazikener.devin.data.CreateSessionRequest
import com.blazikener.devin.data.NewSessionDraft
import com.blazikener.devin.data.Session
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal data class CreateSessionUi(
    val submitting: Boolean = false,
    val sessionId: String? = null,
    val error: String? = null,
    val uncertain: Boolean = false,
)

internal class CreateSessionViewModel(
    private val createSession: suspend (CreateSessionRequest) -> Session,
    private val attempts: CreateAttemptStore,
) : ViewModel() {
    private val _ui = MutableStateFlow(
        when (val attempt = attempts.read()) {
            null -> CreateSessionUi()
            CreateAttempt.Pending -> CreateSessionUi(uncertain = true)
            is CreateAttempt.Created -> CreateSessionUi(sessionId = attempt.sessionId)
        },
    )
    val ui = _ui.asStateFlow()

    fun clearError() {
        _ui.value = _ui.value.copy(error = null)
    }

    fun acknowledgeCreated() = attempts.acknowledgeCreated()

    fun submit(form: NewSessionDraft, createAsUserId: String?, confirmedRetry: Boolean = false) {
        val state = _ui.value
        if (state.submitting || state.sessionId != null || state.uncertain != confirmedRetry) return
        val prompt = form.prompt.trim()
        if (prompt.isEmpty()) {
            _ui.value = state.copy(error = "Tell Devin what to do")
            return
        }

        _ui.value = CreateSessionUi(submitting = true)
        viewModelScope.launch {
            if (!attempts.write(CreateAttempt.Pending, form)) {
                _ui.value = state.copy(error = "Couldn't save the draft. The session was not started.")
                return@launch
            }
            val session = try {
                createSession(
                    CreateSessionRequest(
                        prompt = prompt,
                        title = form.title.trim().ifEmpty { null },
                        tags = form.tags.split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinct().ifEmpty { null },
                        maxAcuLimit = form.acu.toIntOrNull()?.takeIf { it > 0 },
                        playbookId = form.playbookId,
                        repos = form.repos.ifEmpty { null },
                        attachmentUrls = form.attachments.map { it.url }.ifEmpty { null },
                        createAsUserId = createAsUserId,
                        devinMode = form.mode,
                    ),
                )
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                val rejected = failure is ApiException && failure.code in 400..599 && attempts.write(null)
                _ui.value = CreateSessionUi(error = failure.message ?: "Couldn't start the session", uncertain = !rejected)
                return@launch
            }
            attempts.write(CreateAttempt.Created(session.id), NewSessionDraft())
            _ui.value = CreateSessionUi(sessionId = session.id)
        }
    }
}
