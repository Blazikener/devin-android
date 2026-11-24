package com.blazikener.devin.ui.reader

import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.blazikener.devin.graph
import com.blazikener.devin.ui.components.DevinIconButton
import com.blazikener.devin.ui.navigation.SessionTab
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Date

@Composable
fun ReaderScreen(session: String, file: String, origin: SessionTab, initialAnchor: ReadingAnchor?, onAnchor: (ReadingAnchor) -> Unit,
    onBack: () -> Unit, onSettings: () -> Unit,
) {
    val context = LocalContext.current
    val graph = context.graph
    val account by graph.auth.account.collectAsStateWithLifecycle()
    key(account) {
        if (account != null) {
            val vm: ReaderViewModel = viewModel(key = "reader:$session:$file:${readerOwner(graph)}", factory = object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = ReaderViewModel(graph, context.applicationContext, session, file, initialAnchor) as T
            })
            ReaderSurface(vm, session, origin, onAnchor, onBack, onSettings)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReaderSurface(vm: ReaderViewModel, session: String, origin: SessionTab, onAnchor: (ReadingAnchor) -> Unit,
    onBack: () -> Unit, onSettings: () -> Unit,
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    var options by rememberSaveable { mutableStateOf(false) }
    var webConfirm by rememberSaveable { mutableStateOf(false) }
    var plain by rememberSaveable { mutableStateOf(false) }
    var sessionTitle by remember { mutableStateOf("Session document") }
    val title = ui.result.file?.name ?: "Document"
    val focus = remember { FocusRequester() }
    LaunchedEffect(session) { context.graph.cache.session(session)?.let { sessionTitle = it.displayTitle } }
    LaunchedEffect(title) { delay(350); focus.requestFocus() }
    val notice: (String) -> Unit = { scope.launch { snack.showSnackbar(it) }; Unit }

    fun external(share: Boolean) {
        scope.launch {
            val exported = vm.export() ?: return@launch
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.reader-files", exported)
            val mime = ui.result.file?.contentType ?: java.net.URLConnection.guessContentTypeFromName(exported.name) ?: "application/octet-stream"
            val intent = if (share) Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri)
                else Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            intent.clipData = android.content.ClipData.newRawUri("Document", uri)
            try { context.startActivity(if (share) Intent.createChooser(intent, "Share document") else intent) }
            catch (_: ActivityNotFoundException) { vm.noViewer() }
            catch (_: SecurityException) { notice("Couldn’t open another app. Try downloading the file.") }
        }
    }

    fun download() {
        scope.launch {
            val exported = vm.export() ?: return@launch
            val saved = withContext(Dispatchers.IO) {
                runCatching {
                    if (Build.VERSION.SDK_INT >= 29) {
                        val values = ContentValues().apply {
                            put(MediaStore.Downloads.DISPLAY_NAME, exported.name)
                            put(MediaStore.Downloads.MIME_TYPE, ui.result.file?.contentType ?: "application/octet-stream")
                            put(MediaStore.Downloads.IS_PENDING, 1)
                        }
                        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("No download location")
                        try {
                            context.contentResolver.openOutputStream(uri)?.use { output -> exported.inputStream().use { it.copyTo(output) } } ?: error("No download stream")
                            context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
                        } catch (failure: Exception) { context.contentResolver.delete(uri, null, null); throw failure }
                        "Saved to Downloads"
                    } else {
                        val directory = context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS) ?: error("Downloads unavailable")
                        exported.copyTo(File(directory, exported.name), overwrite = true)
                        "Saved to this app’s Downloads folder"
                    }
                }
            }
            notice(saved.getOrElse { "Couldn’t save the file. Try again." })
        }
    }

    Scaffold(snackbarHost = { SnackbarHost(snack) }, contentWindowInsets = WindowInsets.navigationBars) { insets ->
        Column(Modifier.fillMaxSize().padding(insets)) {
            Surface(color = MaterialTheme.colorScheme.surface) {
                Row(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically) {
                    DevinIconButton("Back to ${origin.name}", Icons.AutoMirrored.Outlined.ArrowBack, onBack)
                    Column(Modifier.weight(1f).padding(vertical = 12.dp)) {
                        Text(title, style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.focusRequester(focus).focusable().semantics { heading() })
                        Text(sessionTitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    DevinIconButton("More document actions", Icons.Outlined.MoreVert, { options = true })
                }
            }
            HorizontalDivider()
            if (ui.result.document != null) {
                val document = ui.result.document!!
                if (ui.result.savedCopy || !ui.online) {
                    val saved = DateFormat.getMediumDateFormat(context).format(Date(document.savedAt)) + " " + DateFormat.getTimeFormat(context).format(Date(document.savedAt))
                    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer).padding(horizontal = 20.dp, vertical = 8.dp)) {
                        Text("${if (!ui.online) "Offline" else "Couldn’t refresh"} · Saved copy from $saved", style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                        TextButton(onClick = vm::retry, enabled = ui.online && !ui.loading) { Text(if (ui.online) "Retry sync" else "Retry sync when online") }
                    }
                }
                if (ui.updated) Text("Updated document", style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp).semantics { liveRegion = LiveRegionMode.Polite })
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(if (plain || ui.blocks == null) "Plain text" else "Markdown", style = MaterialTheme.typography.labelMedium)
                    Text(if (document.file.source == "user") "Shared by you" else "Shared by Devin", style = MaterialTheme.typography.bodySmall)
                }
                if (documentFormat(document.file) == DocumentFormat.Markdown && ui.blocks == null) {
                    Text("Readable as plain text · The original source is shown below.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 20.dp))
                }
                key(document.version, plain) {
                    val count = if (plain || ui.blocks == null) document.text.lines().chunked(24).size else ui.blocks!!.size
                    val anchor = (if (plain) ReadingAnchor(document.version) else ui.anchor ?: ReadingAnchor(document.version))
                        .let { it.copy(index = it.index.coerceIn(0, (count - 1).coerceAtLeast(0))) }
                    val list = rememberLazyListState(anchor.index, anchor.offset)
                    val currentOnAnchor by rememberUpdatedState(onAnchor)
                    LaunchedEffect(list, document.version, plain) {
                        if (!plain) snapshotFlow { list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset }.distinctUntilChanged().collect { (index, offset) ->
                            val position = ReadingAnchor(document.version, index, offset)
                            currentOnAnchor(position)
                            vm.rememberAnchor(position)
                        }
                    }
                    ReaderDocument(document, ui.blocks, plain, list, Modifier.weight(1f), onCopied = { copied ->
                        notice(if (copied) "Code copied" else "Couldn’t copy. Select the code to copy it.")
                    })
                }
                TextButton(onClick = { options = true }, modifier = Modifier.fillMaxWidth()) { Text("Document options") }
            } else if (ui.loading) {
                Column(Modifier.weight(1f).fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("Loading $title", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    listOf(0.8f, 1f, 1f, 0.65f).forEach { width -> Box(Modifier.fillMaxWidth(width).height(20.dp).background(MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.shapes.small)) }
                    TextButton(onClick = onBack) { Text("Cancel") }
                }
            } else {
                val problem = (ui.result.problem ?: ReaderProblem.Transient).let {
                    if (it == ReaderProblem.Unsupported && ui.result.file?.let(::documentFormat) in setOf(DocumentFormat.Markdown, DocumentFormat.PlainText)) ReaderProblem.WebOnly else it
                }
                ReaderError(problem, title, ui.online, ui.working, origin,
                    onRetry = vm::retry, onBack = onBack, onSettings = onSettings,
                    onWeb = { webConfirm = true }, onExternal = { external(false) }, onShare = { external(true) }, onDownload = ::download)
            }
        }
    }
    if (options) ModalBottomSheet(onDismissRequest = { options = false }) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).verticalScroll(rememberScrollState())) {
            Text("Document options", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            if (ui.result.document != null && documentFormat(ui.result.document!!.file) == DocumentFormat.Markdown) {
                TextButton(onClick = { plain = !plain; options = false }) { Text(if (plain) "Read Markdown" else "Read plain text") }
            }
            TextButton(onClick = { options = false; download() }, enabled = ui.online && ui.result.file != null && !ui.working && ui.result.problem != ReaderProblem.Denied) { Text("Download file") }
            TextButton(onClick = { options = false; webConfirm = true }) { Text("Open session on app.devin.ai") }
            TextButton(onClick = { options = false }) { Text("Close") }
            Spacer(Modifier.height(24.dp))
        }
    }
    if (webConfirm) AlertDialog(onDismissRequest = { webConfirm = false }, title = { Text("Open app.devin.ai?") },
        text = { Text("Open this session in your browser to access the document. Your reading position and session stay here.") },
        confirmButton = { TextButton(onClick = {
            webConfirm = false
            try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://app.devin.ai/sessions/${Uri.encode(session)}"))) }
            catch (_: ActivityNotFoundException) { notice("No browser found. Install a browser and try again.") }
        }) { Text("Open in browser") } }, dismissButton = { TextButton(onClick = { webConfirm = false }) { Text("Stay here") } })
}

@Composable
private fun ReaderError(problem: ReaderProblem, file: String, online: Boolean, busy: Boolean, origin: SessionTab,
    onRetry: () -> Unit, onBack: () -> Unit, onSettings: () -> Unit, onWeb: () -> Unit,
    onExternal: () -> Unit, onShare: () -> Unit, onDownload: () -> Unit,
) {
    val (title, body) = when (problem) {
        ReaderProblem.Offline -> "You’re offline" to "Connect to open $file. Your place in the session is safe."
        ReaderProblem.Expired -> "This file link expired" to "Refresh the session’s file list and try its latest link. If it still fails, open the session on the web."
        ReaderProblem.Denied -> "You don’t have access" to "This account can’t read the file. Check your account in Settings or ask the file owner for access."
        ReaderProblem.Missing -> "File isn’t available" to "This file wasn’t found in the session’s current file list. It may have been removed."
        ReaderProblem.TooLarge -> "File is too large to preview" to "In-app previews support UTF-8 text up to 2 MB. You can download files up to 20 MB, or open the session on the web."
        ReaderProblem.Unsupported -> "No in-app preview" to "$file uses a format this Reader can’t display. Open a compatible Android app or download the original file."
        ReaderProblem.NoViewer -> "No compatible viewer found" to "Install a compatible Android app, download the file, or share it. Markdown and plain text open here."
        ReaderProblem.WebOnly -> "This document lives on the web" to "Open app.devin.ai in your browser to read the document. Your session stays here."
        ReaderProblem.Transient -> "Couldn’t load this file" to "Try again without leaving the session. Your reading position is kept for the same document version."
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.Center) {
        Icon(Icons.Outlined.Description, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
        Spacer(Modifier.height(20.dp))
        Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading(); liveRegion = LiveRegionMode.Polite })
        Spacer(Modifier.height(12.dp))
        Text(body, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(20.dp))
        when (problem) {
            ReaderProblem.Denied -> Button(onClick = onSettings) { Text("Account settings") }
            ReaderProblem.Unsupported -> Button(onClick = onExternal, enabled = online && !busy) { Text("Open with another app") }
            ReaderProblem.NoViewer, ReaderProblem.TooLarge -> Button(onClick = onDownload, enabled = online && !busy) { Text("Download file") }
            ReaderProblem.WebOnly -> Button(onClick = onWeb) { Text("Open on web") }
            else -> Button(onClick = onRetry, enabled = online && !busy) { Text(if (!online) "Retry when online" else if (problem == ReaderProblem.Expired) "Refresh file link" else "Retry") }
        }
        if (problem == ReaderProblem.NoViewer) TextButton(onClick = onShare, enabled = online && !busy) { Text("Share file") }
        if (problem != ReaderProblem.Offline && problem != ReaderProblem.WebOnly) TextButton(onClick = onWeb) { Text(if (problem == ReaderProblem.Denied) "Check access on web" else "Open session on web") }
        TextButton(onClick = onBack) { Text("Back to ${origin.name}") }
        if (busy) Text("Preparing file…", modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    }
}
