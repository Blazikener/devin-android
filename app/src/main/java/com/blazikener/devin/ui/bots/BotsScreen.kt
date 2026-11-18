package com.blazikener.devin.ui.bots

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.blazikener.devin.data.Bot
import com.blazikener.devin.data.Session
import com.blazikener.devin.graph
import com.blazikener.devin.ui.components.StatusPill
import com.blazikener.devin.ui.components.openUrl
import com.blazikener.devin.ui.components.relativeTime
import com.blazikener.devin.ui.session.Composer
import com.blazikener.devin.ui.session.MessageBubble
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BotsScreen(onBack: () -> Unit, onOpen: (String) -> Unit) {
    val g = LocalContext.current.graph
    val vm: BotsViewModel = viewModel { BotsViewModel(g) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Bots") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") } },
        )
    }) { pad ->
        PullToRefreshBox(isRefreshing = ui.loading && ui.bots.isNotEmpty(), onRefresh = { vm.refresh() }, modifier = Modifier.padding(pad).fillMaxSize()) {
            when {
                ui.loading && ui.bots.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
                ui.error != null && ui.bots.isEmpty() -> Message(ui.error!!, "Retry") { vm.refresh() }
                ui.bots.isEmpty() -> Message("No Bots yet. Create one in Devin on the web, then refresh.", "Refresh") { vm.refresh() }
                else -> LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxSize()) {
                    items(ui.bots, key = { it.id }) { BotRow(it) { onOpen(it.id) } }
                }
            }
        }
    }
}

@Composable
private fun Message(text: String, action: String? = null, onAction: () -> Unit = {}) {
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (action != null) { Spacer(Modifier.padding(6.dp)); Button(onClick = onAction) { Text(action) } }
    }
}

@Composable
private fun BotAvatar(size: Int = 40) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(size.dp)) {
        Box(contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.SmartToy, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size((size * 0.55).dp))
        }
    }
}

@Composable
private fun BotRow(bot: Bot, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            BotAvatar()
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(bot.displayName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                bot.description?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            if (bot.updatedAt > 0) Text(relativeTime(bot.updatedAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun BotScreen(id: String, onBack: () -> Unit, onOpenSession: (String) -> Unit) {
    val ctx = LocalContext.current
    val g = ctx.graph
    val vm: BotViewModel = viewModel(key = "bot-$id") { BotViewModel(g, id) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val settings by g.settings.settings.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var draft by rememberSaveable { mutableStateOf("") }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_START) vm.startPolling() else if (e == Lifecycle.Event.ON_STOP) vm.stopPolling()
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs); vm.stopPolling() }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        BotAvatar(32)
                        Spacer(Modifier.width(10.dp))
                        Text(ui.bot?.displayName ?: "Bot", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") } },
                actions = {
                    ui.bot?.url?.let { url -> IconButton(onClick = { openUrl(ctx, url) }) { Icon(Icons.Outlined.OpenInNew, "Open in browser") } }
                },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).consumeWindowInsets(pad).fillMaxSize().imePadding()) {
            PrimaryTabRow(selectedTabIndex = tab) {
                Tab(tab == 0, { tab = 0 }, text = { Text("Chat") })
                Tab(tab == 1, { tab = 1 }, text = { Text(if (ui.sessions.isEmpty()) "Sessions" else "Sessions (${ui.sessions.size})") })
            }
            Box(Modifier.weight(1f)) { when {
                ui.loading && ui.bot == null -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
                ui.bot == null -> Message(ui.error ?: "Couldn't load this Bot.", "Retry") { scope.launch { vm.refresh() } }
                tab == 0 -> BotChat(ui, vm)
                else -> BotSessions(ui.sessions, ui.sessionsError, onOpenSession) { scope.launch { vm.refresh() } }
            } }
            if (tab == 0 && ui.bot != null) Composer(
                value = draft, onChange = { draft = it }, enterToSend = settings.enterToSend,
                canSend = draft.isNotBlank(), hint = "Message ${ui.bot?.displayName ?: "Bot"}",
                onAttach = null, onSend = { vm.send(draft); draft = "" },
            )
        }
    }
}

@Composable
private fun BotChat(ui: BotUi, vm: BotViewModel) {
    val list = rememberLazyListState()
    val count = ui.messages.size + ui.pending.size
    LaunchedEffect(count) { if (count > 0) list.animateScrollToItem(count - 1) }
    if (count == 0) {
        Message(
            if (ui.bot?.conversationSessionId == null) "Send a message to start chatting with this Bot."
            else "No messages yet. Say hello to ${ui.bot.displayName}.",
        )
        return
    }
    LazyColumn(state = list, contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxSize()) {
        ui.error?.let { e -> item(key = "error") { Text(e, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) } }
        items(ui.messages, key = { it.eventId }) { MessageBubble(it) }
        items(ui.pending, key = { it.localId }) { p ->
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
                Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = if (p.failed) 0.5f else 0.8f)) {
                    Text(p.text, Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
                }
                if (p.failed) Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(p.error ?: "Not sent", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                    TextButton(onClick = { vm.retry(p) }) { Text("Retry") }
                    TextButton(onClick = { vm.discard(p) }) { Text("Discard") }
                } else Text(if (ui.bot?.conversationSessionId == null) "Sent" else "Sending…", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun BotSessions(sessions: List<Session>, error: String?, onOpen: (String) -> Unit, onRetry: () -> Unit) {
    if (sessions.isEmpty()) {
        if (error != null) Message("Couldn't load this Bot's sessions. $error", "Retry", onRetry)
        else Message("This Bot hasn't started any sessions yet.")
        return
    }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxSize()) {
        items(sessions, key = { it.id }) { s ->
            Surface(
                shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                modifier = Modifier.fillMaxWidth().clickable { onOpen(s.id) },
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(s.displayTitle, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.padding(3.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusPill(s.state)
                        Spacer(Modifier.weight(1f))
                        if (s.updatedAt > 0) Text(relativeTime(s.updatedAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
