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
import com.blazikener.devin.data.CreateSnapshot
import com.blazikener.devin.data.DEVIN_MODES
import com.blazikener.devin.data.SessionFilter
import com.blazikener.devin.data.createFingerprint
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
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(
    onOpen: (String) -> Unit,
    onSessions: (filter: SessionFilter?, search: Boolean) -> Unit,
    onNewForm: (prompt: String, modeOverride: String?) -> Unit,
    onSettings: () -> Unit,
    onCreateRecovery: () -> Unit,
    consumedCreateFingerprint: String? = null,
    onConsumedCreateHandled: () -> Unit = {},
) {
    val context = LocalContext.current
    val g = context.graph
    val vm: SessionsViewModel = viewModel {
        SessionsViewModel(g) { SessionWatcher.markSeen(context.applicationContext, it) }
    }
    val launcher: HomeLaunchViewModel = viewModel { HomeLaunchViewModel(g.createGate) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val launch by launcher.state.collectAsStateWithLifecycle()
    val attempts by launcher.attempts.collectAsStateWithLifecycle()
    val globalBusy by launcher.inFlight.collectAsStateWithLifecycle()
    val busy = launch.busy || globalBusy
    val recovery = if (busy) null else launch.confirmation ?: attempts.firstOrNull { !it.dismissed }
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
    val scope = rememberCoroutineScope()
    LaunchedEffect(consumedCreateFingerprint) {
        consumedCreateFingerprint?.let { fingerprint ->
            if (createFingerprint(prompt) == fingerprint) {
                prompt = ""
                modeChosen = false
                showModePicker = false
            }
            onConsumedCreateHandled()
        }
    }
    LaunchedEffect(launch.error, recovery) { if (launch.error != null || recovery != null) scroll.scrollTo(0) }

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
            if (createFingerprint(prompt) == launch.createdFingerprint) {
                prompt = ""
                modeChosen = false
                showModePicker = false
            }
            onOpen(id) // Never wait for the decorative turn to finish.
            launcher.consumeCreated(id)
        }
    }
    fun request() = CreateSessionRequest(
            prompt = prompt.trim(),
            maxAcuLimit = settings.defaultAcuLimit?.takeIf { it > 0 },
            createAsUserId = settings.createAsUserId,
            devinMode = mode,
        )
    fun send() {
        if (prompt.isBlank() || busy) return
        keyboard?.hide()
        scope.launch { scroll.scrollTo(0) }
        launcher.start(request())
    }
    fun openCreate() { if (!busy) onNewForm(prompt, createModeOverride(modeChosen, chosenMode)) }

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
                    actions = { DevinIconButton("New session", Icons.Outlined.Add, ::openCreate, enabled = !busy) },
                )
            },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll).padding(horizontal = 20.dp)) {
                    recovery?.let { attempt ->
                        HomeCreateRecovery(
                            submitted = attempt.submitted,
                            hasSession = attempt.sessionId != null,
                            canRetry = attempt.submitted != null && !busy,
                            onOpen = { launcher.openSaved(attempt) },
                            onCheck = { keyboard?.hide(); onCreateRecovery() },
                            onDismiss = { launcher.dismiss(attempt) },
                            onClear = {
                                if (attempt.matches(prompt)) {
                                    prompt = ""
                                    modeChosen = false
                                }
                                launcher.clearAttempt(attempt)
                            },
                            onRetry = { keyboard?.hide(); launcher.retryAnyway(attempt) },
                        )
                    }
                    launch.error?.let { error ->
                        Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
                            Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                            if (recovery == null) TextButton(onClick = ::send, enabled = prompt.isNotBlank() && !busy) { Text("Retry") }
                        }
                    }
                    if (busy) {
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
                    if (!busy) {
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
                    val compactComposer = compact && (imeVisible || busy)
                    DevinOutlinedSurface(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(if (compactComposer) 4.dp else 12.dp)) {
                            // Keep the same field and focus node when the IME changes the layout.
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            if (compactComposer) DevinIconButton("Attach context and session options", Icons.Outlined.Add,
                                ::openCreate, enabled = !busy)
                            BasicTextField(
                                prompt, { prompt = it }, enabled = !busy,
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
                            if (prompt.isNotEmpty()) DevinIconButton("Clear prompt", Icons.Outlined.Close, {
                                prompt = ""
                                launcher.clearPrompt()
                            }, enabled = !busy)
                            if (compactComposer) FilledIconButton(onClick = ::send, enabled = prompt.isNotBlank() && !busy, modifier = Modifier.size(48.dp)) {
                                Icon(Icons.Filled.ArrowUpward, "Start session")
                            }
                            }
                            if (!compactComposer) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                DevinIconButton("Attach context and session options", Icons.Outlined.Add,
                                ::openCreate, enabled = !busy)
                                val label = DEVIN_MODES.firstOrNull { it.first == mode }?.second ?: "Default"
                                TextButton(onClick = { showModePicker = true }, enabled = !busy,
                                    modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Session mode: " + label }) {
                                    Text(label)
                                    Icon(Icons.Outlined.ExpandMore, null, Modifier.size(18.dp))
                                }
                                Spacer(Modifier.weight(1f))
                                FilledIconButton(onClick = ::send, enabled = prompt.isNotBlank() && !busy, modifier = Modifier.size(48.dp)) {
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

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun HomeCreateRecovery(
    submitted: CreateSnapshot?,
    hasSession: Boolean,
    canRetry: Boolean,
    onOpen: () -> Unit,
    onCheck: () -> Unit,
    onDismiss: () -> Unit,
    onClear: () -> Unit,
    onRetry: () -> Unit,
) {
    var reviewOriginal by rememberSaveable(submitted?.request?.prompt) { mutableStateOf(false) }
    var reviewClear by rememberSaveable(submitted?.draftVersion) { mutableStateOf(false) }
    Surface(
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        shape = MaterialTheme.shapes.medium,
        color = attentionContainer,
        contentColor = onAttentionContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Column(Modifier.padding(16.dp).semantics { liveRegion = LiveRegionMode.Polite }) {
            Text(if (hasSession) "Your session was created" else "A session may already have started",
                style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
            submitted?.request?.let { original ->
                Text("Original task", style = MaterialTheme.typography.labelMedium)
                Text(original.prompt, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Text(
                    "Mode: ${DEVIN_MODES.firstOrNull { it.first == original.devinMode }?.second ?: original.devinMode ?: "Default"}" +
                        " · ACU limit: ${original.maxAcuLimit?.toString() ?: "Default"}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text(if (hasSession) "Open the session instead of starting the same task again." else
                "Check sessions before starting the same task again. You can still start a different task.",
                style = MaterialTheme.typography.bodyMedium)
            if (!hasSession && canRetry) Text(
                "Start again uses the original task and settings, not your current draft. It may create another session.",
                style = MaterialTheme.typography.bodySmall,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (hasSession) TextButton(onClick = onOpen, modifier = Modifier.heightIn(min = 48.dp)) { Text("Open session") }
                TextButton(onClick = onCheck, modifier = Modifier.heightIn(min = 48.dp)) { Text("Check sessions") }
                if (submitted != null) TextButton(onClick = { reviewClear = false; reviewOriginal = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(if (!hasSession && canRetry) "Review retry" else "Review task and settings")
                }
                TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("Dismiss") }
                if (!hasSession) TextButton(onClick = {
                    if (submitted == null) onClear() else { reviewClear = true; reviewOriginal = true }
                }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Clear original task") }
            }
        }
    }
    if (reviewOriginal && submitted != null) ModalBottomSheet(onDismissRequest = { reviewOriginal = false }) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(if (reviewClear) "Clear original task?" else "Original task and settings", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            if (reviewClear) Text("This clears this saved draft and its attached-file choices. A session that already started will not be deleted. Sending this task again can create another session.")
            else if (!hasSession) Text("Starting this task again may create a duplicate session. These original settings will be sent, not your current edits.")
            CreateRequestDetails(submitted)
            if (reviewClear) OutlinedButton(onClick = { reviewOriginal = false; onClear() }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Clear original task") }
            else if (!hasSession && canRetry) OutlinedButton(onClick = { reviewOriginal = false; onRetry() }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Start again anyway") }
            TextButton(onClick = { reviewOriginal = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (reviewClear) "Cancel" else "Close") }
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
