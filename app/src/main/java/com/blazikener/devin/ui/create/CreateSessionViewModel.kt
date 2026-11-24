package com.blazikener.devin.ui.create

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.blazikener.devin.data.ApiException
import com.blazikener.devin.data.CreateAttempt
import com.blazikener.devin.data.CreateAttemptStore
import com.blazikener.devin.data.CreateSessionRequest
import com.blazikener.devin.data.NewSessionDraft
import com.blazikener.devin.data.Session
import com.blazikener.devin.data.versionKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal data class CreateSessionUi(
    val submitting: Boolean = false,
    val sessionId: String? = null,
    val error: String? = null,
    val uncertain: Boolean = false,
    val pendingDraft: NewSessionDraft? = null,
)

internal fun CreateSessionUi.needsRetryConfirmation(form: NewSessionDraft): Boolean =
    uncertain && (pendingDraft == null || pendingDraft.prompt.trim() == form.prompt.trim())

internal val NewSessionDraft.hasValidAcuLimit: Boolean
    get() = acu.isBlank() || (acu.all(Char::isDigit) && (acu.toIntOrNull() ?: 0) > 0)

internal class CreateSessionViewModel(
    private val createSession: suspend (CreateSessionRequest) -> Session,
    private val attempts: CreateAttemptStore,
) : ViewModel() {
    private val _ui = MutableStateFlow(
        when (val attempt = attempts.read()) {
            null -> CreateSessionUi()
            CreateAttempt.Pending -> CreateSessionUi(uncertain = true, pendingDraft = attempts.pendingDraft())
            is CreateAttempt.Created -> CreateSessionUi(sessionId = attempt.sessionId)
        },
    )
    val ui = _ui.asStateFlow()

    fun clearError() {
        _ui.value = _ui.value.copy(error = null)
    }

    fun acknowledgeCreated(sessionId: String) = attempts.acknowledgeCreated(sessionId)

    fun resolveUncertain(form: NewSessionDraft) {
        val state = _ui.value
        if (!state.uncertain || state.submitting) return
        viewModelScope.launch {
            if (attempts.write(null, state.pendingDraft ?: form)) _ui.value = CreateSessionUi()
            else _ui.value = state.copy(pendingDraft = attempts.pendingDraft(), error = "A newer start is unresolved. Check Sessions before clearing its warning.")
        }
    }

    fun submit(form: NewSessionDraft, createAsUserId: String?, confirmedRetry: Boolean = false, defaultAcuLimit: Int? = null) {
        val state = _ui.value
        if (state.submitting || state.sessionId != null || (confirmedRetry && !state.uncertain)) return
        val source = if (confirmedRetry) {
            val pending = attempts.pendingDraft()
            if (pending != null && pending.versionKey != (state.pendingDraft ?: form).versionKey) {
                _ui.value = state.copy(pendingDraft = pending, error = "A newer start is unresolved. Review the previous task before retrying.")
                return
            }
            pending ?: form
        } else form
        if (attempts.isConsumed(source)) {
            _ui.value = state.copy(error = "This draft was already started or cleared. Clear it to start a new task.")
            return
        }
        if (!confirmedRetry && attempts.read() == CreateAttempt.Pending) {
            val pending = attempts.pendingDraft()
            if (pending == null || pending.prompt.trim() == form.prompt.trim()) {
                _ui.value = state.copy(uncertain = true, pendingDraft = pending)
                return
            }
        }
        val prompt = source.prompt.trim()
        if (prompt.isEmpty()) {
            _ui.value = state.copy(error = "Tell Devin what to do")
            return
        }
        if (!source.hasValidAcuLimit) {
            _ui.value = state.copy(error = "Enter an ACU limit greater than 0, or leave it blank")
            return
        }

        _ui.value = CreateSessionUi(submitting = true)
        viewModelScope.launch {
            // Resolve the configured default once so a restored retry sends the same limit.
            val submitted = if (source.acu.isBlank() && (defaultAcuLimit ?: 0) > 0) {
                source.copy(acu = defaultAcuLimit.toString(), draftVersion = source.versionKey)
            } else source
            if (!attempts.write(CreateAttempt.Pending, submitted)) {
                _ui.value = state.copy(error = "Couldn't save the draft. The session was not started.")
                return@launch
            }
            val session = try {
                createSession(
                    CreateSessionRequest(
                        prompt = prompt,
                        title = submitted.title.trim().ifEmpty { null },
                        tags = submitted.tags.split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinct().ifEmpty { null },
                        maxAcuLimit = submitted.acu.toIntOrNull()?.takeIf { it > 0 },
                        playbookId = submitted.playbookId,
                        repos = submitted.repos.ifEmpty { null },
                        attachmentUrls = submitted.attachments.map { it.url }.ifEmpty { null },
                        createAsUserId = createAsUserId,
                        devinMode = submitted.mode,
                    ),
                )
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                val rejected = failure is ApiException && failure.code in 400..499 && failure.code != 408 && attempts.write(null, submitted)
                _ui.value = CreateSessionUi(error = failure.message ?: "Couldn't start the session", uncertain = !rejected, pendingDraft = if (rejected) null else attempts.pendingDraft() ?: submitted)
                return@launch
            }
            attempts.write(CreateAttempt.Created(session.id), submitted)
            _ui.value = CreateSessionUi(sessionId = session.id)
        }
    }
}
