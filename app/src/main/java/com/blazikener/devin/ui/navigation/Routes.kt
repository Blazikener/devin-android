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
    const val Tab = "session_tab"
    const val FileId = "reader_file_id"
    const val FileVersion = "reader_file_version"
    const val ReadingAnchor = "reader_anchor"
}
