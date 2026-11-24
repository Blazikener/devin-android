package com.blazikener.devin.data

internal sealed interface CreateAttempt {
    data object Pending : CreateAttempt
    data class Created(val sessionId: String) : CreateAttempt
}

internal interface CreateAttemptStore {
    fun read(): CreateAttempt?
    fun pendingDraft(): NewSessionDraft?
    fun isConsumed(draft: NewSessionDraft): Boolean
    /** Pending persists the request draft; Created consumes it only if it still owns the slot. */
    suspend fun write(attempt: CreateAttempt?, draft: NewSessionDraft? = null): Boolean
    fun acknowledgeCreated(sessionId: String)
}
