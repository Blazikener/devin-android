package com.blazikener.devin.ui.create

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
)

internal val NewSessionDraft.hasValidAcuLimit: Boolean
    get() = acu.isBlank() || (acu.all(Char::isDigit) && (acu.toIntOrNull() ?: 0) > 0)

internal class CreateSessionViewModel(
    private val createSession: suspend (CreateSessionRequest) -> Session,
    private val clearDraft: () -> Unit,
) : ViewModel() {
    private val _ui = MutableStateFlow(CreateSessionUi())
    val ui = _ui.asStateFlow()

    fun clearError() {
        _ui.value = _ui.value.copy(error = null)
    }

    fun submit(form: NewSessionDraft, createAsUserId: String?) {
        if (_ui.value.submitting || _ui.value.sessionId != null) return
        val prompt = form.prompt.trim()
        if (prompt.isEmpty()) {
            _ui.value = CreateSessionUi(error = "Tell Devin what to do")
            return
        }
        if (!form.hasValidAcuLimit) {
            _ui.value = CreateSessionUi(error = "Enter an ACU limit greater than 0, or leave it blank")
            return
        }

        _ui.value = CreateSessionUi(submitting = true)
        viewModelScope.launch {
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
                _ui.value = CreateSessionUi(error = failure.message ?: "Couldn't start the session")
                return@launch
            }
            clearDraft()
            _ui.value = CreateSessionUi(sessionId = session.id)
        }
    }
}
