package com.blazikener.devin.ui.sessions

import android.content.Context
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.CallSplit
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.blazikener.devin.data.Session
import com.blazikener.devin.data.SessionFilter
import com.blazikener.devin.data.SessionState
import com.blazikener.devin.graph
import com.blazikener.devin.notify.SessionWatcher
import com.blazikener.devin.ui.components.*
import com.blazikener.devin.ui.navigation.SessionsRequest
import com.blazikener.devin.ui.navigation.RootDestination
import com.blazikener.devin.ui.theme.attentionContainer
import com.blazikener.devin.ui.theme.onAttentionContainer
import com.blazikener.devin.ui.theme.color
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionsScreen(
    onOpen: (String) -> Unit,
    onNew: () -> Unit,
    onSettings: () -> Unit,
    onBack: () -> Unit,
    initialFilter: SessionFilter?,
    initialSearch: Boolean,
    onHome: () -> Unit = onBack,
    navigationRequest: SessionsRequest? = null,
    onNavigationRequestHandled: () -> Unit = {},
) {
    val ctx = LocalContext.current
    val g = ctx.graph
    val vm: SessionsViewModel = viewModel {
        SessionsViewModel(g, createSavedStateHandle()) { SessionWatcher.markSeen(ctx.applicationContext, it) }
    }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val account by g.auth.account.collectAsStateWithLifecycle()
    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var applied by rememberSaveable { mutableStateOf(false) }
    var focusSearch by rememberSaveable { mutableStateOf(initialSearch) }
    var showFilters by rememberSaveable { mutableStateOf(false) }
    var confirmTerminate by remember { mutableStateOf<Session?>(null) }
    val listState = rememberLazyListState()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    LaunchedEffect(vm, initialFilter, initialSearch) {
        if (!applied) {
            initialFilter?.let(vm::setFilter)
            applied = true
        }
    }
    LaunchedEffect(navigationRequest) {
        navigationRequest?.let { request ->
            request.filter?.let(vm::setFilter)
            if (request.search) focusSearch = true
            onNavigationRequestHandled()
        }
    }
    LaunchedEffect(focusSearch) {
        if (focusSearch) { focus.requestFocus(); focusSearch = false }
    }
    LaunchedEffect(Unit) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                vm.refresh()
                val prefs = ctx.getSharedPreferences("watcher", Context.MODE_PRIVATE)
                SessionWatcher.check(ctx, vm.ui.value.all, prefs, Triple(false, false, false))
                delay(if (vm.ui.value.workingCount > 0) 10_000 else 30_000)
            }
        }
    }
    val feed = ui.feed
    val visible = remember(feed.items, ui.filter, ui.query, ui.pinned) { ui.visible }
    val nearEnd by remember {
        derivedStateOf {
            val layout = listState.layoutInfo
            layout.visibleItemsInfo.lastOrNull()?.index?.let { it >= layout.totalItemsCount - 5 } == true
        }
    }
    // Do not reset a restored list merely because this screen re-entered composition.
    val viewKey = ui.key.toString() + "|" + ui.filter.name + "|" + ui.query
    var previousView by rememberSaveable { mutableStateOf(viewKey) }
    LaunchedEffect(viewKey) {
        if (previousView != viewKey) { listState.scrollToItem(0); previousView = viewKey }
    }
    val canLoadMore = feed.hasMore && !feed.loadingMore && !feed.refreshing && feed.error == null && feed.pageError == null
    val waitingForMore = feed.loadingMore || canLoadMore ||
        (feed.hasMore && feed.refreshing && feed.error == null && feed.pageError == null)
    LaunchedEffect(ui.key, feed.cursor, nearEnd, visible.isEmpty(), canLoadMore) {
        if (canLoadMore && (visible.isEmpty() || nearEnd)) vm.loadMore()
    }

    DevinRootShell(
        selected = RootDestination.Sessions, accountName = account?.name,
        recents = ui.all, needsYouCount = ui.needsYouCount,
        onHome = onHome, onSessions = {},
        onNeedsYou = { vm.setFilter(SessionFilter.NeedsYou) },
        onSearch = { focusSearch = true }, onSettings = onSettings,
        onOpen = onOpen, onNew = onNew,
    ) { openMenu ->
        Scaffold(
            topBar = {
                DevinTopBar("Sessions",
                    navigationIcon = { DevinIconButton("Open menu", Icons.Outlined.Menu, openMenu) },
                    actions = { DevinIconButton("New session", Icons.Outlined.Add, onNew) })
            },
            snackbarHost = { SnackbarHost(snack) },
        ) { padding ->
            Column(Modifier.padding(padding).consumeWindowInsets(padding).fillMaxSize().imePadding()) {
                OutlinedTextField(
                    ui.query, vm::setQuery,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).focusRequester(focus),
                    label = { Text("Search your sessions") }, singleLine = true,
                    leadingIcon = { Icon(Icons.Outlined.Search, null) },
                    trailingIcon = {
                        Row {
                            if (ui.query.isNotEmpty()) DevinIconButton("Clear search", Icons.Outlined.Close, { vm.setQuery("") })
                            DevinIconButton("Filter sessions", Icons.Outlined.Tune, { showFilters = true })
                        }
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                    shape = MaterialTheme.shapes.medium,
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                    ),
                )
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val primary = listOf(SessionFilter.All, SessionFilter.NeedsYou, SessionFilter.Working)
                    (primary + listOf(ui.filter).filterNot { it in primary }).forEach { filter ->
                        FilterChip(
                            selected = ui.filter == filter, onClick = { vm.setFilter(filter) },
                            modifier = Modifier.heightIn(min = 48.dp),
                            label = {
                                Text(filter.label + if (filter == SessionFilter.NeedsYou && ui.needsYouCount > 0) " " + ui.needsYouCount else "")
                            },
                        )
                    }
                }
                if (feed.offline || (feed.error != null && visible.isNotEmpty())) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.CloudOff, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(8.dp))
                        Text("Showing saved sessions", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick = vm::pullRefresh) { Text("Retry") }
                    }
                }
                PullToRefreshBox(isRefreshing = feed.manualRefresh, onRefresh = vm::pullRefresh, modifier = Modifier.weight(1f)) {
                    when {
                        visible.isEmpty() && (waitingForMore || (feed.loading && feed.error == null)) ->
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.semantics { contentDescription = "Loading sessions" }) }
                        feed.error != null && visible.isEmpty() && !feed.offline -> EmptyState("Couldn't load sessions", feed.error) {
                            TextButton(onClick = vm::pullRefresh) { Text("Retry") }
                        }
                        feed.pageError != null && visible.isEmpty() && !feed.offline -> EmptyState("Couldn't load more sessions", feed.pageError) {
                            TextButton(onClick = { vm.loadMore(retry = true) }) { Text("Retry") }
                        }
                        visible.isEmpty() -> EmptyState(
                            if (ui.query.isNotBlank()) "No matches" else if (ui.filter == SessionFilter.NeedsYou) "All caught up" else "Nothing here",
                            when {
                                feed.offline -> "No saved sessions match this view."
                                ui.query.isNotBlank() -> "Try a different title, tag or pull request."
                                ui.filter == SessionFilter.NeedsYou -> "Devin doesn't need anything from you right now."
                                ui.filter == SessionFilter.All -> "Start a session and it shows up here."
                                else -> "No " + ui.filter.label.lowercase() + " sessions."
                            },
                        )
                        else -> LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                            item(key = "heading") {
                                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
                                    Text(if (account?.userId != null && !ui.mineOnly) "Everyone’s work" else "Your work",
                                        style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f).semantics { heading() })
                                    Text(visible.size.toString() + if (feed.hasMore) " shown" else " sessions",
                                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            items(visible, key = { "session:" + it.id }) { session ->
                                SessionRow(
                                    session, pinned = session.id in ui.pinned, onClick = { keyboard?.hide(); onOpen(session.id) },
                                    onAction = { action ->
                                        if (action == SessionAction.Terminate) confirmTerminate = session
                                        else vm.act(session.id, action) { message -> scope.launch { snack.showSnackbar(message) } }
                                    },
                                    onPin = { vm.togglePin(session.id) },
                                    onOpenWeb = { openUrl(ctx, session.url) },
                                )
                            }
                            if (feed.loadingMore) item {
                                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator(Modifier.size(24.dp))
                                }
                            }
                            if (feed.pageError != null && !feed.offline) item {
                                TextButton(onClick = { vm.loadMore(retry = true) }, modifier = Modifier.fillMaxWidth()) { Text("Couldn't load more — retry") }
                            }
                        }
                    }
                }
            }
        }
    }
    if (showFilters) ModalBottomSheet(onDismissRequest = { showFilters = false }) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            Text("Filter sessions", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            if (account?.userId != null) {
                ListItem(headlineContent = { Text("Only my sessions") },
                    trailingContent = { Switch(ui.mineOnly, onCheckedChange = { vm.toggleMine() }, modifier = Modifier.semantics { contentDescription = "Only my sessions" }) })
            }
            SessionFilter.entries.forEach { filter ->
                TextButton(onClick = { vm.setFilter(filter); showFilters = false },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics { selected = ui.filter == filter }) {
                    Text(if (filter == SessionFilter.Finished) "Finished and sleeping" else filter.label, Modifier.weight(1f))
                    if (ui.filter == filter) Icon(Icons.Outlined.Check, "Selected")
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
    confirmTerminate?.let { session ->
        AlertDialog(
            onDismissRequest = { confirmTerminate = null },
            title = { Text("Terminate session?") },
            text = { Text("“" + session.displayTitle + "” will stop for good. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmTerminate = null
                    vm.act(session.id, SessionAction.Terminate) { message -> scope.launch { snack.showSnackbar(message) } }
                }) { Text("Terminate", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmTerminate = null }) { Text("Cancel") } },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionRow(
    session: Session, pinned: Boolean, onClick: () -> Unit, onAction: (SessionAction) -> Unit,
    onPin: () -> Unit, onOpenWeb: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    Surface(color = if (session.state.needsAttention) attentionContainer else MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface) {
        Column(
            Modifier.fillMaxWidth().combinedClickable(role = Role.Button, onClickLabel = "Open session",
                onClick = onClick, onLongClickLabel = "Session actions", onLongClick = { menu = true })
                .padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (pinned) { Icon(Icons.Outlined.PushPin, "Pinned", Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)) }
                if (session.parentSessionId != null) {
                    Icon(Icons.AutoMirrored.Outlined.CallSplit, "Child session", Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                }
                Text(session.displayTitle, style = MaterialTheme.typography.titleMedium, maxLines = 3,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Box {
                    DevinIconButton("Actions for " + session.displayTitle, Icons.Outlined.MoreVert, { menu = true })
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text(if (pinned) "Unpin" else "Pin to top") }, onClick = { menu = false; onPin() })
                        DropdownMenuItem(text = { Text("Open in browser") }, onClick = { menu = false; onOpenWeb() })
                        DropdownMenuItem(text = { Text("Copy link") }, onClick = { menu = false; clipboard.setText(AnnotatedString(session.url)) })
                        DropdownMenuItem(text = { Text(if (session.isArchived) "Unarchive" else "Archive") },
                            onClick = { menu = false; onAction(if (session.isArchived) SessionAction.Unarchive else SessionAction.Archive) })
                        if (session.state.isActive || session.state.needsAttention) DropdownMenuItem(text = { Text("Put to sleep") },
                            onClick = { menu = false; onAction(SessionAction.Sleep) })
                        DropdownMenuItem(text = { Text("Terminate", color = MaterialTheme.colorScheme.error) },
                            onClick = { menu = false; onAction(SessionAction.Terminate) })
                    }
                }
            }
            val preview = when (session.state) {
                SessionState.NeedsYou -> "Waiting for your reply"
                SessionState.NeedsApproval -> "Approval needed on the web"
                SessionState.Blocked -> "Session needs your attention"
                else -> null
            }
            if (preview != null) Text(preview, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp, end = 12.dp))
            // A wrapping flow keeps status and time reachable at 200% font scale.
            SessionMetadata(session)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SessionMetadata(session: Session) {
    FlowRow(Modifier.padding(end = 12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(session.state.label, style = MaterialTheme.typography.labelMedium,
            color = if (session.state.needsAttention) onAttentionContainer else session.state.color())
        Text(relativeTime(session.updatedAt), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        session.pullRequests.firstOrNull()?.let { pr ->
            Text("PR " + pr.label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (session.acusConsumed > 0) Text("%.1f ACU".format(session.acusConsumed),
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
