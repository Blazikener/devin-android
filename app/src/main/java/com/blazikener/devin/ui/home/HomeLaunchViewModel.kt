package com.blazikener.devin.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.blazikener.devin.data.CreateAttempt
import com.blazikener.devin.data.CreateResult
import com.blazikener.devin.data.CreateSessionGate
import com.blazikener.devin.data.CreateSessionRequest
import com.blazikener.devin.data.createFingerprint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal data class HomeLaunchState(
    val busy: Boolean = false,
    val error: String? = null,
    val createdId: String? = null,
    val createdFingerprint: String? = null,
    val confirmation: CreateAttempt? = null,
)

/** Survives rotation without reissuing a create. Recomposition never submits work. */
internal class HomeLaunchViewModel(private val gate: CreateSessionGate) : ViewModel() {
    private val _state = MutableStateFlow(HomeLaunchState())
    val state = _state.asStateFlow()
    val attempts = gate.attempts
    val inFlight = gate.inFlight

    fun start(request: CreateSessionRequest) {
        submit(request)
    }

    fun retryAnyway(attempt: CreateAttempt) {
        val request = attempt.submitted?.request ?: return
        submit(request, attempt)
    }

    private fun submit(request: CreateSessionRequest, retry: CreateAttempt? = null) {
        if (request.prompt.isBlank() || _state.value.busy || _state.value.createdId != null) return
        _state.value = HomeLaunchState(busy = true)
        viewModelScope.launch {
            try {
                val result = if (retry == null) gate.submit(request) else gate.retryAnyway(retry)
                _state.value = when (result) {
                    is CreateResult.Open -> HomeLaunchState(createdId = result.sessionId, createdFingerprint = createFingerprint(request.prompt))
                    is CreateResult.NeedsConfirmation -> HomeLaunchState(confirmation = result.attempt)
                    is CreateResult.Failed -> HomeLaunchState(error = result.message)
                    CreateResult.Busy -> HomeLaunchState(error = "Another session is starting. Please wait.")
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Exception) {
                _state.value = HomeLaunchState(error = failure.message ?: "Couldn't start the session")
            }
        }
    }

    fun dismiss(attempt: CreateAttempt) {
        viewModelScope.launch {
            gate.dismiss(attempt)
            _state.value = _state.value.copy(confirmation = null, error = null)
        }
    }

    fun clearPrompt() {
        _state.value = HomeLaunchState()
    }

    fun clearAttempt(attempt: CreateAttempt) {
        clearPrompt()
        viewModelScope.launch { gate.clear(attempt) }
    }

    fun openSaved(attempt: CreateAttempt) {
        val id = attempt.sessionId ?: return
        if (!_state.value.busy) _state.value = HomeLaunchState(createdId = id, createdFingerprint = attempt.fingerprint)
    }

    fun consumeCreated(id: String) {
        _state.value = HomeLaunchState()
        viewModelScope.launch { gate.acknowledgeOpened(id) }
    }
}
