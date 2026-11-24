package com.blazikener.devin.data

import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal sealed interface CreateResult {
    data class Open(val sessionId: String) : CreateResult
    data class NeedsConfirmation(val attempt: CreateAttempt) : CreateResult
    data class Failed(val message: String) : CreateResult
    data object Busy : CreateResult
}

/** App-scoped guard for every create entry point. An empty [attempts] list means there's nothing to recover. */
internal class CreateSessionGate(
    private val store: CreateAttemptStore,
    private val create: suspend (CreateSessionRequest) -> Session,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()
    private val state = MutableStateFlow(store.read())
    private val submitting = MutableStateFlow(false)

    /** Newest first; a saved session ID is known success, otherwise the POST may have committed. */
    val attempts = state.asStateFlow()
    val inFlight = submitting.asStateFlow()

    /** A known session can be opened without posting, then acknowledged once navigation succeeds. */
    fun openSaved(prompt: String): String? = attempts.value.firstOrNull { it.matches(prompt) }?.sessionId

    /** Ordinary sends never repeat an uncertain request for the same trimmed prompt. */
    suspend fun submit(
        request: CreateSessionRequest,
        draft: NewSessionDraft = request.toCreateDraft(),
        draftVersion: String = UUID.randomUUID().toString(),
    ): CreateResult = submit(CreateSnapshot(request, draft, draftVersion), null)

    /** Resends the saved request, not the current editor values. Confirmation applies only to this attempt. */
    suspend fun retryAnyway(attempt: CreateAttempt): CreateResult =
        attempt.submitted?.let { submit(it, attempt) } ?: CreateResult.Failed("Couldn't recover the original request.")

    /** Hides launch recovery UI without allowing an unconfirmed repeat of the same prompt. */
    suspend fun dismiss(attempt: CreateAttempt) = change(attempt) { it.copy(dismissed = true) }

    /** Explicit Clear discards only the attempt represented by the cleared form. */
    suspend fun clear(attempt: CreateAttempt) = mutex.withLock {
        if (attempt in state.value) save(state.value.filterNot { it == attempt }, attempt.submitted?.draftVersion)
    }

    suspend fun acknowledgeOpened(sessionId: String) = mutex.withLock {
        val next = state.value.filterNot { it.sessionId == sessionId }
        if (next != state.value) save(next)
    }

    suspend fun forgetAll() = mutex.withLock { save(emptyList()) }

    private suspend fun change(attempt: CreateAttempt, update: (CreateAttempt) -> CreateAttempt?) = mutex.withLock {
        if (attempt in state.value) save(state.value.mapNotNull { if (it == attempt) update(it) else it })
    }

    private suspend fun save(next: List<CreateAttempt>, consumeDraftVersion: String? = null): Boolean =
        withContext(NonCancellable) {
            store.write(next, consumeDraftVersion).also { if (it) state.value = next }
        }

    private suspend fun submit(snapshot: CreateSnapshot, confirmed: CreateAttempt?): CreateResult {
        if (!mutex.tryLock()) return CreateResult.Busy
        try {
            val request = snapshot.request
            val fingerprint = createFingerprint(request.prompt)
            val previous = state.value
            val matching = previous.firstOrNull { it.fingerprint == fingerprint }
            matching?.sessionId?.let { return CreateResult.Open(it) }
            if (matching != null && (confirmed == null || matching != confirmed)) {
                return CreateResult.NeedsConfirmation(matching)
            }
            if (confirmed != null && matching == null) return CreateResult.Busy
            submitting.value = true
            val others = previous.filterNot { it.fingerprint == fingerprint }
            val pending = CreateAttempt(fingerprint, maxOf(now(), (matching?.startedAt ?: 0) + 1), submitted = snapshot)
            if (!save(listOf(pending) + others)) {
                return CreateResult.Failed("Couldn't save the draft. The session was not started.")
            }
            val session = try {
                create(request)
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                if (failure is ApiException && failure.code in 400..499 && failure.code != 408) {
                    // A rejected retry doesn't resolve the earlier ambiguous POST.
                    save(previous)
                }
                return CreateResult.Failed(failure.message ?: "Couldn't start the session")
            }
            save(listOf(pending.copy(sessionId = session.id, submitted = null)) + others, snapshot.draftVersion)
            return CreateResult.Open(session.id)
        } finally {
            submitting.value = false
            mutex.unlock()
        }
    }
}
