package com.blazikener.devin.ui.session

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.blazikener.devin.data.Session
import com.blazikener.devin.data.SessionAttachment
import com.blazikener.devin.data.SessionMessage
import com.blazikener.devin.data.SessionState
import com.blazikener.devin.graph
import com.blazikener.devin.ui.components.*
import com.blazikener.devin.ui.navigation.SessionTab
import com.blazikener.devin.ui.theme.DevinDimens
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SessionScreen(
    id: String, sharedText: String? = null, onBack: () -> Unit, onOpenSession: (String) -> Unit,
    onOpenFile: (SessionAttachment, SessionTab) -> Unit,
) {
    val ctx = LocalContext.current
    val g = ctx.graph
    val vm: SessionViewModel = viewModel(key = id) { SessionViewModel(g, ctx.applicationContext, id) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val settings by g.settings.settings.collectAsStateWithLifecycle()
    val uploads by vm.uploads.items.collectAsStateWithLifecycle()
    var input by rememberSaveable(id) { mutableStateOf(sharedText ?: vm.draft()) }
    var tab by rememberSaveable(id) { mutableStateOf(SessionTab.Chat) }
    val list = rememberLazyListState()
    val filesList = rememberLazyListState()
    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    var confirmTerminate by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val accessibility = remember(ctx) {
        ctx.getSystemService(android.view.accessibility.AccessibilityManager::class.java)
    }
    val latestInput by rememberUpdatedState(input)
    val attachFocus = remember { FocusRequester() }
    var restoreAttachFocus by rememberSaveable { mutableStateOf(false) }
    var openedFile by rememberSaveable(id) { mutableStateOf<String?>(null) }
    var restoreFileFocus by remember(id) { mutableStateOf<String?>(null) }

    DisposableEffect(lifecycle, vm) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) {
                vm.startPolling()
                // NavHost resumes the origin after Reader's return transition finishes.
                restoreFileFocus = openedFile
                openedFile = null
            }
            if (e == Lifecycle.Event.ON_PAUSE) { vm.stopPolling(); vm.saveDraft(latestInput) }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs); vm.stopPolling(); vm.saveDraft(latestInput) }
    }
    // Persist edits rather than waiting for a stale lifecycle closure to save a draft.
    LaunchedEffect(input) { vm.saveDraft(input) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        uris.forEach(vm.uploads::add)
        restoreAttachFocus = true
    }
    LaunchedEffect(restoreAttachFocus, tab) {
        if (restoreAttachFocus && tab == SessionTab.Chat) {
            // TalkBack can be on the removed chip while input focus stays on
            // Attach. Reissue focus only for touch exploration; ordinary chip
            // removal must not steal the editor's focus or dismiss its IME.
            if (accessibility?.isTouchExplorationEnabled == true) {
                focusManager.clearFocus(force = true)
                withFrameNanos { }
                attachFocus.requestFocus()
            }
            restoreAttachFocus = false
        }
    }
    BackHandler(tab == SessionTab.Files) { tab = SessionTab.Chat }
    fun back() { if (tab == SessionTab.Files) tab = SessionTab.Chat else onBack() }
    fun send() { if (vm.send(input)) input = "" }
    fun openFile(file: SessionAttachment, origin: SessionTab) {
        keyboard?.hide()
        vm.saveDraft(input)
        openedFile = file.id
        onOpenFile(file, origin)
    }
    fun act(label: String, block: suspend () -> Session) =
        vm.act(block) { err -> scope.launch { snack.showSnackbar(err ?: label) } }

    val s = ui.session
    val atBottom by remember { derivedStateOf { !list.canScrollBackward } }
    val newest = ui.pending.lastOrNull()?.localId ?: ui.messages.lastOrNull()?.eventId
    val mineNewest = ui.pending.isNotEmpty() || ui.messages.lastOrNull()?.fromDevin == false
    var followNewest by rememberSaveable(id) { mutableStateOf(true) }
    var lastFollowed by rememberSaveable(id) { mutableStateOf<String?>(null) }
    LaunchedEffect(atBottom) { if (atBottom) followNewest = true }
    LaunchedEffect(list.isScrollInProgress) { if (list.isScrollInProgress && !atBottom) followNewest = false }
    LaunchedEffect(newest, tab) {
        if (tab == SessionTab.Chat && newest != null && newest != lastFollowed) {
            if (followNewest || mineNewest) list.animateScrollToItem(0)
            lastFollowed = newest
        }
    }
    val typingCramped = WindowInsets.isImeVisible &&
        androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp < 480
    val urls = uploads.readyAttachmentUrls()
    val canSend = urls != null && (input.isNotBlank() || urls.isNotEmpty())

    Scaffold(
        topBar = {
            if (!typingCramped) TopAppBar(
                navigationIcon = { DevinIconButton("Back", Icons.AutoMirrored.Outlined.ArrowBack, ::back) },
                title = {
                    Column {
                        Text(s?.displayTitle ?: "Session", maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                        if (s != null) Row(verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            StatusDot(s.state)
                            Text(s.state.label, style = MaterialTheme.typography.labelSmall)
                            if (s.acusConsumed > 0) Text("%.1f ACU".format(s.acusConsumed),
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                },
                actions = {
                    DevinIconButton("Session options", Icons.Outlined.MoreVert, { menu = true })
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        if (s != null) {
                            DropdownMenuItem(text = { Text("Open in browser") }, onClick = { menu = false; openUrl(ctx, s.url) })
                            DropdownMenuItem(text = { Text("Share link") }, onClick = { menu = false; shareText(ctx, s.url) })
                            DropdownMenuItem(text = { Text("Copy link") }, onClick = { menu = false; clipboard.setText(AnnotatedString(s.url)) })
                            DropdownMenuItem(text = { Text("Copy session ID") }, onClick = { menu = false; clipboard.setText(AnnotatedString(s.id)) })
                            if (s.state.isActive || s.state.needsAttention) DropdownMenuItem(text = { Text("Put to sleep") }, onClick = { menu = false; act("Going to sleep") { g.api.sleep(id) } })
                            DropdownMenuItem(text = { Text(if (s.isArchived) "Unarchive" else "Archive") }, onClick = {
                                menu = false
                                act(if (s.isArchived) "Restored" else "Archived") { if (s.isArchived) g.api.unarchive(id) else g.api.archive(id) }
                            })
                            DropdownMenuItem(text = { Text("Terminate", color = MaterialTheme.colorScheme.error) }, onClick = { menu = false; confirmTerminate = true })
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snack) },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().imePadding()) {
            if (!typingCramped || tab == SessionTab.Files) TabRow(selectedTabIndex = tab.ordinal) {
                SessionTab.entries.forEach { item ->
                    Tab(selected = tab == item, onClick = { tab = item },
                        modifier = Modifier.heightIn(min = 48.dp).testTag("tab-${item.name}"),
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(item.name)
                                if (item == SessionTab.Files) KnownCountBadge(ui.files.knownCount)
                            }
                        })
                }
            }
            if (tab == SessionTab.Files) {
                SessionFiles(ui.files, filesList, { vm.loadAttachments() }, { tab = SessionTab.Chat },
                    onOpen = { openFile(it, SessionTab.Files) },
                    restoreFocusId = restoreFileFocus, onFocusRestored = { restoreFileFocus = null })
            } else {
                if (ui.error != null) SessionBanner(
                    if (ui.offline) "Offline — showing saved messages." else "Couldn’t refresh this session. ${ui.error}",
                ) { TextButton(onClick = { scope.launch { vm.refresh() } }) { Text("Retry") } }
                if (s != null && !typingCramped) SessionContext(s, onOpenSession)
                Box(Modifier.weight(1f)) {
                    if (ui.loading && ui.messages.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.semantics { contentDescription = "Loading messages" })
                    } else LazyColumn(state = list, reverseLayout = true,
                        modifier = Modifier.fillMaxSize().testTag("messages"),
                        contentPadding = PaddingValues(DevinDimens.screenPadding),
                        verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.Bottom)) {
                        if (s?.state?.isActive == true) item(key = "working") { WorkingIndicator() }
                        items(ui.pending.asReversed(), key = { "pending-${it.localId}" }) { p ->
                            PendingBubble(p, { vm.retry(p) }, { vm.discard(p) })
                        }
                        // The API index doesn't contain a message id; never invent message ownership.
                        // Files without a matching reference remain easy to find in a named latest-files group.
                        val referenced = ui.files.rows.groupBy { a -> a.firstLinkedMessageId(ui.messages) }
                        val unreferenced = referenced[null].orEmpty()
                        if (unreferenced.isNotEmpty()) item(key = "shared-files") {
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text("Shared files", style = MaterialTheme.typography.labelMedium, modifier = Modifier.semantics { heading() })
                                unreferenced.forEach { a ->
                                    SessionFileCard(a, restoreFileFocus == a.id, { restoreFileFocus = null }) {
                                        openFile(a, SessionTab.Chat)
                                    }
                                }
                            }
                        }
                        items(ui.messages.asReversed(), key = { "message-${it.eventId}" }) { m ->
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                MessageBubble(m, ui.files.rows) { openFile(it, SessionTab.Chat) }
                                referenced[m.eventId].orEmpty().forEach { a ->
                                    SessionFileCard(a, restoreFileFocus == a.id, { restoreFileFocus = null }) {
                                        openFile(a, SessionTab.Chat)
                                    }
                                }
                            }
                        }
                        if (ui.messages.isEmpty() && ui.pending.isEmpty() && (s != null || ui.error == null)) item(key = "empty") {
                            Text("No messages yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (!atBottom && ui.messages.size + ui.pending.size > 3) SmallFloatingActionButton(
                        onClick = { scope.launch { list.animateScrollToItem(0) } },
                        modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                    ) { Icon(Icons.Outlined.KeyboardArrowDown, "Jump to latest") }
                }
                if (!typingCramped) when (s?.state) {
                    SessionState.NeedsApproval -> SessionBanner("Approval needed on the web. You can still reply here.") {
                        TextButton(onClick = { openUrl(ctx, s.url) }) { Text("Open") }
                    }
                    SessionState.Blocked -> SessionBanner("This session hit a usage or ACU limit. Raise the limit or reply to resume.")
                    else -> Unit
                }
                if (uploads.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    uploads.forEach { upload -> AttachmentUploadChip(upload, compact = typingCramped) {
                        vm.uploads.remove(upload.id)
                        restoreAttachFocus = true
                    } }
                }
                Composer(input, { input = it }, settings.enterToSend, canSend,
                    hint = if (s?.state == SessionState.NeedsYou) "Reply to Devin…" else "Message Devin…",
                    onAttach = { picker.launch("*/*") }, onSend = ::send,
                    maxHeight = if (typingCramped) 72.dp else 180.dp, attachFocus = attachFocus,
                    compact = typingCramped)
            }
        }
    }
    if (confirmTerminate) AlertDialog(onDismissRequest = { confirmTerminate = false },
        title = { Text("Terminate session?") }, text = { Text("Devin stops working on this for good. This can’t be undone.") },
        confirmButton = { TextButton(onClick = { confirmTerminate = false; act("Terminated") { g.api.terminate(id) } }) { Text("Terminate") } },
        dismissButton = { TextButton(onClick = { confirmTerminate = false }) { Text("Cancel") } })
}

@Composable
private fun SessionBanner(text: String, action: (@Composable () -> Unit)? = null) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
            Text(text, style = MaterialTheme.typography.bodySmall)
            action?.invoke()
        }
    }
}

@Composable
private fun SessionContext(s: Session, onOpenSession: (String) -> Unit) {
    val ctx = LocalContext.current
    if (s.pullRequests.isEmpty() && s.parentSessionId == null && s.childSessionIds.isNullOrEmpty()) return
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        s.pullRequests.forEach { pr -> AssistChip(onClick = { openUrl(ctx, pr.url) }, label = { Text("PR ${pr.label}") }) }
        s.parentSessionId?.let { p -> AssistChip(onClick = { onOpenSession(p) }, label = { Text("Parent session") }) }
        s.childSessionIds.orEmpty().forEachIndexed { i, c -> AssistChip(onClick = { onOpenSession(c) }, label = { Text("Child ${i + 1}") }) }
    }
}

@Composable
private fun MessageBubble(m: SessionMessage, files: List<SessionAttachment>, onOpen: (SessionAttachment) -> Unit) {
    val external = LocalUriHandler.current
    val uriHandler = remember(files, onOpen, external) { object : UriHandler {
        override fun openUri(uri: String) {
            val attachment = files.firstOrNull { it.url == uri }
            if (attachment != null) onOpen(attachment) else external.openUri(uri)
        }
    } }
    CompositionLocalProvider(LocalUriHandler provides uriHandler) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (m.fromDevin) Alignment.Start else Alignment.End,
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("${if (m.fromDevin) "Devin" else m.username ?: "You"} · ${relativeTime(m.createdAt)}",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (m.fromDevin) Markdown(m.message)
        else Box(Modifier.widthIn(max = 360.dp).clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(14.dp)) {
            Markdown(m.message, color = MaterialTheme.colorScheme.onSurface)
        }
    }
    }
}

@Composable
private fun PendingBubble(p: Pending, onRetry: () -> Unit, onDiscard: () -> Unit) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
        Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Text(p.text, Modifier.widthIn(max = 360.dp).padding(14.dp), style = MaterialTheme.typography.bodyLarge)
        }
        if (p.failed) {
            Text("Not sent${p.error?.let { ": $it" } ?: ""}", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            Row { TextButton(onClick = onRetry) { Text("Retry") }; TextButton(onClick = onDiscard) { Text("Discard") } }
        } else Text("Sending…", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun WorkingIndicator() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StatusDot(SessionState.Working)
        Text("Devin is working…", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun Composer(value: String, onChange: (String) -> Unit, enterToSend: Boolean, canSend: Boolean, hint: String,
    onAttach: () -> Unit, onSend: () -> Unit, maxHeight: Dp = 180.dp, attachFocus: FocusRequester? = null,
    compact: Boolean = false,
) {
    val fieldModifier = Modifier.testTag("composer").semantics { contentDescription = "Message" }
        .onPreviewKeyEvent { e ->
            if (enterToSend && e.type == KeyEventType.KeyDown && e.key == Key.Enter && !e.isShiftPressed) {
                if (canSend) onSend()
                true
            } else false
        }
    val options = KeyboardOptions(imeAction = if (enterToSend) ImeAction.Send else ImeAction.Default)
    val actions = KeyboardActions(onSend = { if (canSend) onSend() })
    val attach: @Composable () -> Unit = {
        DevinIconButton("Attach context", Icons.Outlined.Add, onAttach,
            modifier = if (attachFocus != null) Modifier.focusRequester(attachFocus)
                .focusProperties { canFocus = true } else Modifier)
    }
    val send: @Composable () -> Unit = {
        FilledIconButton(onSend, Modifier.size(48.dp).testTag("send"), enabled = canSend) {
            Icon(Icons.AutoMirrored.Outlined.Send, "Send message")
        }
    }
    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = if (compact) 4.dp else 12.dp)) {
        DevinOutlinedSurface(Modifier.fillMaxWidth()) {
                // Keep one input node through IME/inset changes, so opening the keyboard
                // never replaces the focused editor. A single row also reserves both actions.
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    attach()
                    BasicTextField(value, onChange,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp, max = maxHeight)
                            .padding(horizontal = 8.dp, vertical = 4.dp).then(fieldModifier),
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary), maxLines = if (compact) 2 else 6,
                        keyboardOptions = options, keyboardActions = actions,
                        decorationBox = { inner ->
                            Box(contentAlignment = Alignment.CenterStart) {
                                if (value.isEmpty()) Text(hint, style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                inner()
                            }
                        })
                    send()
                }
        }
    }
}
