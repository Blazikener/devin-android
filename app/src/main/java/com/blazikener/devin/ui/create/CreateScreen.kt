package com.blazikener.devin.ui.create

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.blazikener.devin.data.DEVIN_MODES
import com.blazikener.devin.data.DraftAttachment
import com.blazikener.devin.data.NewSessionDraft
import com.blazikener.devin.data.Playbook
import com.blazikener.devin.data.Repository
import com.blazikener.devin.data.json
import com.blazikener.devin.graph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class Upload(val name: String, val url: String?, val error: String?)

private val DraftSaver = Saver<NewSessionDraft, String>(
    save = { json.encodeToString(NewSessionDraft.serializer(), it) },
    restore = { json.decodeFromString(NewSessionDraft.serializer(), it) },
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CreateScreen(
    initialPrompt: String?,
    modeOverride: String?,
    onBack: () -> Unit,
    onCheckRecents: () -> Unit,
    onCreated: (String) -> Unit,
) {
    val ctx = LocalContext.current
    val g = ctx.graph
    val settings = g.settings.value
    val scope = rememberCoroutineScope()
    val defaults = remember { NewSessionDraft(acu = settings.defaultAcuLimit?.toString() ?: "", mode = settings.defaultMode) }
    val formState = rememberSaveable(stateSaver = DraftSaver) {
        val draft = g.settings.newSessionDraft()
        mutableStateOf(draft.withCreateArguments(initialPrompt, modeOverride, g.createGate.attempts.value))
    }
    var form by remember<MutableState<NewSessionDraft>>(formState) {
        object : MutableState<NewSessionDraft> by formState {
            override var value: NewSessionDraft
                get() = formState.value
                set(value) {
                    g.settings.saveNewSessionDraft(value)
                    formState.value = value
                }
        }
    }
    val vm: CreateSessionViewModel = viewModel {
        CreateSessionViewModel(g.createGate)
    }
    val submission by vm.ui.collectAsStateWithLifecycle()
    val attempts by g.createGate.attempts.collectAsStateWithLifecycle()
    val inFlight by g.createGate.inFlight.collectAsStateWithLifecycle()
    val matching = attempts.firstOrNull { it.matches(form.prompt) }
    val recovered = submission.sessionId ?: matching?.sessionId
    val warning = matching?.takeIf { it.sessionId == null && (!it.dismissed || submission.confirmation == it) }
    val busy = inFlight || submission.submitting || recovered != null
    var repos by remember { mutableStateOf<List<Repository>>(emptyList()) }
    var playbooks by remember { mutableStateOf<List<Playbook>>(emptyList()) }
    val playbook = playbooks.firstOrNull { it.id == form.playbookId }
    var repoQuery by remember { mutableStateOf("") }
    val failedUploads = remember { mutableStateListOf<Upload>() }
    var uploading by remember { mutableStateOf(0) }

    // Saved on every edit so the form survives Back, leaving the app, and process death.
    LaunchedEffect(Unit) {
        if (recovered == null && !g.settings.newSessionDraft().shouldPreserveOnRecovery(matching)) {
            g.settings.saveNewSessionDraft(form)
        }
    }
    LaunchedEffect(recovered) { recovered?.let(onCreated) }

    LaunchedEffect(Unit) {
        launch {
            runCatching {
                val items = mutableListOf<Repository>()
                var after: String? = null
                var pages = 0
                while (pages < 20) {
                    val page = g.api.repositories(first = 100, after = after)
                    pages++
                    items += page.items
                    repos = items.toList()
                    if (!page.hasNextPage) break
                    val next = page.endCursor ?: break
                    if (next == after) break
                    after = next
                }
            }
        }
        launch {
            runCatching {
                val items = mutableListOf<Playbook>()
                var after: String? = null
                var pages = 0
                while (pages < 20) {
                    val page = g.api.playbooks(first = 200, after = after)
                    pages++
                    items += page.items
                    playbooks = items.toList()
                    if (!page.hasNextPage) break
                    val next = page.endCursor ?: break
                    if (next == after) break
                    after = next
                }
            }
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        uris.forEach { uri ->
            uploading++
            scope.launch {
                val name = uri.lastPathSegment?.substringAfterLast('/') ?: "file"
                val r = runCatching {
                    val bytes = withContext(Dispatchers.IO) { ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes() } }
                    g.api.upload(name, ctx.contentResolver.getType(uri) ?: "application/octet-stream", bytes)
                }
                r.fold(
                    { form = form.copy(attachments = form.attachments + DraftAttachment(it.name, it.url)) },
                    { failedUploads += Upload(name, null, it.message) },
                )
                uploading--
            }
        }
    }

    Scaffold(topBar = {
        Column {
            TopAppBar(
                title = { Text("New session") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") } },
                actions = {
                    if (submission.submitting) {
                        Row(
                            Modifier.padding(end = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            Text("Starting session…")
                        }
                    }
                },
            )
            if (warning != null && !busy) {
                CreateRecoveryWarning(
                    attempt = warning,
                    onCheckRecents = onCheckRecents,
                    onDismiss = { vm.dismiss(warning) },
                    onRetry = if (form.prompt.isNotBlank() && uploading == 0) {
                        { vm.submit(form, settings.createAsUserId, retry = warning) }
                    } else null,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                )
            }
        }
    }) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = form.prompt, onValueChange = { form = form.copy(prompt = it); vm.clearError() }, label = { Text("What should Devin do?") },
                modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp).testTag("prompt"),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { picker.launch("*/*") }) {
                    Icon(Icons.Outlined.AttachFile, null, Modifier.size(18.dp)); Text(" Attach")
                }
                if (uploading > 0) CircularProgressIndicator(Modifier.padding(start = 12.dp).size(18.dp), strokeWidth = 2.dp)
            }
            if (form.attachments.isNotEmpty() || failedUploads.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                form.attachments.forEach { a ->
                    InputChip(selected = false, onClick = { form = form.copy(attachments = form.attachments - a) },
                        label = { Text(a.name) },
                        trailingIcon = { Icon(Icons.Outlined.Close, "Remove", Modifier.size(16.dp)) })
                }
                failedUploads.toList().forEach { u ->
                    InputChip(selected = true, onClick = { failedUploads.remove(u) },
                        label = { Text("${u.name} — failed") },
                        trailingIcon = { Icon(Icons.Outlined.Close, "Remove", Modifier.size(16.dp)) })
                }
            }
            Text("Mode", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                DEVIN_MODES.forEach { (value, label) -> FilterChip(selected = form.mode == value, onClick = { form = form.copy(mode = value) }, label = { Text(label) }) }
            }
            if (repos.isNotEmpty()) {
                Text("Repositories", style = MaterialTheme.typography.labelLarge)
                OutlinedTextField(value = repoQuery, onValueChange = { repoQuery = it }, placeholder = { Text("Filter repositories") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val q = repoQuery.trim().lowercase()
                    (repos.filter { it.fullName in form.repos } + repos.filter { it.fullName !in form.repos && (q.isEmpty() || it.fullName.lowercase().contains(q)) }.take(12)).forEach { r ->
                        val on = r.fullName in form.repos
                        FilterChip(selected = on, onClick = { form = form.copy(repos = if (on) form.repos - r.fullName else form.repos + r.fullName) }, label = { Text(r.fullName.ifEmpty { r.name }) })
                    }
                }
            }
            if (playbooks.isNotEmpty()) {
                var open by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = it }) {
                    OutlinedTextField(
                        value = playbook?.let { it.macro ?: it.title } ?: "No playbook", onValueChange = {}, readOnly = true,
                        label = { Text("Playbook") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(open) },
                        modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
                    )
                    ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                        DropdownMenuItem(text = { Text("No playbook") }, onClick = { form = form.copy(playbookId = null); open = false })
                        playbooks.forEach { p -> DropdownMenuItem(text = { Text(p.macro?.let { m -> "$m — ${p.title}" } ?: p.title) }, onClick = { form = form.copy(playbookId = p.id); open = false }) }
                    }
                }
            }
            OutlinedTextField(value = form.title, onValueChange = { form = form.copy(title = it) }, label = { Text("Title (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = form.tags, onValueChange = { form = form.copy(tags = it) }, label = { Text("Tags, comma separated") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                value = form.acu, onValueChange = { form = form.copy(acu = it.filter(Char::isDigit)) }, label = { Text("Max ACUs (optional)") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            submission.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(onClick = {
                val version = form.takeUnless { g.settings.newSessionDraft().shouldPreserveOnRecovery(matching) }
                    ?.let(g.settings::saveNewSessionDraft)
                vm.submit(form, settings.createAsUserId, draftVersion = version)
            }, enabled = !busy && warning == null && uploading == 0, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("create")) {
                Text("Start session")
            }
            TextButton(onClick = {
                val savedDraft = g.settings.newSessionDraft()
                vm.clear(matching)
                if (savedDraft.shouldPreserveOnRecovery(matching)) formState.value = savedDraft else form = defaults
                failedUploads.clear()
            }, enabled = !busy && (form != defaults || failedUploads.isNotEmpty() || matching != null)) { Text("Clear") }
        }
    }
}
