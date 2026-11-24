package com.blazikener.devin.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.blazikener.devin.data.CreateSessionRequest
import com.blazikener.devin.data.Session
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal data class HomeLaunchState(
    val busy: Boolean = false,
    val error: String? = null,
    val createdId: String? = null,
)

/** Survives rotation without reissuing a create. Recomposition never submits work. */
internal class HomeLaunchViewModel(private val create: suspend (CreateSessionRequest) -> Session) : ViewModel() {
    private val _state = MutableStateFlow(HomeLaunchState())
    val state = _state.asStateFlow()

    fun start(request: CreateSessionRequest) {
        if (request.prompt.isBlank() || _state.value.busy || _state.value.createdId != null) return
        _state.value = HomeLaunchState(busy = true)
        viewModelScope.launch {
            try {
                _state.value = HomeLaunchState(createdId = create(request).id)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Exception) {
                _state.value = HomeLaunchState(error = failure.message ?: "Couldn't start the session")
            }
        }
    }

    fun consumeCreated() { _state.value = HomeLaunchState() }
}
