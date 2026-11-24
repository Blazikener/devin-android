package com.blazikener.devin.ui.create

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.blazikener.devin.data.*
import com.blazikener.devin.graph
import com.blazikener.devin.ui.components.*
import com.blazikener.devin.ui.theme.DevinDimens
import kotlinx.coroutines.CancellationException

private val DraftSaver = Saver<NewSessionDraft, String>(
    save = { json.encodeToString(NewSessionDraft.serializer(), it) },
    restore = { json.decodeFromString(NewSessionDraft.serializer(), it) },
)

private enum class SetupPicker(val title: String) {
    Mode("Mode"), Repositories("Repositories"), Playbook("Playbook"), Options("More options")
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CreateScreen(
    initialPrompt: String?,
    modeOverride: String? = null,
    onBack: () -> Unit,
    onCheckSessions: () -> Unit,
    onCreated: (String) -> Unit,
) {
    val context = LocalContext.current
    val graph = context.graph
    val settings = graph.settings.value
    val focus = LocalFocusManager.current
    val saved = remember { graph.settings.newSessionDraft() }
    var sharedChoice by rememberSaveable {
        mutableStateOf(!initialPrompt.isNullOrBlank() && saved.prompt.isNotBlank() && saved.prompt.trim() != initialPrompt.trim())
    }
    var form by rememberSaveable(stateSaver = DraftSaver) {
        mutableStateOf(saved.withCreateArguments(if (sharedChoice || saved.prompt.trim() == initialPrompt?.trim()) null else initialPrompt, modeOverride).withVersionFrom(saved))
    }
    var ownsDraft by rememberSaveable { mutableStateOf(graph.settings.hasNewSessionDraft() || !form.sameContentAs(saved)) }
    var lastSavedForm by rememberSaveable(stateSaver = DraftSaver) { mutableStateOf(saved) }
    val vm: CreateSessionViewModel = viewModel {
        CreateSessionViewModel(graph.api::createSession, graph.settings.createAttempts)
    }
    val submission by vm.ui.collectAsStateWithLifecycle()
    val busy = submission.submitting || submission.sessionId != null
    val attachmentVm: AttachmentUploadsViewModel = viewModel { AttachmentUploadsViewModel(context.applicationContext, graph.api) }
    val uploads by attachmentVm.uploads.items.collectAsStateWithLifecycle()
    var picker by rememberSaveable { mutableStateOf<SetupPicker?>(null) }
    var confirmClear by rememberSaveable { mutableStateOf(false) }

    // Persist synchronously at the edit boundary, including mode-only and ACU-only drafts.
    fun update(next: NewSessionDraft) {
        if (!next.sameContentAs(form)) ownsDraft = true
        form = next.withVersionFrom(form)
        if (ownsDraft && vm.ui.value.sessionId == null && form != lastSavedForm) {
            graph.settings.saveNewSessionDraft(form)
            lastSavedForm = form
        }
        vm.clearError()
    }
    // Arguments are saved once per entry; Back and rotation must not rewrite a newer draft.
    LaunchedEffect(Unit) {
        if (vm.ui.value.sessionId == null) {
            if (graph.settings.wasNewSessionDraftConsumed(form)) {
                form = graph.settings.newSessionDraft()
                lastSavedForm = form
                ownsDraft = graph.settings.hasNewSessionDraft()
            }
            if (ownsDraft && (form != lastSavedForm || !graph.settings.hasNewSessionDraft())) {
                graph.settings.saveNewSessionDraft(form)
                lastSavedForm = form
            }
        }
    }
    LaunchedEffect(submission.sessionId) {
        submission.sessionId?.let {
            onCreated(it)
            vm.acknowledgeCreated(it)
        }
    }
    LaunchedEffect(uploads) {
        val completed = attachmentVm.uploads.items.value.filter { it.result != null }
        if (completed.isNotEmpty()) {
            update(form.copy(attachments = (form.attachments + completed.mapNotNull { it.result }).distinctBy { it.url }))
            completed.forEach { attachmentVm.uploads.remove(it.id) }
        }
    }
    val files = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        uris.forEach(attachmentVm.uploads::add)
    }

    var repos by remember { mutableStateOf<List<Repository>>(emptyList()) }
    var playbooks by remember { mutableStateOf<List<Playbook>>(emptyList()) }
    var repoLoading by remember { mutableStateOf(true) }
    var playbookLoading by remember { mutableStateOf(true) }
    var repoError by remember { mutableStateOf(false) }
    var playbookError by remember { mutableStateOf(false) }
    var repoAttempt by remember { mutableIntStateOf(0) }
    var playbookAttempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(repoAttempt) {
        repoLoading = true; repoError = false
        try {
            val all = mutableListOf<Repository>()
            var after: String? = null
            do {
                val page = graph.api.repositories(first = 100, after = after)
                all += page.items
                repos = all.distinctBy { it.fullName }
                val next = page.endCursor
                if (!page.hasNextPage || next == null || next == after) break
                after = next
            } while (true)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            repoError = true
        } finally { repoLoading = false }
    }
    LaunchedEffect(playbookAttempt) {
        playbookLoading = true; playbookError = false
        try {
            val all = mutableListOf<Playbook>()
            var after: String? = null
            do {
                val page = graph.api.playbooks(first = 200, after = after)
                all += page.items
                playbooks = all.distinctBy { it.id }
                val next = page.endCursor
                if (!page.hasNextPage || next == null || next == after) break
                after = next
            } while (true)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            playbookError = true
        } finally { playbookLoading = false }
    }

    Scaffold(
        topBar = {
            DevinTopBar("New session", navigationIcon = {
                DevinIconButton("Back", Icons.AutoMirrored.Outlined.ArrowBack, onBack)
            }, actions = {
                TextButton(onClick = { confirmClear = true }, enabled = !busy && (!form.isEmpty || uploads.isNotEmpty())) { Text("Clear") }
            })
        },
        bottomBar = {
            if (!busy) Surface(color = MaterialTheme.colorScheme.surface) {
                Column(
                    Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(horizontal = DevinDimens.screenPadding, vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = {
                            if (attachmentVm.uploads.items.value.isEmpty()) {
                                focus.clearFocus()
                                vm.submit(form, settings.createAsUserId)
                            }
                        },
                        enabled = !submission.uncertain && form.prompt.isNotBlank() && uploads.isEmpty() && form.hasValidAcuLimit,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("create"),
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        Text("Start session", Modifier.weight(1f))
                        Icon(Icons.Outlined.ArrowUpward, null)
                    }
                    Text(
                        if (uploads.isNotEmpty()) "Finish uploading or remove files to start" else "Draft saved on this device",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
    ) { padding ->
        if (busy) {
            if (submission.submitting) Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                SessionLaunchCue(Modifier.fillMaxWidth())
            }
        } else Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = DevinDimens.screenPadding).padding(top = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Give it a good start.", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
                Text("A clear task is all you need.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (submission.uncertain) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "This session may already have started. Check Sessions before retrying.",
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
                    )
                    TextButton(onClick = onCheckSessions) { Text("Check Sessions") }
                    TextButton(
                        onClick = {
                            if (attachmentVm.uploads.items.value.isEmpty()) {
                                focus.clearFocus()
                                vm.submit(form, settings.createAsUserId, confirmedRetry = true)
                            }
                        },
                        enabled = form.prompt.isNotBlank() && uploads.isEmpty() && form.hasValidAcuLimit,
                    ) { Text("Retry anyway") }
                }
            }
            DevinOutlinedSurface(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(8.dp)) {
                    TextField(
                        value = form.prompt, onValueChange = { update(form.copy(prompt = it)) },
                        placeholder = { Text("What should Devin do?") },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp).testTag("prompt").semantics { contentDescription = "Task" },
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                            focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                            unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                        ),
                    )
                    TextButton(onClick = { files.launch("*/*") }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Icon(Icons.Outlined.AttachFile, null, Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp)); Text("Attach context")
                    }
                    if (form.attachments.isNotEmpty() || uploads.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        form.attachments.forEach { attachment ->
                            InputChip(
                                selected = false,
                                onClick = { update(form.copy(attachments = form.attachments - attachment)) },
                                modifier = Modifier.heightIn(min = 48.dp),
                                label = { Text(attachment.name) },
                                trailingIcon = { Icon(Icons.Outlined.Close, "Remove ${attachment.name}") },
                            )
                        }
                        uploads.forEach { upload -> AttachmentUploadChip(upload) { attachmentVm.uploads.remove(upload.id) } }
                    }
                }
            }
            Column {
                Text("Session setup", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp).semantics { heading() })
                SetupRow("Mode", DEVIN_MODES.firstOrNull { it.first == form.mode }?.second ?: form.mode.orEmpty(), Icons.Outlined.Bolt) { picker = SetupPicker.Mode }
                SetupRow("Repositories", form.repos.takeIf { it.isNotEmpty() }?.joinToString() ?: "None selected", Icons.Outlined.Folder) { picker = SetupPicker.Repositories }
                SetupRow("Playbook", playbooks.firstOrNull { it.id == form.playbookId }?.title ?: form.playbookId ?: "None", Icons.Outlined.Description) { picker = SetupPicker.Playbook }
                SetupRow("More options", if (form.hasValidAcuLimit) "ACU limit, title and tags" else "Review the ACU limit", Icons.Outlined.Tune) { picker = SetupPicker.Options }
            }
            submission.error?.let { message ->
                Text(message, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive })
            }
        }
    }

    picker?.let { selected ->
        ModalBottomSheet(onDismissRequest = { picker = null }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            var query by rememberSaveable(selected) { mutableStateOf("") }
            Column(Modifier.fillMaxWidth().imePadding().padding(horizontal = 20.dp).padding(bottom = 20.dp).heightIn(max = 580.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(selected.title, Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleLarge)
                    TextButton(onClick = { picker = null }) { Text("Done") }
                }
                if (selected == SetupPicker.Repositories || selected == SetupPicker.Playbook) {
                    OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), label = { Text("Search ${selected.title.lowercase()}") }, singleLine = true)
                }
                when (selected) {
                    SetupPicker.Mode -> LazyColumn(Modifier.selectableGroup()) {
                        items(DEVIN_MODES) { (mode, label) ->
                            RadioChoice(label, form.mode == mode) { update(form.copy(mode = mode)) }
                        }
                    }
                    SetupPicker.Repositories -> {
                        PickerStatus(repoLoading, repoError, repos.isEmpty(), "repositories") { repoAttempt++ }
                        val names = (form.repos + repos.map { it.fullName }).distinct().filter { it.contains(query, ignoreCase = true) }
                        LazyColumn(Modifier.weight(1f, fill = false)) {
                            if (names.isEmpty() && !repoLoading && !repoError) item { Text("No matching repositories", Modifier.padding(vertical = 16.dp)) }
                            items(names, key = { it }) { name ->
                                Row(
                                    Modifier.fillMaxWidth().heightIn(min = 56.dp).toggleable(value = name in form.repos, role = Role.Checkbox) {
                                        update(form.copy(repos = if (it) form.repos + name else form.repos - name))
                                    }.padding(vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Checkbox(name in form.repos, onCheckedChange = null)
                                    Text(name, Modifier.padding(start = 12.dp).weight(1f))
                                }
                            }
                        }
                    }
                    SetupPicker.Playbook -> {
                        PickerStatus(playbookLoading, playbookError, playbooks.isEmpty(), "playbooks") { playbookAttempt++ }
                        LazyColumn(Modifier.weight(1f, fill = false).selectableGroup()) {
                            item { RadioChoice("None", form.playbookId == null) { update(form.copy(playbookId = null)) } }
                            // A saved selection stays visible even if its metadata is unavailable offline.
                            if (form.playbookId != null && playbooks.none { it.id == form.playbookId }) item {
                                RadioChoice(form.playbookId.orEmpty(), true) { }
                            }
                            items(playbooks.filter { it.title.contains(query, true) || it.macro?.contains(query, true) == true }, key = { it.id }) { playbook ->
                                RadioChoice(playbook.macro?.let { "$it — ${playbook.title}" } ?: playbook.title, form.playbookId == playbook.id) { update(form.copy(playbookId = playbook.id)) }
                            }
                        }
                    }
                    SetupPicker.Options -> Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        OutlinedTextField(form.acu, { update(form.copy(acu = it)) }, Modifier.fillMaxWidth(), label = { Text("ACU limit (optional)") },
                            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), isError = !form.hasValidAcuLimit,
                            supportingText = { Text(if (form.hasValidAcuLimit) "Leave blank for the account default" else "Enter a whole number greater than 0") })
                        OutlinedTextField(form.title, { update(form.copy(title = it)) }, Modifier.fillMaxWidth(), label = { Text("Title (optional)") }, singleLine = true)
                        OutlinedTextField(form.tags, { update(form.copy(tags = it)) }, Modifier.fillMaxWidth(), label = { Text("Tags (optional)") }, supportingText = { Text("Separate tags with commas") })
                    }
                }
            }
        }
    }
    if (sharedChoice) AlertDialog(
        onDismissRequest = { sharedChoice = false }, title = { Text("Keep your saved task?") },
        text = { Text("There is already a task in this draft. Add the incoming task to it, or keep the draft unchanged.") },
        confirmButton = { TextButton(onClick = { update(form.withShared(initialPrompt)); sharedChoice = false }) { Text("Add to draft") } },
        dismissButton = { TextButton(onClick = { sharedChoice = false }) { Text("Keep draft") } },
    )
    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false }, title = { Text("Clear this draft?") }, text = {
            Text(if (submission.uncertain) "Check Sessions first: the previous session may already have started. Clearing removes this draft and resolves the warning so you can start a new task."
            else "The task, context and setup choices will be removed.")
        },
        confirmButton = { TextButton(onClick = {
            attachmentVm.uploads.clear()
            graph.settings.consumeNewSessionDraft(form)
            form = NewSessionDraft(acu = settings.defaultAcuLimit?.toString().orEmpty(), mode = settings.defaultMode)
            lastSavedForm = form
            ownsDraft = false
            vm.resolveUncertain()
            vm.clearError()
            confirmClear = false
        }) { Text("Clear draft") } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
    )
}

@Composable
private fun SetupRow(title: String, value: String, icon: ImageVector, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 72.dp).clickable(onClickLabel = "Choose $title", onClick = onClick).padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun RadioChoice(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).selectable(selected, role = Role.RadioButton, onClick = onClick).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, onClick = null)
        Text(label, Modifier.weight(1f).padding(start = 12.dp))
    }
}

@Composable
private fun PickerStatus(loading: Boolean, error: Boolean, empty: Boolean, kind: String, retry: () -> Unit) {
    if (loading) Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        Text("Loading $kind…", Modifier.padding(start = 12.dp).semantics { liveRegion = LiveRegionMode.Polite })
    } else if (error) Column {
        Text("Couldn't load $kind. Your saved choices are kept.", color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        TextButton(onClick = retry) { Text("Retry") }
    } else if (empty) Text("No $kind available", color = MaterialTheme.colorScheme.onSurfaceVariant)
}
