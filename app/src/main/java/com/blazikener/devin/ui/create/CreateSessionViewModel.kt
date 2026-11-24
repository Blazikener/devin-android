package com.blazikener.devin.ui.create

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.blazikener.devin.data.CreateAttempt
import com.blazikener.devin.data.CreateResult
import com.blazikener.devin.data.CreateSessionGate
import com.blazikener.devin.data.NewSessionDraft
import com.blazikener.devin.data.toCreateRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal data class CreateSessionUi(
    val submitting: Boolean = false,
    val sessionId: String? = null,
    val error: String? = null,
    val confirmation: CreateAttempt? = null,
)

internal class CreateSessionViewModel(private val gate: CreateSessionGate) : ViewModel() {
    private val _ui = MutableStateFlow(CreateSessionUi())
    val ui = _ui.asStateFlow()

    fun clearError() {
        _ui.value = _ui.value.copy(error = null, confirmation = null)
    }

    fun consumeResult() {
        _ui.value = CreateSessionUi()
    }

    fun dismiss(attempt: CreateAttempt) {
        clearError()
        viewModelScope.launch { gate.dismiss(attempt) }
    }

    fun clear(attempt: CreateAttempt?) {
        clearError()
        if (attempt != null) viewModelScope.launch { gate.clear(attempt) }
    }

    fun submit(form: NewSessionDraft, createAsUserId: String?, retry: CreateAttempt? = null, draftVersion: String? = null) {
        val state = _ui.value
        if (state.submitting || state.sessionId != null) return
        _ui.value = CreateSessionUi(submitting = true)
        viewModelScope.launch {
            val request = form.toCreateRequest(createAsUserId)
            val result = when {
                retry != null -> gate.retryAnyway(retry)
                request.prompt.isEmpty() -> CreateResult.Failed("Tell Devin what to do")
                draftVersion != null -> gate.submit(request, form, draftVersion)
                else -> gate.submit(request, form)
            }
            _ui.value = when (result) {
                is CreateResult.Open -> CreateSessionUi(sessionId = result.sessionId)
                is CreateResult.NeedsConfirmation -> CreateSessionUi(confirmation = result.attempt)
                is CreateResult.Failed -> CreateSessionUi(error = result.message)
                CreateResult.Busy -> CreateSessionUi(error = "A session is already starting. Check Recents and try again.")
            }
        }
    }
}
