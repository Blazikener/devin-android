package com.blazikener.devin.ui.session

import com.blazikener.devin.data.ApiException
import com.blazikener.devin.data.SessionAttachment
import java.io.IOException

/** A missing index is not an empty index. Failed refreshes never erase saved rows. */
data class FilesIndex(
    val saved: List<SessionAttachment>? = null,
    val refreshing: Boolean = true,
    val error: String? = null,
    val offline: Boolean = false,
    val fromCache: Boolean = false,
) {
    val rows: List<SessionAttachment> get() = saved.orEmpty()
    val knownCount: Int? get() = saved?.size
    val confirmedEmpty: Boolean get() = saved?.isEmpty() == true && !refreshing && error == null && !fromCache

    fun loading() = copy(refreshing = true, error = null, offline = false)
    fun loaded(rows: List<SessionAttachment>) = FilesIndex(saved = rows.distinctBy { it.id }, refreshing = false)
    fun failed(cause: Throwable) = copy(
        refreshing = false,
        error = cause.userMessage() ?: "Couldn't load files. Try again.",
        offline = cause is IOException && cause !is ApiException,
    )
}
