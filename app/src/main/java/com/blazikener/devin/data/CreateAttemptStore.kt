package com.blazikener.devin.data

import java.security.MessageDigest
import kotlinx.serialization.Serializable

@Serializable
internal data class CreateAttempt(
    val fingerprint: String,
    val startedAt: Long,
    val sessionId: String? = null,
    val dismissed: Boolean = false,
    val submitted: CreateSnapshot? = null,
) {
    fun matches(prompt: String): Boolean = fingerprint == createFingerprint(prompt)
}

internal fun createFingerprint(prompt: String): String =
    MessageDigest.getInstance("SHA-256").digest(prompt.trim().encodeToByteArray()).joinToString("") { "%02x".format(it) }

internal interface CreateAttemptStore {
    fun read(): List<CreateAttempt>
    suspend fun write(attempts: List<CreateAttempt>, consumeDraftVersion: String? = null): Boolean
}

@Serializable
internal data class CreateSnapshot(
    val request: CreateSessionRequest,
    val draft: NewSessionDraft,
    val draftVersion: String,
)

internal fun NewSessionDraft.toCreateRequest(createAsUserId: String?) = CreateSessionRequest(
    prompt = prompt.trim(),
    title = title.trim().ifEmpty { null },
    tags = tags.split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinct().ifEmpty { null },
    maxAcuLimit = acu.toIntOrNull()?.takeIf { it > 0 },
    playbookId = playbookId,
    repos = repos.ifEmpty { null },
    attachmentUrls = attachments.map { it.url }.ifEmpty { null },
    createAsUserId = createAsUserId,
    devinMode = mode,
)

internal fun CreateSessionRequest.toCreateDraft() = NewSessionDraft(
    prompt = prompt,
    title = title.orEmpty(),
    tags = tags.orEmpty().joinToString(", "),
    acu = maxAcuLimit?.toString().orEmpty(),
    playbookId = playbookId,
    repos = repos.orEmpty(),
    attachments = attachmentUrls.orEmpty().map { DraftAttachment(it, it) },
    mode = devinMode,
)
