package com.blazikener.devin.ui.create

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.blazikener.devin.data.CreateAttempt
import com.blazikener.devin.data.CreateResult
import com.blazikener.devin.data.CreateSessionGate
import com.blazikener.devin.data.NewSessionDraft
import com.blazikener.devin.data.toCreateRequest
import com.blazikener.devin.data.versionKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal data class CreateSessionUi(
    val submitting: Boolean = false,
    val sessionId: String? = null,
    val error: String? = null,
    val confirmation: CreateAttempt? = null,
) {
    val uncertain: Boolean get() = confirmation != null
    val pendingDraft: NewSessionDraft? get() = confirmation?.submitted?.draft
}

internal fun CreateSessionUi.needsRetryConfirmation(form: NewSessionDraft): Boolean =
    confirmation?.matches(form.prompt) == true

internal val NewSessionDraft.hasValidAcuLimit: Boolean
    get() = acu.isBlank() || (acu.all(Char::isDigit) && (acu.toIntOrNull() ?: 0) > 0)

internal class CreateSessionViewModel(
    private val gate: CreateSessionGate,
    initialPrompt: String,
    private val isConsumed: (NewSessionDraft) -> Boolean = { false },
) : ViewModel() {
    private val _ui = MutableStateFlow(
        CreateSessionUi(
            sessionId = gate.openSaved(initialPrompt),
            confirmation = gate.attempts.value.firstOrNull {
                it.sessionId == null && !it.dismissed && it.matches(initialPrompt)
            } ?: gate.attempts.value.firstOrNull { it.sessionId == null && !it.dismissed },
        ),
    )
    val ui = _ui.asStateFlow()

    fun clearError() { _ui.value = _ui.value.copy(error = null) }

    fun acknowledgeCreated(sessionId: String) {
        viewModelScope.launch { gate.acknowledgeOpened(sessionId) }
    }

    fun resolveUncertain(form: NewSessionDraft) {
        val attempt = _ui.value.confirmation?.takeIf { it.matches(form.prompt) }
            ?: gate.attempts.value.firstOrNull { it.matches(form.prompt) } ?: return
        viewModelScope.launch {
            gate.clear(attempt)
            _ui.value = _ui.value.copy(confirmation = gate.attempts.value.firstOrNull {
                it.fingerprint == attempt.fingerprint && it.sessionId == null && !it.dismissed
            }, error = null)
        }
    }

    fun dismissWarning() {
        val attempt = _ui.value.confirmation ?: return
        viewModelScope.launch {
            gate.dismiss(attempt)
            _ui.value = _ui.value.copy(confirmation = gate.attempts.value.firstOrNull {
                it.fingerprint == attempt.fingerprint && it.sessionId == null && !it.dismissed
            }, error = null)
        }
    }

    fun submit(form: NewSessionDraft, createAsUserId: String?, confirmedRetry: Boolean = false, defaultAcuLimit: Int? = null) {
        val state = _ui.value
        if (state.submitting || state.sessionId != null) return
        val retry = state.confirmation.takeIf { confirmedRetry }
        if (confirmedRetry && retry == null) return
        if (!confirmedRetry) {
            if (form.prompt.isBlank()) {
                _ui.value = state.copy(error = "Tell Devin what to do")
                return
            }
            if (!form.hasValidAcuLimit) {
                _ui.value = state.copy(error = "Enter an ACU limit greater than 0, or leave it blank")
                return
            }
            if (isConsumed(form) && gate.openSaved(form.prompt) == null) {
                _ui.value = state.copy(error = "This draft was already started or cleared. Clear it to start a new task.")
                return
            }
        }
        _ui.value = CreateSessionUi(submitting = true)
        viewModelScope.launch {
            val request = form.toCreateRequest(createAsUserId).let {
                if (form.acu.isBlank()) it.copy(maxAcuLimit = defaultAcuLimit?.takeIf { limit -> limit > 0 }) else it
            }
            val result = try {
                if (retry == null) gate.submit(request, form, form.versionKey) else gate.retryAnyway(retry)
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                CreateResult.Failed(failure.message ?: "Couldn't start the session")
            }
            _ui.value = when (result) {
                is CreateResult.Open -> CreateSessionUi(sessionId = result.sessionId)
                is CreateResult.NeedsConfirmation -> CreateSessionUi(confirmation = result.attempt)
                is CreateResult.Failed -> CreateSessionUi(
                    error = result.message,
                    confirmation = gate.attempts.value.firstOrNull {
                        it.sessionId == null && it.matches(retry?.submitted?.request?.prompt ?: request.prompt)
                    },
                )
                CreateResult.Busy -> state.copy(error = "A session is already starting. Check Sessions and try again.")
            }
        }
    }
}
