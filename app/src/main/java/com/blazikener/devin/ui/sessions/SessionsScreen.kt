package com.blazikener.devin.ui.sessions

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.CallSplit
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.blazikener.devin.data.Session
import com.blazikener.devin.data.SessionFilter
import com.blazikener.devin.graph
import com.blazikener.devin.notify.SessionWatcher
import com.blazikener.devin.ui.components.EmptyState
import com.blazikener.devin.ui.components.StatusPill
import com.blazikener.devin.ui.components.openUrl
import com.blazikener.devin.ui.components.relativeTime
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionsScreen(onOpen: (String) -> Unit, onNew: () -> Unit, onSettings: () -> Unit) {
    val ctx = LocalContext.current
    val g = ctx.graph
    val vm: SessionsViewModel = viewModel { val app = ctx.applicationContext; SessionsViewModel(g) { SessionWatcher.markSeen(app, it) } }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val account by g.auth.account.collectAsStateWithLifecycle()
    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var searching by remember { mutableStateOf(false) }
    var confirmTerminate by remember { mutableStateOf<Session?>(null) }
    val listState = rememberLazyListState()
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    // Live refresh while visible; also feeds the notifier so background checks don't re-alert.
    LaunchedEffect(Unit) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                vm.refresh()
                val prefs = ctx.getSharedPreferences("watcher", android.content.Context.MODE_PRIVATE)
                SessionWatcher.check(ctx, vm.ui.value.all, prefs, Triple(false, false, false))
                delay(if (vm.ui.value.workingCount > 0) 10_000 else 30_000)
            }
        }
    }
    val nearEnd by remember { derivedStateOf { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 >= listState.layoutInfo.totalItemsCount - 5 } }
    LaunchedEffect(nearEnd, ui.all.size, ui.archived.size, ui.loadingMore, ui.hasMore) {
        if (nearEnd && !ui.loadingMore && ui.hasMore) vm.loadMore()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (searching) OutlinedTextField(
                        value = ui.query, onValueChange = vm::setQuery, singleLine = true,
                        placeholder = { Text("Search titles, tags, PRs") }, modifier = Modifier.fillMaxWidth(),
                    ) else Column {
                        Text("Sessions", fontWeight = FontWeight.SemiBold)
                        val sub = buildList {
                            if (ui.needsYouCount > 0) add("${ui.needsYouCount} need you")
                            if (ui.workingCount > 0) add("${ui.workingCount} working")
                        }.joinToString(" · ")
                        if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                actions = {
                    IconButton(onClick = { searching = !searching; if (!searching) vm.setQuery("") }) {
                        Icon(if (searching) Icons.Outlined.Close else Icons.Outlined.Search, contentDescription = "Search")
                    }
                    IconButton(onClick = onSettings) { Icon(Icons.Outlined.Settings, contentDescription = "Settings") }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = onNew, icon = { Icon(Icons.Outlined.Add, null) }, text = { Text("New session") })
        },
        snackbarHost = { SnackbarHost(snack) },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (account?.userId != null) FilterChip(
                    selected = ui.mineOnly, onClick = vm::toggleMine, label = { Text("Mine") },
                    leadingIcon = { Icon(Icons.Outlined.Person, null, Modifier.size(16.dp)) },
                )
                SessionFilter.entries.forEach { f ->
                    FilterChip(
                        selected = ui.filter == f, onClick = { vm.setFilter(f) },
                        label = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(f.label)
                                if (f == SessionFilter.NeedsYou && ui.needsYouCount > 0) {
                                    Spacer(Modifier.width(6.dp)); Badge { Text("${ui.needsYouCount}") }
                                }
                            }
                        },
                    )
                }
            }
            if (ui.offline) Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.CloudOff, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(6.dp))
                Text("Offline — showing saved sessions", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            PullToRefreshBox(isRefreshing = ui.refreshing, onRefresh = vm::pullRefresh, modifier = Modifier.fillMaxSize()) {
                when {
                    ui.loading && ui.visible.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    ui.error != null && ui.visible.isEmpty() -> EmptyState("Couldn't load sessions", ui.error ?: "") {
                        TextButton(onClick = vm::pullRefresh) { Text("Retry") }
                    }
                    ui.visible.isEmpty() -> EmptyState(
                        if (ui.query.isNotBlank()) "No matches" else "Nothing here",
                        if (ui.filter == SessionFilter.NeedsYou) "Devin doesn't need anything from you right now." else "Start a session and it shows up here.",
                    )
                    else -> LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                        items(ui.visible, key = { it.id }) { s ->
                            SessionRow(
                                s, pinned = s.id in ui.pinned, onClick = { onOpen(s.id) },
                                onAction = { action ->
                                    if (action == SessionAction.Terminate) confirmTerminate = s
                                    else vm.act(s.id, action) { msg -> scope.launch { snack.showSnackbar(msg) } }
                                },
                                onPin = { vm.togglePin(s.id) },
                                onOpenWeb = { openUrl(ctx, s.url) },
                            )
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                        if (ui.loadingMore) item { Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp)) } }
                        item { Spacer(Modifier.size(88.dp)) }
                    }
                }
            }
        }
    }
    confirmTerminate?.let { s ->
        AlertDialog(
            onDismissRequest = { confirmTerminate = null },
            title = { Text("Terminate session?") },
            text = { Text("“${s.displayTitle}” will stop for good. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmTerminate = null
                    vm.act(s.id, SessionAction.Terminate) { msg -> scope.launch { snack.showSnackbar(msg) } }
                }) { Text("Terminate", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmTerminate = null }) { Text("Cancel") } },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionRow(
    s: Session, pinned: Boolean, onClick: () -> Unit, onAction: (SessionAction) -> Unit,
    onPin: () -> Unit, onOpenWeb: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    Box {
        Column(
            Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = { menu = true })
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (pinned) { Icon(Icons.Outlined.PushPin, "Pinned", Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(4.dp)) }
                if (s.parentSessionId != null) { Icon(Icons.AutoMirrored.Outlined.CallSplit, "Child session", Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant); Spacer(Modifier.width(4.dp)) }
                Text(s.displayTitle, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                Text(relativeTime(s.updatedAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.size(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusPill(s.state)
                s.pullRequests.firstOrNull()?.let { pr ->
                    Text(
                        "PR ${pr.label}" + (pr.state?.let { " · $it" } ?: "") + if (s.pullRequests.size > 1) " +${s.pullRequests.size - 1}" else "",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                    )
                }
                Spacer(Modifier.weight(1f))
                if (s.acusConsumed > 0) Text("%.1f ACU".format(s.acusConsumed), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text(if (pinned) "Unpin" else "Pin to top") }, onClick = { menu = false; onPin() })
            DropdownMenuItem(text = { Text("Open in browser") }, onClick = { menu = false; onOpenWeb() })
            DropdownMenuItem(text = { Text("Copy link") }, onClick = { menu = false; clipboard.setText(AnnotatedString(s.url)) })
            if (s.isArchived) DropdownMenuItem(text = { Text("Unarchive") }, onClick = { menu = false; onAction(SessionAction.Unarchive) })
            else DropdownMenuItem(text = { Text("Archive") }, onClick = { menu = false; onAction(SessionAction.Archive) })
            if (s.state.isActive || s.state.needsAttention) DropdownMenuItem(text = { Text("Put to sleep") }, onClick = { menu = false; onAction(SessionAction.Sleep) })
            DropdownMenuItem(text = { Text("Terminate", color = MaterialTheme.colorScheme.error) }, onClick = { menu = false; onAction(SessionAction.Terminate) })
        }
    }
}
