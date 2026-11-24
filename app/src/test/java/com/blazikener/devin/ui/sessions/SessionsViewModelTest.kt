package com.blazikener.devin.ui.sessions

import androidx.lifecycle.viewModelScope
import androidx.lifecycle.SavedStateHandle
import com.blazikener.devin.data.Page
import com.blazikener.devin.data.Session
import com.blazikener.devin.data.SessionFilter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class SessionsViewModelTest {
    private val models = mutableListOf<SessionsViewModel>()
    private val cache = mutableMapOf<String, List<Session>>()
    private val mine = Session("mine", userId = "me")
    private val other = Session("other", userId = "someone-else")
    private val archived = Session("archived", userId = "me", isArchived = true)

    @Before
    fun setUp() = Dispatchers.setMain(StandardTestDispatcher())

    @After
    fun tearDown() {
        models.forEach { it.viewModelScope.cancel() }
        Dispatchers.resetMain()
    }

    private fun model(
        perform: suspend (String, SessionAction) -> Session = { _, _ -> error("Unexpected action") },
        savedState: SavedStateHandle = SavedStateHandle(),
        fetch: suspend (Int, String?, Boolean, List<String>?) -> Page<Session>,
    ) = SessionsViewModel(
        userId = "me",
        fetch = fetch,
        readCache = { cache[it] },
        writeCache = { key, items -> cache[key] = items },
        pinned = MutableStateFlow(emptySet()),
        changePin = {},
        perform = perform,
        savedState = savedState,
    ).also { models.add(it) }

    @Test
    fun recreatedModelKeepsQueryOwnerAndNeedsYouFilter() = runTest {
        val saved = SavedStateHandle()
        val vm = model(savedState = saved) { _, _, _, _ -> Page(listOf(mine, other)) }
        runCurrent()
        vm.setQuery("retry")
        vm.setFilter(SessionFilter.NeedsYou)
        vm.toggleMine()
        runCurrent()
        val restored = model(savedState = SavedStateHandle(mapOf(
            "query" to saved.get<String>("query"),
            "filter" to saved.get<String>("filter"),
            "mine" to saved.get<Boolean>("mine"),
        ))) { _, _, _, users ->
            assertEquals(null, users)
            Page(emptyList())
        }
        runCurrent()
        assertEquals("retry", restored.ui.value.query)
        assertEquals(SessionFilter.NeedsYou, restored.ui.value.filter)
        assertFalse(restored.ui.value.mineOnly)
    }

    @Test
    fun delayedAllResponseCannotReplaceMineOrItsCache() = runTest {
        val allResponse = CompletableDeferred<Page<Session>>()
        val vm = model { _, _, _, users ->
            if (users == listOf("me")) Page(listOf(mine)) else allResponse.await()
        }
        runCurrent()
        vm.toggleMine()
        runCurrent()
        vm.toggleMine()
        allResponse.complete(Page(listOf(other, mine)))
        runCurrent()

        assertEquals(listOf(mine), vm.ui.value.visible)
        assertEquals(listOf(mine), cache["mine"])
        assertEquals(listOf(other, mine), cache["org"])
    }

    @Test
    fun cachedMineAllAndArchivedAreRestoredIndependentlyOffline() = runTest {
        cache["mine"] = listOf(mine, other)
        cache["org"] = listOf(other, mine)
        cache["mine_archived"] = listOf(archived)
        cache["org_archived"] = emptyList()
        val vm = model { _, _, _, _ -> throw IOException("Offline") }
        runCurrent()
        assertEquals(listOf(mine), vm.ui.value.visible)
        assertTrue(vm.ui.value.feed.offline)

        vm.toggleMine()
        runCurrent()
        assertEquals(listOf(other, mine), vm.ui.value.visible)
        vm.setFilter(SessionFilter.Archived)
        runCurrent()
        assertTrue(vm.ui.value.feed.offline)
        assertTrue(vm.ui.value.visible.isEmpty())

        vm.toggleMine()
        runCurrent()
        assertEquals(listOf(archived), vm.ui.value.visible)
        assertTrue(vm.ui.value.feed.offline)
        vm.setFilter(SessionFilter.All)
        assertEquals(listOf(mine), vm.ui.value.visible)
    }

    @Test
    fun retryUsesSameCursorAndPagingWritesTheOfflineSnapshot() = runTest {
        var failPage = true
        val firstPage = (1..50).map { Session("session-$it", userId = "me", updatedAt = -it.toLong()) }
        val last = mine.copy(updatedAt = -51)
        val vm = model { _, cursor, _, _ ->
            when (cursor) {
                null -> Page(firstPage, endCursor = "page-2", hasNextPage = true)
                "page-2" -> if (failPage) throw IOException("Async failure") else Page(listOf(last))
                else -> error("Unexpected cursor: $cursor")
            }
        }
        runCurrent()
        vm.loadMore()
        runCurrent()
        assertEquals(firstPage, vm.ui.value.visible)
        assertEquals("Async failure", vm.ui.value.feed.pageError)
        assertTrue(vm.ui.value.feed.hasMore)

        failPage = false
        vm.loadMore(retry = true)
        runCurrent()
        assertEquals(firstPage + last, vm.ui.value.visible)
        assertEquals(firstPage + last, cache["mine"])
        assertFalse(vm.ui.value.feed.hasMore)
    }

    @Test
    fun archiveSurvivesOfflineRestartBeforeEverOpeningArchived() = runTest {
        val moved = mine.copy(isArchived = true, updatedAt = 10)
        cache["mine_archived"] = listOf(archived)
        val staleRefresh = CompletableDeferred<Page<Session>>()
        var calls = 0
        val vm = model(perform = { _, _ -> moved }) { _, _, _, _ ->
            if (calls++ == 0) Page(listOf(mine)) else staleRefresh.await()
        }
        runCurrent()
        vm.pullRefresh()
        runCurrent()
        vm.act(mine.id, SessionAction.Archive) {}
        runCurrent()
        staleRefresh.complete(Page(listOf(mine)))
        runCurrent()

        assertTrue(vm.ui.value.visible.isEmpty())
        val restarted = model { _, _, _, _ -> throw IOException("Offline") }
        runCurrent()
        assertTrue(restarted.ui.value.visible.isEmpty())
        restarted.setFilter(SessionFilter.Archived)
        runCurrent()
        assertEquals(listOf(moved, archived), restarted.ui.value.visible)
        assertEquals(listOf(moved), cache["org_archived"])
    }

    @Test
    fun allUsersActionMovesOwnerlessRowAlreadyKnownToBeMine() = runTest {
        val ownerless = mine.copy(userId = null)
        val moved = ownerless.copy(isArchived = true)
        val vm = model(perform = { _, _ -> moved }) { _, _, _, _ -> Page(listOf(ownerless)) }
        runCurrent()
        vm.toggleMine()
        runCurrent()
        vm.act(ownerless.id, SessionAction.Archive) {}
        runCurrent()

        assertEquals(emptyList<Session>(), cache["mine"])
        assertEquals(listOf(moved), cache["mine_archived"])
        assertEquals(listOf(moved), cache["org_archived"])
    }

    @Test
    fun refreshKeepsTheLoadedExtentBeyondTheApiPageLimit() = runTest {
        val rows = (1..260).map { Session("session-$it", updatedAt = -it.toLong()) }
        val vm = model { first, cursor, _, _ ->
            assertTrue(first <= 200)
            val start = cursor?.toInt() ?: 0
            val end = (start + first).coerceAtMost(rows.size)
            Page(rows.subList(start, end), end.toString(), end < rows.size)
        }
        runCurrent()
        repeat(5) {
            vm.loadMore()
            runCurrent()
        }
        vm.pullRefresh()
        runCurrent()

        assertEquals(rows, vm.ui.value.visible)
        assertEquals(rows, cache["mine"])
    }
}
