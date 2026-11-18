package com.blazikener.devin.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Page<T>(
    val items: List<T> = emptyList(),
    @SerialName("end_cursor") val endCursor: String? = null,
    @SerialName("has_next_page") val hasNextPage: Boolean = false,
    val total: Int? = null,
)

@Serializable
data class Self(
    @SerialName("principal_type") val principalType: String? = null,
    @SerialName("org_id") val orgId: String? = null,
    @SerialName("devin_sessions_org_id") val devinSessionsOrgId: String? = null,
    @SerialName("user_id") val userId: String? = null,
    @SerialName("user_name") val userName: String? = null,
    @SerialName("service_user_id") val serviceUserId: String? = null,
    @SerialName("service_user_name") val serviceUserName: String? = null,
) {
    val effectiveOrgId: String? get() = devinSessionsOrgId ?: orgId
    val displayName: String get() = userName ?: serviceUserName ?: "Devin user"
}

@Serializable
data class PullRequest(
    @SerialName("pr_url") val url: String,
    @SerialName("pr_state") val state: String? = null,
) {
    val label: String
        get() {
            val m = Regex("""github\.com/([^/]+)/([^/]+)/pull/(\d+)""").find(url)
            return if (m != null) "${m.groupValues[2]}#${m.groupValues[3]}" else url.substringAfterLast('/')
        }
}

@Serializable
data class Session(
    @SerialName("session_id") val id: String,
    val url: String = "",
    val status: String = "new",
    val title: String? = null,
    val tags: List<String> = emptyList(),
    @SerialName("playbook_id") val playbookId: String? = null,
    @SerialName("user_id") val userId: String? = null,
    @SerialName("created_at") val createdAt: Long = 0,
    @SerialName("updated_at") val updatedAt: Long = 0,
    @SerialName("is_archived") val isArchived: Boolean = false,
    @SerialName("acus_consumed") val acusConsumed: Double = 0.0,
    @SerialName("pull_requests") val pullRequests: List<PullRequest> = emptyList(),
    @SerialName("parent_session_id") val parentSessionId: String? = null,
    @SerialName("child_session_ids") val childSessionIds: List<String>? = null,
    val origin: String? = null,
    @SerialName("devin_mode") val devinMode: String? = null,
    @SerialName("status_detail") val statusDetail: String? = null,
) {
    val displayTitle: String get() = title?.takeIf { it.isNotBlank() } ?: "Untitled session"
    val state: SessionState get() = SessionState.of(status, statusDetail)
}

/** Collapses the API's status + status_detail pair into what a person cares about. */
enum class SessionState(val label: String) {
    Working("Working"),
    NeedsYou("Needs you"),
    NeedsApproval("Needs approval"),
    Sleeping("Sleeping"),
    Finished("Finished"),
    Blocked("Blocked"),
    Error("Error"),
    Starting("Starting");

    val needsAttention: Boolean get() = this == NeedsYou || this == NeedsApproval || this == Blocked
    val isActive: Boolean get() = this == Working || this == Starting

    companion object {
        private val limitDetails = setOf(
            "usage_limit_exceeded", "out_of_credits", "out_of_quota", "no_quota_allocation",
            "payment_declined", "org_usage_limit_exceeded", "user_usage_limit_exceeded",
            "total_session_limit_exceeded", "contract_expired",
        )

        fun of(status: String, detail: String?): SessionState = when {
            status == "error" || detail == "error" -> Error
            detail in limitDetails -> Blocked
            detail == "waiting_for_user" -> NeedsYou
            detail == "waiting_for_approval" -> NeedsApproval
            detail == "finished" || status == "exit" -> Finished
            detail == "inactivity" || detail == "user_request" || status == "suspended" -> Sleeping
            status == "new" || status == "claimed" || status == "resuming" -> Starting
            else -> Working
        }
    }
}

@Serializable
data class SessionMessage(
    @SerialName("event_id") val eventId: String,
    val source: String,
    val message: String,
    @SerialName("created_at") val createdAt: Long = 0,
    val username: String? = null,
    @SerialName("user_id") val userId: String? = null,
    val origin: String? = null,
) {
    val fromDevin: Boolean get() = source == "devin"
}

@Serializable
data class SessionAttachment(
    @SerialName("attachment_id") val id: String,
    val name: String,
    val url: String,
    @SerialName("content_type") val contentType: String? = null,
    val source: String = "devin",
)

@Serializable
data class UploadedAttachment(
    @SerialName("attachment_id") val id: String,
    val name: String,
    val url: String,
)

@Serializable
data class Repository(
    @SerialName("repo_name") val name: String = "",
    @SerialName("repo_path") val path: String = "",
    @SerialName("repo_full_name") val fullName: String = "",
    @SerialName("repo_description") val description: String? = null,
    @SerialName("repo_language") val language: String? = null,
)

@Serializable
data class Playbook(
    @SerialName("playbook_id") val id: String,
    val title: String,
    val macro: String? = null,
)

@Serializable
data class CreateSessionRequest(
    val prompt: String,
    val title: String? = null,
    val tags: List<String>? = null,
    @SerialName("max_acu_limit") val maxAcuLimit: Int? = null,
    @SerialName("playbook_id") val playbookId: String? = null,
    val repos: List<String>? = null,
    @SerialName("attachment_urls") val attachmentUrls: List<String>? = null,
    @SerialName("create_as_user_id") val createAsUserId: String? = null,
    @SerialName("devin_mode") val devinMode: String? = null,
)

@Serializable
data class SendMessageRequest(
    val message: String,
    @SerialName("message_as_user_id") val messageAsUserId: String? = null,
    @SerialName("attachment_urls") val attachmentUrls: List<String>? = null,
)

enum class SessionFilter(val label: String) {
    All("All"), NeedsYou("Needs you"), Working("Working"), Finished("Done"), Archived("Archived")
}

val DEVIN_MODES = listOf(
    null to "Default",
    "fast" to "Fast",
    "normal" to "Normal",
    "fusion" to "Fusion",
    "lite" to "Lite",
    "ultra" to "Ultra",
)
