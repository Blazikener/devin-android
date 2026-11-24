package com.blazikener.devin.ui.home

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.blazikener.devin.data.CreateSessionRequest
import com.blazikener.devin.data.DEVIN_MODES
import com.blazikener.devin.data.SessionFilter
import com.blazikener.devin.graph
import com.blazikener.devin.notify.SessionWatcher
import com.blazikener.devin.ui.components.*
import com.blazikener.devin.ui.create.createModeOverride
import com.blazikener.devin.ui.navigation.RootDestination
import com.blazikener.devin.ui.sessions.SessionsViewModel
import com.blazikener.devin.ui.theme.DevinTextStyles
import com.blazikener.devin.ui.theme.attentionContainer
import com.blazikener.devin.ui.theme.onAttentionContainer
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(
    onOpen: (String) -> Unit,
    onSessions: (filter: SessionFilter?, search: Boolean) -> Unit,
    onNewForm: (prompt: String, modeOverride: String?) -> Unit,
    onSettings: () -> Unit,
) {
    val context = LocalContext.current
    val g = context.graph
    val vm: SessionsViewModel = viewModel {
        SessionsViewModel(g) { SessionWatcher.markSeen(context.applicationContext, it) }
    }
    val launcher: HomeLaunchViewModel = viewModel { HomeLaunchViewModel(g.api::createSession) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val launch by launcher.state.collectAsStateWithLifecycle()
    val account by g.auth.account.collectAsStateWithLifecycle()
    val settings by g.settings.settings.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val compact = LocalConfiguration.current.screenHeightDp < 480
    val imeVisible = WindowInsets.isImeVisible
    val keyboard = LocalSoftwareKeyboardController.current
    var prompt by rememberSaveable { mutableStateOf("") }
    var chosenMode by rememberSaveable { mutableStateOf<String?>(null) }
    var modeChosen by rememberSaveable { mutableStateOf(false) }
    val mode = if (modeChosen) chosenMode else settings.defaultMode
    var showModePicker by rememberSaveable { mutableStateOf(false) }
    val scroll = rememberScrollState()
    LaunchedEffect(launch.error) { if (launch.error != null) scroll.scrollTo(0) }

    LaunchedEffect(Unit) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                vm.refresh()
                val prefs = context.getSharedPreferences("watcher", Context.MODE_PRIVATE)
                SessionWatcher.check(context, vm.ui.value.all, prefs, Triple(false, false, false))
                delay(if (vm.ui.value.workingCount > 0) 10_000 else 30_000)
            }
        }
    }
    LaunchedEffect(launch.createdId) {
        launch.createdId?.let { id ->
            prompt = ""
            modeChosen = false
            launcher.consumeCreated()
            onOpen(id) // Never wait for the decorative turn to finish.
        }
    }
    fun send() {
        if (prompt.isBlank() || launch.busy) return
        keyboard?.hide()
        launcher.start(CreateSessionRequest(
            prompt = prompt.trim(),
            maxAcuLimit = settings.defaultAcuLimit?.takeIf { it > 0 },
            createAsUserId = settings.createAsUserId,
            devinMode = mode,
        ))
    }
    fun openCreate() { if (!launch.busy) onNewForm(prompt, createModeOverride(modeChosen, chosenMode)) }

    DevinRootShell(
        selected = RootDestination.Home, accountName = account?.name,
        recents = ui.all, needsYouCount = ui.needsYouCount,
        onHome = {}, onSessions = { onSessions(null, false) },
        onNeedsYou = { onSessions(SessionFilter.NeedsYou, false) },
        onSearch = { onSessions(null, true) }, onSettings = onSettings,
        onOpen = onOpen, onNew = ::openCreate,
    ) { openMenu ->
        Scaffold(
            topBar = {
                if (!(compact && imeVisible)) DevinTopBar(
                    title = "Devin",
                    navigationIcon = { DevinIconButton("Open menu", Icons.Outlined.Menu, openMenu) },
                    actions = { DevinIconButton("New session", Icons.Outlined.Add, ::openCreate, enabled = !launch.busy) },
                )
            },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll).padding(horizontal = 20.dp)) {
                    launch.error?.let { error ->
                        Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
                            Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                            TextButton(onClick = ::send, enabled = prompt.isNotBlank()) { Text("Retry") }
                        }
                    }
                    if (launch.busy) {
                        SessionLaunchCue(Modifier.fillMaxWidth().padding(vertical = if (compact) 8.dp else 24.dp))
                    } else if (!imeVisible) {
                        Spacer(Modifier.height(if (compact) 8.dp else 20.dp))
                        JadeWorkTiles(Modifier.size(72.dp))
                        Spacer(Modifier.height(20.dp))
                        Text("A little less busy.\nA little more done.", style = DevinTextStyles.welcomeSerif,
                            modifier = Modifier.semantics { heading() })
                        Spacer(Modifier.height(10.dp))
                        val name = account?.takeIf { it.userId != null && it.name != "Devin user" }?.name
                        Text(greeting(java.time.LocalTime.now().hour, name) + ". What’s next?",
                            style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(24.dp))
                    }
                    if (!launch.busy) {
                        val feed = ui.feed
                        when {
                            feed.loading -> LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 16.dp))
                            feed.error != null -> {
                                Text(if (feed.offline) "Showing saved sessions" else "Couldn't load sessions",
                                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                TextButton(onClick = vm::pullRefresh) { Text("Retry") }
                            }
                            ui.needsYouCount > 0 -> HomeAttention(ui.needsYouCount) { onSessions(SessionFilter.NeedsYou, false) }
                            else -> Text(if (ui.workingCount > 0) ui.workingCount.toString() + " sessions working" else "All caught up",
                                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Spacer(Modifier.height(12.dp))
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("Pick up where you left off", style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.weight(1f).semantics { heading() })
                            TextButton(onClick = { onSessions(null, false) }) { Text("View all") }
                        }
                        ui.all.sortedByDescending { it.updatedAt }.take(2).forEach { session ->
                            Row(
                                Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = "Open session") { onOpen(session.id) }
                                    .padding(vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Outlined.History, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(session.displayTitle, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text(session.state.label + " · " + relativeTime(session.updatedAt),
                                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        if (!feed.loading && feed.error == null && ui.all.isEmpty()) {
                            Text("Your recent sessions will appear here.", style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 12.dp))
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
                    val compactComposer = compact && (imeVisible || launch.busy)
                    DevinOutlinedSurface(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(if (compactComposer) 4.dp else 12.dp)) {
                            // Keep the same field and focus node when the IME changes the layout.
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            if (compactComposer) DevinIconButton("Attach context and session options", Icons.Outlined.Add,
                                ::openCreate, enabled = !launch.busy)
                            BasicTextField(
                                prompt, { prompt = it }, enabled = !launch.busy,
                                modifier = Modifier.weight(1f).heightIn(min = 48.dp).padding(4.dp)
                                    .semantics { contentDescription = "Task for Devin" },
                                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                                keyboardActions = KeyboardActions(onSend = { send() }),
                                singleLine = compactComposer,
                                maxLines = if (compact) 2 else 4,
                                decorationBox = { inner ->
                                    Box(contentAlignment = Alignment.CenterStart) {
                                        if (prompt.isEmpty()) Text(if (compactComposer) "Your task" else "What would you like to build?",
                                            style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        inner()
                                    }
                                },
                            )
                            if (compactComposer) FilledIconButton(onClick = ::send, enabled = prompt.isNotBlank() && !launch.busy, modifier = Modifier.size(48.dp)) {
                                Icon(Icons.Filled.ArrowUpward, "Start session")
                            }
                            }
                            if (!compactComposer) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                DevinIconButton("Attach context and session options", Icons.Outlined.Add,
                                ::openCreate, enabled = !launch.busy)
                                val label = DEVIN_MODES.firstOrNull { it.first == mode }?.second ?: "Default"
                                TextButton(onClick = { showModePicker = true }, enabled = !launch.busy,
                                    modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Session mode: " + label }) {
                                    Text(label)
                                    Icon(Icons.Outlined.ExpandMore, null, Modifier.size(18.dp))
                                }
                                Spacer(Modifier.weight(1f))
                                FilledIconButton(onClick = ::send, enabled = prompt.isNotBlank() && !launch.busy, modifier = Modifier.size(48.dp)) {
                                    Icon(Icons.Filled.ArrowUpward, "Start session")
                                }
                            }
                            }
                        }
                    }
                }
            }
        }
    }
    if (showModePicker) ModalBottomSheet(onDismissRequest = { showModePicker = false }) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            Text("Session mode", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            DEVIN_MODES.forEach { (value, label) ->
                TextButton(onClick = { chosenMode = value; modeChosen = true; showModePicker = false },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics { selected = mode == value }) {
                    Text(label, modifier = Modifier.weight(1f))
                    if (mode == value) Icon(Icons.Outlined.Check, "Selected")
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun HomeAttention(count: Int, onClick: () -> Unit) {
    Surface(onClick = onClick, modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium, color = attentionContainer, contentColor = onAttentionContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.ChatBubbleOutline, null, Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(if (count == 1) "1 session needs you" else "$count sessions need you", style = MaterialTheme.typography.titleSmall)
                Text("A quick reply keeps things moving.", style = MaterialTheme.typography.bodySmall)
            }
            Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, Modifier.size(18.dp))
        }
    }
}

internal fun greeting(hour: Int, name: String?): String {
    val salutation = when (hour) {
        in 5..11 -> "Good morning"
        in 12..16 -> "Good afternoon"
        else -> "Good evening"
    }
    val firstName = name?.trim()?.takeIf(String::isNotEmpty)?.split(Regex("\\s+"))?.firstOrNull()
    return if (firstName == null) salutation else "$salutation, $firstName"
}
