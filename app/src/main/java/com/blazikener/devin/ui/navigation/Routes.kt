package com.blazikener.devin.ui.navigation

import com.blazikener.devin.data.SessionFilter
import java.net.URLEncoder

/** All navigation paths live here. IDs are opaque, encoded path segments, not URLs. */
object Routes {
    const val Login = "login"
    const val Home = "home"
    const val Settings = "settings"
    const val Sessions = "sessions?filter={filter}&search={search}"
    const val Session = "session/{id}"
    const val Create = "create?prompt={prompt}&mode={mode}"
    // Reserved for the Reader implementation. Origin is the opening tab, not a new session.
    const val Reader = "reader/{id}?file={file}&version={version}&origin={origin}"

    fun sessions(filter: SessionFilter? = null, search: Boolean = false) =
        "sessions?filter=${filter?.name.orEmpty()}&search=$search"
    fun session(id: String) = "session/${encode(id)}"
    fun create(prompt: String = "", modeOverride: String? = null) =
        "create?prompt=${encode(prompt)}" + (modeOverride?.let { "&mode=${encode(it)}" } ?: "")
    fun reader(id: String, file: String, version: String = "", origin: SessionTab = SessionTab.Chat) =
        "reader/${encode(id)}?file=${encode(file)}&version=${encode(version)}&origin=${origin.name}"

    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}

enum class RootDestination { Home, Sessions }
enum class SessionTab { Chat, Files }

/** Keys shared by Chat/Files and Reader; keep content/URLs out of the route and saved state. */
object SessionNavigationState {
    const val SessionsRequest = "sessions_navigation_request"
    const val Tab = "session_tab"
    const val FileId = "reader_file_id"
    const val FileVersion = "reader_file_version"
    const val ReadingAnchor = "reader_anchor"
}

/** A one-shot override on a restored list; other query/owner/scroll state stays intact. */
data class SessionsRequest(val filter: SessionFilter? = null, val search: Boolean = false, val recoverCreate: Boolean = false) {
    fun encode() = "${filter?.name.orEmpty()}|$search" + if (recoverCreate) "|recover" else ""

    companion object {
        fun decode(value: String?): SessionsRequest? {
            val parts = value?.split('|', limit = 3) ?: return null
            if (parts.size !in 2..3 || (parts.size == 3 && parts[2] != "recover")) return null
            val filter = parts[0].takeIf(String::isNotEmpty)?.let { name ->
                SessionFilter.entries.firstOrNull { it.name == name } ?: return null
            }
            return SessionsRequest(filter, parts[1].toBooleanStrictOrNull() ?: return null, parts.size == 3)
        }
    }
}
