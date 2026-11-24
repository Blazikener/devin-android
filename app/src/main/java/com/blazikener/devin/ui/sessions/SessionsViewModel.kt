package com.blazikener.devin.ui.sessions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.blazikener.devin.AppGraph
import com.blazikener.devin.data.Page
import com.blazikener.devin.data.Session
import com.blazikener.devin.data.SessionFilter
import com.blazikener.devin.data.SessionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class SessionFeedKey(val mine: Boolean, val archived: Boolean) {
    val cacheKey: String get() = (if (mine) "mine" else "org") + if (archived) "_archived" else ""
}

internal data class SessionFeed(
    val items: List<Session> = emptyList(),
    val initialized: Boolean = false,
    val cursor: String? = null,
    val refreshing: Boolean = false,
    val manualRefresh: Boolean = false,
    val loadingMore: Boolean = false,
    val error: String? = null,
    val pageError: String? = null,
    val revision: Int = 0,
) {
    val loading: Boolean get() = !initialized
    val offline: Boolean get() = initialized && error != null
    val hasMore: Boolean get() = cursor != null
}

internal data class SessionsUi(
    val feeds: Map<SessionFeedKey, SessionFeed> = emptyMap(),
    val filter: SessionFilter = SessionFilter.All,
    val query: String = "",
    val mineOnly: Boolean = true,
    val pinned: Set<String> = emptySet(),
) {
    val key: SessionFeedKey get() = SessionFeedKey(mineOnly, filter == SessionFilter.Archived)
    val feed: SessionFeed get() = feed(key)
    val all: List<Session> get() = feed(SessionFeedKey(mineOnly, false)).items
    fun feed(key: SessionFeedKey): SessionFeed = feeds[key] ?: SessionFeed()

    val visible: List<Session>
        get() {
            val q = query.trim().lowercase()
            return feed.items.filter { s ->
                when (filter) {
                    SessionFilter.NeedsYou -> s.state.needsAttention
                    SessionFilter.Working -> s.state.isActive
                    SessionFilter.Finished -> s.state == SessionState.Finished || s.state == SessionState.Sleeping
                    else -> true
                } && (q.isEmpty() || s.displayTitle.lowercase().contains(q) || s.id.contains(q, ignoreCase = true) ||
                    s.tags.any { it.lowercase().contains(q) } || s.pullRequests.any { it.url.lowercase().contains(q) })
            }.sortedWith(compareByDescending<Session> { it.id in pinned }.thenByDescending { it.updatedAt })
        }
    val needsYouCount: Int get() = all.count { it.state.needsAttention }
    val workingCount: Int get() = all.count { it.state.isActive }
}

internal class SessionsViewModel(
    private val userId: String?,
    private val fetch: suspend (first: Int, after: String?, archived: Boolean, userIds: List<String>?) -> Page<Session>,
    private val readCache: suspend (String) -> List<Session>?,
    private val writeCache: suspend (String, List<Session>) -> Unit,
    pinned: StateFlow<Set<String>>,
    private val changePin: (String) -> Unit,
    private val perform: suspend (String, SessionAction) -> Session,
    private val savedState: SavedStateHandle = SavedStateHandle(),
) : ViewModel() {
    constructor(g: AppGraph, savedState: SavedStateHandle = SavedStateHandle(), onActed: (Session) -> Unit) : this(
        userId = g.auth.account.value?.userId,
        fetch = { first, after, archived, users -> g.api.listSessions(first, after, archived, users) },
        readCache = g.cache::sessions,
        writeCache = { key, sessions -> g.cache.putSessions(key, sessions) },
        pinned = g.settings.pinned,
        changePin = g.settings::togglePin,
        perform = { id, action ->
            when (action) {
                SessionAction.Archive -> g.api.archive(id)
                SessionAction.Unarchive -> g.api.unarchive(id)
                SessionAction.Sleep -> g.api.sleep(id)
                SessionAction.Terminate -> g.api.terminate(id)
            }.also(onActed)
        },
        savedState = savedState,
    )

    private val _ui = MutableStateFlow(SessionsUi(
        mineOnly = userId != null && (savedState.get<Boolean>("mine") ?: true),
        filter = savedState.get<String>("filter")?.let { value -> SessionFilter.entries.find { it.name == value } } ?: SessionFilter.All,
        query = savedState["query"] ?: "",
    ))
    val ui: StateFlow<SessionsUi> = _ui
    private val cacheWrites = Mutex()

    init {
        viewModelScope.launch { pinned.collect { p -> _ui.update { it.copy(pinned = p) } } }
        openFeed(_ui.value.key)
    }

    private fun updateFeed(key: SessionFeedKey, change: (SessionFeed) -> SessionFeed) {
        _ui.update { it.copy(feeds = it.feeds + (key to change(it.feed(key)))) }
    }

    private fun openFeed(key: SessionFeedKey) {
        viewModelScope.launch { refresh(key) }
    }

    private suspend fun page(key: SessionFeedKey, first: Int, cursor: String?) =
        fetch(first, cursor, key.archived, if (key.mine) userId?.let(::listOf) else null)

    private suspend fun persist(key: SessionFeedKey) = cacheWrites.withLock {
        writeCache(key.cacheKey, _ui.value.feed(key).items)
    }

    private suspend fun readScopedCache(key: SessionFeedKey): List<Session>? =
        readCache(key.cacheKey)?.filter { !key.mine || it.userId == null || it.userId == userId }

    fun setFilter(f: SessionFilter) {
        savedState["filter"] = f.name
        val previous = _ui.value.key
        _ui.update { it.copy(filter = f) }
        val key = _ui.value.key
        if (key != previous) openFeed(key)
    }

    fun setQuery(q: String) {
        savedState["query"] = q
        _ui.update { it.copy(query = q) }
    }

    fun toggleMine() {
        if (userId == null) return
        _ui.update { it.copy(mineOnly = !it.mineOnly) }
        savedState["mine"] = _ui.value.mineOnly
        openFeed(_ui.value.key)
    }

    fun pullRefresh() {
        val key = _ui.value.key
        viewModelScope.launch { refresh(key, manual = true) }
    }

    suspend fun refresh() = refresh(_ui.value.key, background = true)

    private suspend fun refresh(key: SessionFeedKey, background: Boolean = false, manual: Boolean = false) {
        val current = _ui.value.feed(key)
        if (current.refreshing || current.loadingMore) return
        updateFeed(key) { it.copy(refreshing = true, manualRefresh = manual) }
        try {
            if (!current.initialized) {
                cacheWrites.withLock {
                    if (!_ui.value.feed(key).initialized) {
                        readScopedCache(key)?.let { cached ->
                            updateFeed(key) { it.copy(items = cached, initialized = true) }
                        }
                    }
                }
            }
            val items = mutableListOf<Session>()
            val before = _ui.value.feed(key)
            val target = maxOf(PAGE_SIZE, before.items.size)
            var cursor: String? = null
            do {
                val next = page(key, (target - items.size).coerceIn(PAGE_SIZE, MAX_PAGE_SIZE), cursor)
                items += next.items
                cursor = next.nextCursor(cursor)
            } while (cursor != null && items.size < target)
            if (_ui.value.feed(key).revision != before.revision) return
            updateFeed(key) {
                it.copy(items = items.distinctBy(Session::id), initialized = true, cursor = cursor, error = null,
                    pageError = if (background) it.pageError?.takeIf { cursor != null } else null)
            }
            persist(key)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            updateFeed(key) { it.copy(error = e.message ?: "Couldn't load sessions") }
        } finally {
            updateFeed(key) { it.copy(refreshing = false, manualRefresh = false) }
        }
    }

    fun loadMore(retry: Boolean = false) {
        val key = _ui.value.key
        val current = _ui.value.feed
        val cursor = current.cursor ?: return
        if (current.refreshing || current.loadingMore || current.offline || (!retry && current.pageError != null)) return
        updateFeed(key) { it.copy(loadingMore = true, pageError = null) }
        viewModelScope.launch {
            try {
                val next = page(key, PAGE_SIZE, cursor)
                if (_ui.value.feed(key).revision != current.revision) return@launch
                updateFeed(key) {
                    it.copy(items = (it.items + next.items).distinctBy(Session::id), cursor = next.nextCursor(cursor))
                }
                persist(key)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                updateFeed(key) { it.copy(pageError = e.message ?: "Couldn't load more sessions") }
            } finally {
                updateFeed(key) { it.copy(loadingMore = false) }
            }
        }
    }

    fun togglePin(id: String) = changePin(id)

    fun act(id: String, action: SessionAction, onDone: (String) -> Unit) {
        val origin = _ui.value
        val row = origin.feed.items.find { it.id == id }
        viewModelScope.launch {
            try {
                val updated = perform(id, action)
                updateAfterAction(updated, action, row?.userId ?: userId.takeIf { origin.mineOnly })
                onDone(action.done)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onDone(e.message ?: "Failed")
            }
        }
    }

    private suspend fun updateAfterAction(session: Session, action: SessionAction, owner: String?) = cacheWrites.withLock {
        val moved = action == SessionAction.Archive || action == SessionAction.Unarchive
        val ownership = session.userId ?: owner
        val keys = (if (userId == null) listOf(false) else listOf(false, true)).flatMap { mine ->
            (if (moved) listOf(false, true) else listOf(session.isArchived)).map { archived -> SessionFeedKey(mine, archived) }
        }
        val cached = keys.filterNot { _ui.value.feed(it).initialized }.associateWith { readScopedCache(it) }
        val current = _ui.value
        val previous = keys.associateWith { key ->
            current.feed(key).takeIf { it.initialized }?.items ?: cached[key].orEmpty()
        }
        val wasInMine = previous.any { (key, rows) -> key.mine && rows.any { it.id == session.id } }
        val belongsInMine = userId != null && (ownership == userId || (ownership == null && wasInMine))
        val updated = buildMap {
            previous.forEach { (key, rows) ->
                val items = when {
                    !moved -> rows.map { if (it.id == session.id) session else it }
                    key.archived == session.isArchived && (!key.mine || belongsInMine) ->
                        listOf(session) + rows.filterNot { it.id == session.id }
                    else -> rows.filterNot { it.id == session.id }
                }
                if (items != rows) {
                    val feed = current.feed(key)
                    put(key, feed.copy(items = items, initialized = true, revision = feed.revision + 1))
                }
            }
        }
        _ui.update { it.copy(feeds = it.feeds + updated) }
        updated.forEach { (key, feed) -> writeCache(key.cacheKey, feed.items) }
    }

    private fun Page<Session>.nextCursor(previous: String?): String? =
        endCursor?.takeIf { hasNextPage && items.isNotEmpty() && it != previous }

    private companion object {
        const val PAGE_SIZE = 50
        const val MAX_PAGE_SIZE = 200
    }
}

enum class SessionAction(val label: String, val done: String) {
    Archive("Archive", "Archived"), Unarchive("Unarchive", "Restored"),
    Sleep("Put to sleep", "Session is going to sleep"), Terminate("Terminate", "Session terminated"),
}
