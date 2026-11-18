package com.blazikener.devin.ui.create

import androidx.activity.compose.BackHandler
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.blazikener.devin.data.CreateSessionRequest
import com.blazikener.devin.data.DEVIN_MODES
import com.blazikener.devin.data.Playbook
import com.blazikener.devin.data.Repository
import com.blazikener.devin.graph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class Upload(val name: String, val url: String?, val error: String?)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CreateScreen(initialPrompt: String?, onBack: () -> Unit, onCreated: (String) -> Unit) {
    val ctx = LocalContext.current
    val g = ctx.graph
    val settings = g.settings.value
    val scope = rememberCoroutineScope()
    var prompt by rememberSaveable { mutableStateOf(initialPrompt ?: g.settings.draft("__new__")) }
    var title by rememberSaveable { mutableStateOf("") }
    var tags by rememberSaveable { mutableStateOf("") }
    var acu by rememberSaveable { mutableStateOf(settings.defaultAcuLimit?.toString() ?: "") }
    var mode by rememberSaveable { mutableStateOf(settings.defaultMode) }
    var busy by remember { mutableStateOf(false) }
    var created by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var repos by remember { mutableStateOf<List<Repository>>(emptyList()) }
    var playbooks by remember { mutableStateOf<List<Playbook>>(emptyList()) }
    val chosenRepos = remember { mutableStateListOf<String>() }
    var playbook by remember { mutableStateOf<Playbook?>(null) }
    var repoQuery by remember { mutableStateOf("") }
    val uploads = remember { mutableStateListOf<Upload>() }
    var uploading by remember { mutableStateOf(0) }

    fun leave() {
        if (!created) g.settings.saveDraft("__new__", prompt)
        onBack()
    }
    BackHandler { leave() }

    LaunchedEffect(Unit) {
        launch { runCatching { g.api.repositories() }.onSuccess { repos = it.items } }
        launch { runCatching { g.api.playbooks() }.onSuccess { playbooks = it.items } }
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
                uploads += r.fold({ Upload(it.name, it.url, null) }, { Upload(name, null, it.message) })
                uploading--
            }
        }
    }

    fun create() {
        if (prompt.isBlank()) { error = "Tell Devin what to do"; return }
        busy = true; error = null
        scope.launch {
            runCatching {
                g.api.createSession(
                    CreateSessionRequest(
                        prompt = prompt.trim(),
                        title = title.trim().ifEmpty { null },
                        tags = tags.split(',').map { it.trim() }.filter { it.isNotEmpty() }.ifEmpty { null },
                        maxAcuLimit = acu.toIntOrNull(),
                        playbookId = playbook?.id,
                        repos = chosenRepos.toList().ifEmpty { null },
                        attachmentUrls = uploads.mapNotNull { it.url }.ifEmpty { null },
                        createAsUserId = settings.createAsUserId,
                        devinMode = mode,
                    )
                )
            }.onSuccess {
                created = true
                g.settings.saveDraft("__new__", "")
                onCreated(it.id)
            }
                .onFailure { error = it.message; busy = false }
        }
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("New session") },
            navigationIcon = { IconButton(onClick = ::leave) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") } },
        )
    }) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = prompt, onValueChange = { prompt = it }, label = { Text("What should Devin do?") },
                modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp).testTag("prompt"),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { picker.launch("*/*") }) {
                    Icon(Icons.Outlined.AttachFile, null, Modifier.size(18.dp)); Text(" Attach")
                }
                if (uploading > 0) CircularProgressIndicator(Modifier.padding(start = 12.dp).size(18.dp), strokeWidth = 2.dp)
            }
            if (uploads.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                uploads.toList().forEach { u ->
                    InputChip(selected = u.error != null, onClick = { uploads.remove(u) },
                        label = { Text(if (u.error != null) "${u.name} — failed" else u.name) },
                        trailingIcon = { Icon(Icons.Outlined.Close, "Remove", Modifier.size(16.dp)) })
                }
            }
            Text("Mode", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                DEVIN_MODES.forEach { (value, label) -> FilterChip(selected = mode == value, onClick = { mode = value }, label = { Text(label) }) }
            }
            if (repos.isNotEmpty()) {
                Text("Repositories", style = MaterialTheme.typography.labelLarge)
                OutlinedTextField(value = repoQuery, onValueChange = { repoQuery = it }, placeholder = { Text("Filter repositories") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val q = repoQuery.trim().lowercase()
                    (repos.filter { it.fullName in chosenRepos } + repos.filter { it.fullName !in chosenRepos && (q.isEmpty() || it.fullName.lowercase().contains(q)) }.take(12)).forEach { r ->
                        val on = r.fullName in chosenRepos
                        FilterChip(selected = on, onClick = { if (on) chosenRepos.remove(r.fullName) else chosenRepos.add(r.fullName) }, label = { Text(r.fullName.ifEmpty { r.name }) })
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
                        DropdownMenuItem(text = { Text("No playbook") }, onClick = { playbook = null; open = false })
                        playbooks.forEach { p -> DropdownMenuItem(text = { Text(p.macro?.let { m -> "$m — ${p.title}" } ?: p.title) }, onClick = { playbook = p; open = false }) }
                    }
                }
            }
            OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Title (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = tags, onValueChange = { tags = it }, label = { Text("Tags, comma separated") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                value = acu, onValueChange = { acu = it.filter(Char::isDigit) }, label = { Text("Max ACUs (optional)") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(onClick = ::create, enabled = !busy && uploading == 0, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("create")) {
                if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Text("Start session")
            }
            TextButton(onClick = { prompt = ""; title = ""; tags = ""; uploads.clear(); chosenRepos.clear(); playbook = null }) { Text("Clear") }
        }
    }
}
