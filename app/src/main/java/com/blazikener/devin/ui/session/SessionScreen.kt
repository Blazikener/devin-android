package com.blazikener.devin.ui.session

import android.content.ContentValues
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.blazikener.devin.data.SessionAttachment
import com.blazikener.devin.data.SessionMessage
import com.blazikener.devin.data.SessionState
import com.blazikener.devin.graph
import com.blazikener.devin.ui.components.Markdown
import com.blazikener.devin.ui.components.StatusPill
import com.blazikener.devin.ui.components.openUrl
import com.blazikener.devin.ui.components.relativeTime
import com.blazikener.devin.ui.components.shareText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionScreen(id: String, sharedText: String? = null, onBack: () -> Unit, onOpenSession: (String) -> Unit) {
    val ctx = LocalContext.current
    val g = ctx.graph
    val vm: SessionViewModel = viewModel(key = id) { SessionViewModel(g, ctx.applicationContext, id) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val settings by g.settings.settings.collectAsStateWithLifecycle()
    var input by rememberSaveable { mutableStateOf(sharedText ?: vm.draft()) }
    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val list = rememberLazyListState()
    var menu by remember { mutableStateOf(false) }
    var confirmTerminate by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) vm.startPolling()
            if (e == Lifecycle.Event.ON_PAUSE) { vm.stopPolling(); vm.saveDraft(input) }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs); vm.stopPolling() }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris -> uris.forEach(vm::addFile) }

    val itemCount = ui.messages.size + ui.pending.size
    val atBottom by remember { derivedStateOf { !list.canScrollForward } }
    LaunchedEffect(itemCount) { if (itemCount > 0 && (atBottom || ui.pending.isNotEmpty())) list.animateScrollToItem(itemCount) }

    fun send() {
        if (!vm.canSend) return
        vm.send(input); input = ""
    }
    fun act(label: String, block: suspend () -> com.blazikener.devin.data.Session) =
        vm.act(block) { err -> scope.launch { snack.showSnackbar(err ?: label) } }

    val s = ui.session
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") } },
                title = {
                    Column {
                        Text(s?.displayTitle ?: "Session", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                        if (s != null) Row(verticalAlignment = Alignment.CenterVertically) {
                            StatusPill(s.state)
                            if (s.acusConsumed > 0) Text("  %.1f ACU".format(s.acusConsumed), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                },
                actions = {
                    if (s != null) IconButton(onClick = { openUrl(ctx, s.url) }) { Icon(Icons.AutoMirrored.Outlined.OpenInNew, "Open in browser") }
                    IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "More") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        if (s != null) {
                            DropdownMenuItem(text = { Text("Share link") }, onClick = { menu = false; shareText(ctx, s.url) })
                            DropdownMenuItem(text = { Text("Copy link") }, onClick = { menu = false; clipboard.setText(AnnotatedString(s.url)) })
                            DropdownMenuItem(text = { Text("Copy session ID") }, onClick = { menu = false; clipboard.setText(AnnotatedString(s.id)) })
                            if (s.state.isActive || s.state.needsAttention) DropdownMenuItem(text = { Text("Put to sleep") }, onClick = { menu = false; act("Going to sleep") { g.api.sleep(id) } })
                            if (s.isArchived) DropdownMenuItem(text = { Text("Unarchive") }, onClick = { menu = false; act("Restored") { g.api.unarchive(id) } })
                            else DropdownMenuItem(text = { Text("Archive") }, onClick = { menu = false; act("Archived") { g.api.archive(id) } })
                            DropdownMenuItem(text = { Text("Terminate", color = MaterialTheme.colorScheme.error) }, onClick = { menu = false; confirmTerminate = true })
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snack) },
        floatingActionButton = {
            if (!atBottom && itemCount > 3) SmallFloatingActionButton(onClick = { scope.launch { list.animateScrollToItem(itemCount) } }) {
                Icon(Icons.Outlined.KeyboardArrowDown, "Jump to latest")
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().imePadding()) {
            if (ui.offline) Banner(Icons.Outlined.CloudOff, "Offline — showing saved messages. ${ui.error ?: ""}")
            if (s != null) SessionContext(s, ui.attachments, onOpenSession, onAttachment = { a -> saveAttachment(ctx, vm, a, scope, snack) })
            Box(Modifier.weight(1f)) {
                if (ui.loading && ui.messages.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                else LazyColumn(state = list, modifier = Modifier.fillMaxSize().testTag("messages"), contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (ui.messages.isEmpty() && ui.pending.isEmpty()) item {
                        Text("No messages yet.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
                    }
                    items(ui.messages, key = { it.eventId }) { m -> MessageBubble(m) }
                    items(ui.pending, key = { it.localId }) { p -> PendingBubble(p, onRetry = { vm.retry(p) }, onDiscard = { vm.discard(p) }) }
                    if (s?.state?.isActive == true) item { WorkingIndicator() }
                }
            }
            when (s?.state) {
                SessionState.NeedsApproval -> Banner(Icons.Outlined.ErrorOutline, "Devin is waiting for an approval. Reply here, or open the session to approve the pending action.") {
                    TextButton(onClick = { openUrl(ctx, s.url) }) { Text("Open") }
                }
                SessionState.Blocked -> Banner(Icons.Outlined.ErrorOutline, "This session hit a usage or ACU limit. Raise the limit or reply to resume.")
                else -> {}
            }
            if (ui.drafts.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ui.drafts.forEach { d ->
                    InputChip(
                        selected = d.error != null, onClick = { vm.removeDraft(d) },
                        label = { Text(if (d.error != null) "${d.name} — failed" else d.name, maxLines = 1) },
                        leadingIcon = { if (d.uploading) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp) else Icon(Icons.Outlined.Description, null, Modifier.size(16.dp)) },
                        trailingIcon = { Icon(Icons.Outlined.Close, "Remove", Modifier.size(16.dp)) },
                    )
                }
            }
            Composer(
                value = input, onChange = { input = it }, enterToSend = settings.enterToSend,
                canSend = (input.isNotBlank() || ui.drafts.any { it.uploadedUrl != null }) && ui.drafts.none { it.uploading },
                hint = when (s?.state) {
                    SessionState.Sleeping, SessionState.Finished -> "Message to wake Devin…"
                    SessionState.NeedsYou -> "Reply to Devin…"
                    else -> "Message Devin…"
                },
                onAttach = { picker.launch("*/*") }, onSend = ::send,
            )
        }
    }
    if (confirmTerminate) AlertDialog(
        onDismissRequest = { confirmTerminate = false },
        title = { Text("Terminate session?") },
        text = { Text("Devin stops working on this for good. This can't be undone.") },
        confirmButton = { TextButton(onClick = { confirmTerminate = false; act("Terminated") { g.api.terminate(id) } }) { Text("Terminate", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { confirmTerminate = false }) { Text("Cancel") } },
    )
}

@Composable
private fun Banner(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, action: (@Composable () -> Unit)? = null) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            action?.invoke()
        }
    }
}

@Composable
private fun SessionContext(
    s: com.blazikener.devin.data.Session, attachments: List<SessionAttachment>,
    onOpenSession: (String) -> Unit, onAttachment: (SessionAttachment) -> Unit,
) {
    val ctx = LocalContext.current
    val devinFiles = attachments.filter { it.source != "user" }
    if (s.pullRequests.isEmpty() && devinFiles.isEmpty() && s.parentSessionId == null && s.childSessionIds.isNullOrEmpty()) return
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        s.pullRequests.forEach { pr ->
            AssistChip(onClick = { openUrl(ctx, pr.url) }, label = { Text("PR ${pr.label}" + (pr.state?.let { " · $it" } ?: "")) })
        }
        s.parentSessionId?.let { p -> AssistChip(onClick = { onOpenSession(p) }, label = { Text("Parent session") }) }
        s.childSessionIds.orEmpty().forEachIndexed { i, c -> AssistChip(onClick = { onOpenSession(c) }, label = { Text("Child ${i + 1}") }) }
        devinFiles.forEach { a ->
            AssistChip(onClick = { onAttachment(a) }, label = { Text(a.name, maxLines = 1) }, leadingIcon = { Icon(Icons.Outlined.Description, null, Modifier.size(16.dp)) })
        }
    }
}

@Composable
private fun MessageBubble(m: SessionMessage) {
    if (m.fromDevin) {
        Column(Modifier.fillMaxWidth()) {
            Text("Devin · ${relativeTime(m.createdAt)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.size(4.dp))
            Markdown(m.message)
        }
    } else {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
            Text(listOfNotNull(m.username ?: "You", relativeTime(m.createdAt)).joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.size(4.dp))
            Box(Modifier.widthIn(max = 340.dp).clip(RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp)).background(MaterialTheme.colorScheme.primaryContainer).padding(horizontal = 14.dp, vertical = 10.dp)) {
                Markdown(m.message, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
    }
}

@Composable
private fun PendingBubble(p: Pending, onRetry: () -> Unit, onDiscard: () -> Unit) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
        Box(Modifier.widthIn(max = 340.dp).clip(RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp)).background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)).padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text(p.text.ifBlank { "${p.attachments.size} attachment(s)" }, color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        if (p.failed) Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Not sent${p.error?.let { ": $it" } ?: ""}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f, fill = false))
            TextButton(onClick = onRetry) { Text("Retry") }
            TextButton(onClick = onDiscard) { Text("Discard") }
        } else Text("Sending…", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun WorkingIndicator() {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(8.dp))
        Text("Devin is working…", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun Composer(
    value: String, onChange: (String) -> Unit, enterToSend: Boolean, canSend: Boolean, hint: String,
    onAttach: () -> Unit, onSend: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(8.dp), verticalAlignment = Alignment.Bottom) {
        IconButton(onClick = onAttach) { Icon(Icons.Outlined.AttachFile, "Attach files") }
        OutlinedTextField(
            value = value, onValueChange = onChange, placeholder = { Text(hint) },
            modifier = Modifier.weight(1f).heightIn(min = 48.dp, max = 180.dp).testTag("composer").onPreviewKeyEvent { e ->
                if (enterToSend && e.type == KeyEventType.KeyDown && e.key == Key.Enter && !e.isShiftPressed && canSend) { onSend(); true } else false
            },
            shape = RoundedCornerShape(24.dp),
            keyboardOptions = KeyboardOptions(imeAction = if (enterToSend) ImeAction.Send else ImeAction.Default),
            keyboardActions = KeyboardActions(onSend = { if (canSend) onSend() }),
        )
        Spacer(Modifier.width(6.dp))
        FilledIconButton(onClick = onSend, enabled = canSend, modifier = Modifier.size(48.dp).testTag("send")) {
            Icon(Icons.AutoMirrored.Outlined.Send, "Send")
        }
    }
}

private fun saveAttachment(
    ctx: android.content.Context, vm: SessionViewModel, a: SessionAttachment,
    scope: kotlinx.coroutines.CoroutineScope, snack: SnackbarHostState,
) {
    scope.launch {
        runCatching {
            val bytes = vm.download(a)
            withContext(Dispatchers.IO) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, a.name)
                        put(MediaStore.Downloads.MIME_TYPE, a.contentType ?: "application/octet-stream")
                    }
                    val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)!!
                    ctx.contentResolver.openOutputStream(uri)!!.use { it.write(bytes) }
                } else {
                    val dir = ctx.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS)!!
                    java.io.File(dir, a.name).writeBytes(bytes)
                }
            }
        }.onSuccess { snack.showSnackbar("Saved ${a.name} to Downloads") }
            .onFailure { openUrl(ctx, a.url) }
    }
}
