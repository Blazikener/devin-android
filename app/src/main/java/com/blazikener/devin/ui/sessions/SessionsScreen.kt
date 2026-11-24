package com.blazikener.devin.ui.sessions

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.sp
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
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.graphics.TransformOrigin
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
import com.blazikener.devin.ui.components.DevinRootShell
import com.blazikener.devin.ui.navigation.RootDestination
import com.blazikener.devin.ui.navigation.SessionsRequest
import androidx.compose.material.icons.outlined.Menu
import com.blazikener.devin.ui.components.EmptyState
import com.blazikener.devin.ui.components.StatusPill
import com.blazikener.devin.ui.components.openUrl
import com.blazikener.devin.ui.components.relativeTime
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
    val vm: SessionsViewModel = viewModel { val app = ctx.applicationContext; SessionsViewModel(g) { SessionWatcher.markSeen(app, it) } }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val account by g.auth.account.collectAsStateWithLifecycle()
    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var searching by rememberSaveable { mutableStateOf(ui.query.isNotEmpty()) }
    var applied by rememberSaveable { mutableStateOf(false) }
    var confirmTerminate by remember { mutableStateOf<Session?>(null) }
    val listState = rememberLazyListState()
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val listIdentity = "${ui.key.mine}|${ui.key.archived}|${ui.filter.name}|${ui.query}"
    var previousListIdentity by rememberSaveable { mutableStateOf(listIdentity) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    LaunchedEffect(vm, initialFilter, initialSearch) {
        if (!applied) {
            initialFilter?.let(vm::setFilter)
            if (initialSearch) searching = true
            applied = true
        }
    }

    LaunchedEffect(navigationRequest) {
        navigationRequest?.let { request ->
            if (request.recoverCreate) {
                vm.showAllForCreateRecovery()
                searching = false
                focus.clearFocus()
                keyboard?.hide()
                listState.scrollToItem(0)
            } else {
                request.filter?.let(vm::setFilter)
                if (request.search) searching = true
            }
            onNavigationRequestHandled()
        }
    }

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
    val feed = ui.feed
    val visible = remember(feed.items, ui.filter, ui.query, ui.pinned) { ui.visible }
    val nearEnd by remember {
        derivedStateOf {
            val layout = listState.layoutInfo
            layout.visibleItemsInfo.lastOrNull()?.index?.let { it >= layout.totalItemsCount - 5 } == true
        }
    }
    LaunchedEffect(listIdentity) {
        // Effects restart after a saved root re-enters composition. Only a real filter/query/owner
        // change should reset the independently restored LazyListState.
        if (previousListIdentity != listIdentity) {
            previousListIdentity = listIdentity
            listState.scrollToItem(0)
        }
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
        onSearch = { searching = true }, onSettings = onSettings,
        onOpen = onOpen, onNew = onNew,
    ) { openMenu ->
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (searching) OutlinedTextField(
                        value = ui.query, onValueChange = vm::setQuery, singleLine = true,
                        placeholder = { Text("Search titles, tags, PRs") }, modifier = Modifier.fillMaxWidth(),
                    ) else Column {
                        Text("Devin", style = MaterialTheme.typography.titleLarge)
                        Text("YOUR WORKSPACE", style = MaterialTheme.typography.labelSmall, letterSpacing = 1.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = openMenu) {
                        Icon(Icons.Outlined.Menu, contentDescription = "Open menu")
                    }
                },
                actions = {
                    IconButton(onClick = { searching = !searching; if (!searching) vm.setQuery("") }) {
                        Icon(if (searching) Icons.Outlined.Close else Icons.Outlined.Search, contentDescription = if (searching) "Close search" else "Search")
                    }
                    IconButton(onClick = onSettings) { Icon(Icons.Outlined.Settings, contentDescription = "Settings") }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = onNew, icon = { Icon(Icons.Outlined.Add, null) }, text = { Text("New session") }, containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary)
        },
        snackbarHost = { SnackbarHost(snack) },
    ) { pad ->
        Column(Modifier.padding(pad).consumeWindowInsets(pad).fillMaxSize().imePadding()) {
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (account?.userId != null) FilterChip(
                    selected = ui.mineOnly, onClick = vm::toggleMine, label = { Text(if (ui.mineOnly) "Mine" else "All users") },
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
            if (feed.offline) Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.CloudOff, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(6.dp))
                Text("Offline — showing saved sessions", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            PullToRefreshBox(isRefreshing = feed.manualRefresh, onRefresh = vm::pullRefresh, modifier = Modifier.fillMaxSize()) {
                when {
                    visible.isEmpty() && (waitingForMore || (feed.loading && feed.error == null)) ->
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    feed.error != null && visible.isEmpty() && !feed.offline -> EmptyState("Couldn't load sessions", feed.error) {
                        TextButton(onClick = vm::pullRefresh) { Text("Retry") }
                    }
                    feed.pageError != null && visible.isEmpty() && !feed.offline -> EmptyState("Couldn't load more sessions", feed.pageError) {
                        TextButton(onClick = { vm.loadMore(retry = true) }) { Text("Retry") }
                    }
                    visible.isEmpty() -> EmptyState(
                        if (ui.query.isNotBlank()) "No matches" else "Nothing here",
                        when {
                            feed.offline -> "No saved sessions match this view."
                            ui.query.isNotBlank() -> "Try a different search."
                            ui.filter == SessionFilter.NeedsYou -> "Devin doesn't need anything from you right now."
                            ui.filter == SessionFilter.All -> "Start a session and it shows up here."
                            else -> "No ${ui.filter.label.lowercase()} sessions."
                        },
                    )
                    else -> LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(visible, key = { it.id }) { s ->
                            SessionRow(
                                s, pinned = s.id in ui.pinned, onClick = { onOpen(s.id) },
                                onAction = { action ->
                                    if (action == SessionAction.Terminate) confirmTerminate = s
                                    else vm.act(s.id, action) { msg -> scope.launch { snack.showSnackbar(msg) } }
                                },
                                onPin = { vm.togglePin(s.id) },
                                onOpenWeb = { openUrl(ctx, s.url) },
                                modifier = Modifier.animateItem(),
                            )
                        }
                        if (feed.loadingMore) item { Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp)) } }
                        if (feed.pageError != null && !feed.offline) item {
                            TextButton(onClick = { vm.loadMore(retry = true) }, modifier = Modifier.fillMaxWidth()) { Text("Couldn't load more — retry") }
                        }
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
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionRow(
    s: Session, pinned: Boolean, onClick: () -> Unit, onAction: (SessionAction) -> Unit,
    onPin: () -> Unit, onOpenWeb: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menu by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.98f else 1f, spring(stiffness = 650f), label = "session press")
    val tilt by animateFloatAsState(if (pressed) 1f else 0f, spring(stiffness = 650f), label = "session tilt")
    Surface(
        modifier.graphicsLayer {
            scaleX = scale
            scaleY = scale
            rotationX = tilt * 4f
            cameraDistance = 16f * density
            transformOrigin = TransformOrigin(0.5f, 1f)
        }, shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, if (s.state.needsAttention) s.state.color().copy(alpha = 0.55f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
    ) { Box {
        Column(
            Modifier.fillMaxWidth().combinedClickable(interactionSource = interaction, indication = androidx.compose.material3.ripple(), onClick = onClick, onLongClick = { menu = true })
                .padding(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (pinned) { Icon(Icons.Outlined.PushPin, "Pinned", Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(4.dp)) }
                if (s.parentSessionId != null) { Icon(Icons.AutoMirrored.Outlined.CallSplit, "Child session", Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant); Spacer(Modifier.width(4.dp)) }
                Text(s.displayTitle, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                Text(relativeTime(s.updatedAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.size(12.dp))
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
    } }
}
