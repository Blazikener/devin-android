package com.blazikener.devin.ui.sessions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.blazikener.devin.AppGraph
import com.blazikener.devin.data.Session
import com.blazikener.devin.data.SessionFilter
import com.blazikener.devin.data.SessionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SessionsUi(
    val all: List<Session> = emptyList(),
    val archived: List<Session> = emptyList(),
    val filter: SessionFilter = SessionFilter.All,
    val query: String = "",
    val mineOnly: Boolean = true,
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val loadingMore: Boolean = false,
    val hasMore: Boolean = false,
    val error: String? = null,
    val offline: Boolean = false,
    val pinned: Set<String> = emptySet(),
) {
    val visible: List<Session>
        get() {
            val src = if (filter == SessionFilter.Archived) archived else all
            val q = query.trim().lowercase()
            return src.filter { s ->
                when (filter) {
                    SessionFilter.NeedsYou -> s.state.needsAttention
                    SessionFilter.Working -> s.state.isActive
                    SessionFilter.Finished -> s.state == SessionState.Finished || s.state == SessionState.Sleeping
                    else -> true
                } && (q.isEmpty() || s.displayTitle.lowercase().contains(q) || s.id.contains(q) ||
                    s.tags.any { it.lowercase().contains(q) } || s.pullRequests.any { it.url.lowercase().contains(q) })
            }.sortedWith(compareByDescending<Session> { it.id in pinned }.thenByDescending { it.updatedAt })
        }
    val needsYouCount: Int get() = all.count { it.state.needsAttention }
    val workingCount: Int get() = all.count { it.state.isActive }
}

class SessionsViewModel(private val g: AppGraph) : ViewModel() {
    private val _ui = MutableStateFlow(SessionsUi(mineOnly = g.auth.account.value?.userId != null))
    val ui: StateFlow<SessionsUi> = _ui
    private var cursor: String? = null
    private var archivedCursor: String? = null

    init {
        viewModelScope.launch { g.settings.pinned.collect { p -> _ui.update { it.copy(pinned = p) } } }
        viewModelScope.launch {
            g.cache.sessions(cacheKey())?.let { c -> _ui.update { it.copy(all = c, loading = false) } }
            refresh()
        }
    }

    private fun cacheKey() = if (_ui.value.mineOnly) "mine" else "org"
    private fun userFilter() = if (_ui.value.mineOnly) g.auth.account.value?.userId?.let { listOf(it) } else null

    fun setFilter(f: SessionFilter) {
        _ui.update { it.copy(filter = f) }
        if (f == SessionFilter.Archived && _ui.value.archived.isEmpty()) viewModelScope.launch { refresh() }
    }

    fun setQuery(q: String) = _ui.update { it.copy(query = q) }

    fun toggleMine() {
        _ui.update { it.copy(mineOnly = !it.mineOnly, all = emptyList(), archived = emptyList(), loading = true) }
        viewModelScope.launch { refresh() }
    }

    fun pullRefresh() {
        _ui.update { it.copy(refreshing = true) }
        viewModelScope.launch { refresh(); _ui.update { it.copy(refreshing = false) } }
    }

    suspend fun refresh() {
        val current = _ui.value
        val archived = current.filter == SessionFilter.Archived
        val first = if (archived) {
            maxOf(50, current.archived.size).coerceAtMost(200)
        } else {
            maxOf(50, current.all.size).coerceAtMost(200)
        }
        runCatching { g.api.listSessions(first = first, archived = archived, userIds = userFilter()) }
            .onSuccess { page ->
                if (archived) {
                    archivedCursor = page.endCursor
                    _ui.update { it.copy(archived = page.items, hasMore = page.hasNextPage, loading = false, error = null, offline = false) }
                } else {
                    cursor = page.endCursor
                    _ui.update { it.copy(all = page.items, hasMore = page.hasNextPage, loading = false, error = null, offline = false) }
                    g.cache.putSessions(cacheKey(), page.items)
                }
            }
            .onFailure { e -> _ui.update { it.copy(loading = false, error = e.message, offline = it.all.isNotEmpty()) } }
    }

    fun loadMore() {
        val s = _ui.value
        if (s.loadingMore || !s.hasMore) return
        val archived = s.filter == SessionFilter.Archived
        _ui.update { it.copy(loadingMore = true) }
        viewModelScope.launch {
            runCatching { g.api.listSessions(first = 50, after = if (archived) archivedCursor else cursor, archived = archived, userIds = userFilter()) }
                .onSuccess { page ->
                    if (archived) archivedCursor = page.endCursor else cursor = page.endCursor
                    _ui.update {
                        if (archived) it.copy(archived = (it.archived + page.items).distinctBy { x -> x.id }, hasMore = page.hasNextPage, loadingMore = false)
                        else it.copy(all = (it.all + page.items).distinctBy { x -> x.id }, hasMore = page.hasNextPage, loadingMore = false)
                    }
                }
                .onFailure { e -> _ui.update { it.copy(loadingMore = false, hasMore = false, error = e.message) } }
        }
    }

    fun togglePin(id: String) = g.settings.togglePin(id)

    fun act(id: String, action: SessionAction, onDone: (String) -> Unit) {
        viewModelScope.launch {
            runCatching {
                when (action) {
                    SessionAction.Archive -> g.api.archive(id)
                    SessionAction.Unarchive -> g.api.unarchive(id)
                    SessionAction.Sleep -> g.api.sleep(id)
                    SessionAction.Terminate -> g.api.terminate(id)
                }
            }.onSuccess { updated ->
                _ui.update { u ->
                    if (action == SessionAction.Archive) u.copy(all = u.all.filterNot { it.id == id }, archived = listOf(updated) + u.archived)
                    else if (action == SessionAction.Unarchive) u.copy(archived = u.archived.filterNot { it.id == id }, all = listOf(updated) + u.all)
                    else u.copy(all = u.all.map { if (it.id == id) updated else it })
                }
                onDone(action.done)
            }.onFailure { onDone(it.message ?: "Failed") }
        }
    }
}

enum class SessionAction(val label: String, val done: String) {
    Archive("Archive", "Archived"), Unarchive("Unarchive", "Restored"),
    Sleep("Put to sleep", "Session is going to sleep"), Terminate("Terminate", "Session terminated"),
}
