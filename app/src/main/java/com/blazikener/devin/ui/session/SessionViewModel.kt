package com.blazikener.devin.ui.session

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.blazikener.devin.AppGraph
import com.blazikener.devin.data.SendMessageRequest
import com.blazikener.devin.data.Session
import com.blazikener.devin.data.SessionAttachment
import com.blazikener.devin.data.SessionMessage
import com.blazikener.devin.notify.Notifications
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class Pending(val localId: String, val text: String, val attachments: List<String>, val failed: Boolean = false, val error: String? = null)
data class Draft(val name: String, val mime: String, val uri: Uri, val size: Long, val uploadedUrl: String? = null, val uploading: Boolean = true, val error: String? = null)

data class ChatUi(
    val session: Session? = null,
    val messages: List<SessionMessage> = emptyList(),
    val pending: List<Pending> = emptyList(),
    val attachments: List<SessionAttachment> = emptyList(),
    val drafts: List<Draft> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
    val offline: Boolean = false,
)

class SessionViewModel(private val g: AppGraph, private val ctx: Context, val id: String) : ViewModel() {
    private val _ui = MutableStateFlow(ChatUi())
    val ui: StateFlow<ChatUi> = _ui
    private var cursor: String? = null
    private var poller: Job? = null

    init {
        viewModelScope.launch {
            val s = g.cache.session(id); val m = g.cache.messages(id)
            if (s != null || m != null) _ui.update { it.copy(session = s, messages = m.orEmpty(), loading = false) }
            refresh()
            loadAttachments()
        }
    }

    fun startPolling() {
        poller?.cancel()
        poller = viewModelScope.launch {
            while (isActive) {
                val active = _ui.value.session?.state?.isActive != false || _ui.value.pending.isNotEmpty()
                delay(if (active) 4_000 else 15_000)
                refresh()
            }
        }
        Notifications.cancel(ctx, id)
    }

    fun stopPolling() { poller?.cancel(); poller = null }

    // First fetch per screen is a full reload so stale cached history is replaced, not merged.
    private var synced = false

    suspend fun refresh() {
        val incremental = synced
        runCatching {
            val s = g.api.session(id)
            val fresh = mutableListOf<SessionMessage>()
            var c = if (incremental) cursor else null
            var pages = 0
            do {
                val page = g.api.messages(id, after = c)
                fresh += page.items
                if (page.endCursor != null) c = page.endCursor
            } while (page.hasNextPage && ++pages < 20)
            cursor = c
            s to fresh
        }.onSuccess { (s, fresh) ->
            val oldAtt = _ui.value.attachments.size
            synced = true
            _ui.update { u ->
                val all = ((if (incremental) u.messages else emptyList()) + fresh).distinctBy { it.eventId }.sortedBy { it.createdAt }
                // Drop optimistic messages once the server echoes them back.
                val echoed = fresh.filter { !it.fromDevin }.map { it.message.trim() }.toMutableList()
                val pend = u.pending.filter { p -> p.failed || !echoed.remove(p.text.trim()) }
                u.copy(session = s, messages = all, pending = pend, loading = false, error = null, offline = false)
            }
            g.cache.putSession(s)
            g.cache.putMessages(id, _ui.value.messages)
            _ui.value.messages.lastOrNull { it.fromDevin }?.let {
                ctx.getSharedPreferences("watcher", Context.MODE_PRIVATE).edit()
                    .putString("m_$id", it.eventId).putString("s_$id", "${s.state.name}|${s.updatedAt}").apply()
            }
            if (fresh.any { it.fromDevin } || oldAtt == 0) loadAttachments()
        }.onFailure { e ->
            _ui.update { it.copy(loading = false, error = e.message, offline = it.messages.isNotEmpty()) }
        }
    }

    private fun loadAttachments() = viewModelScope.launch {
        runCatching { g.api.attachments(id) }.onSuccess { a -> _ui.update { it.copy(attachments = a) } }
    }

    fun draft(): String = g.settings.draft(id)
    fun saveDraft(t: String) = g.settings.saveDraft(id, t)

    fun addFile(uri: Uri) {
        val (name, size) = ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
            c.moveToFirst()
            val n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { c.getString(it) } ?: "file"
            val sz = c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 }?.let { c.getLong(it) } ?: 0L
            n to sz
        } ?: ("file" to 0L)
        val mime = ctx.contentResolver.getType(uri) ?: "application/octet-stream"
        val d = Draft(name, mime, uri, size)
        _ui.update { it.copy(drafts = it.drafts + d) }
        viewModelScope.launch { upload(d) }
    }

    private suspend fun upload(d: Draft) {
        runCatching {
            val bytes = withContext(Dispatchers.IO) { ctx.contentResolver.openInputStream(d.uri)!!.use { it.readBytes() } }
            g.api.upload(d.name, d.mime, bytes)
        }.onSuccess { up ->
            _ui.update { u -> u.copy(drafts = u.drafts.map { if (it.uri == d.uri) it.copy(uploadedUrl = up.url, uploading = false) else it }) }
        }.onFailure { e ->
            _ui.update { u -> u.copy(drafts = u.drafts.map { if (it.uri == d.uri) it.copy(uploading = false, error = e.message) else it }) }
        }
    }

    fun removeDraft(d: Draft) = _ui.update { u -> u.copy(drafts = u.drafts.filterNot { it.uri == d.uri }) }

    val canSend: Boolean get() = _ui.value.drafts.none { it.uploading }

    fun send(text: String) {
        val urls = _ui.value.drafts.mapNotNull { it.uploadedUrl }
        if (text.isBlank() && urls.isEmpty()) return
        val p = Pending(System.nanoTime().toString(), text.trim(), urls)
        _ui.update { it.copy(pending = it.pending + p, drafts = emptyList()) }
        saveDraft("")
        deliver(p)
    }

    fun retry(p: Pending) {
        _ui.update { u -> u.copy(pending = u.pending.map { if (it.localId == p.localId) it.copy(failed = false, error = null) else it }) }
        deliver(p.copy(failed = false))
    }

    fun discard(p: Pending) = _ui.update { u -> u.copy(pending = u.pending.filterNot { it.localId == p.localId }) }

    private fun deliver(p: Pending) = viewModelScope.launch {
        runCatching {
            g.api.sendMessage(
                id,
                SendMessageRequest(
                    p.text.ifBlank { "See attached." },
                    messageAsUserId = g.settings.value.createAsUserId,
                    attachmentUrls = p.attachments.ifEmpty { null },
                ),
            )
        }.onSuccess { s ->
            _ui.update { it.copy(session = s) }
            refresh()
        }.onFailure { e ->
            _ui.update { u -> u.copy(pending = u.pending.map { if (it.localId == p.localId) it.copy(failed = true, error = e.message) else it }) }
        }
    }

    fun act(block: suspend () -> Session, onDone: (String?) -> Unit) = viewModelScope.launch {
        runCatching { block() }.onSuccess { s -> _ui.update { it.copy(session = s) }; onDone(null) }.onFailure { onDone(it.message) }
    }

    suspend fun download(a: SessionAttachment): ByteArray = g.api.download(a.url)
}
