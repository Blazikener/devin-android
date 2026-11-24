package com.blazikener.devin.ui.session

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.blazikener.devin.AppGraph
import com.blazikener.devin.data.ApiException
import com.blazikener.devin.data.json
import com.blazikener.devin.data.SendMessageRequest
import com.blazikener.devin.data.Session
import com.blazikener.devin.data.SessionAttachment
import com.blazikener.devin.data.SessionMessage
import com.blazikener.devin.notify.Notifications
import com.blazikener.devin.notify.SessionWatcher
import com.blazikener.devin.ui.components.AttachmentUploads
import com.blazikener.devin.ui.components.readyAttachmentUrls
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.io.IOException

@Serializable
data class Pending(val localId: String, val text: String, val attachments: List<String>, val failed: Boolean = false, val error: String? = null)
/** Connection failures surface as raw OkHttp text ("Failed to connect to /10.0.2.2:8787"); show something a person can act on. */
internal fun Throwable.userMessage(): String? = when (this) {
    is ApiException -> message
    is IOException -> "No connection"
    else -> message
}

data class ChatUi(
    val session: Session? = null,
    val messages: List<SessionMessage> = emptyList(),
    val pending: List<Pending> = emptyList(),
    val attachments: List<SessionAttachment> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
    val offline: Boolean = false,
)

class SessionViewModel(private val g: AppGraph, private val ctx: Context, val id: String) : ViewModel() {
    internal val uploads = AttachmentUploads(viewModelScope, ctx, g.api)
    private val _ui = MutableStateFlow(ChatUi())
    val ui: StateFlow<ChatUi> = _ui
    private var cursor: String? = null
    private var poller: Job? = null
    // First fetch per screen is a full reload so stale cached history is replaced, not merged.
    private var synced = false
    // Ids of failed messages are kept while a retry is in flight.
    private val unsentIds = mutableSetOf<String>()

    init {
        viewModelScope.launch {
            val s = g.cache.session(id); val m = g.cache.messages(id)
            val unsent = g.settings.outbox(id)?.let { runCatching { json.decodeFromString(ListSerializer(Pending.serializer()), it) }.getOrNull() }.orEmpty()
            unsentIds += unsent.map { it.localId }
            if (s != null || m != null || unsent.isNotEmpty())
                _ui.update { it.copy(session = s, messages = m.orEmpty(), pending = unsent, loading = s == null && m == null) }
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
            if (e is CancellationException) throw e
            _ui.update { it.copy(loading = false, error = e.userMessage(), offline = it.messages.isNotEmpty()) }
        }
    }

    private fun loadAttachments() = viewModelScope.launch {
        runCatching { g.api.attachments(id) }.onSuccess { a -> _ui.update { it.copy(attachments = a) } }
    }

    fun draft(): String = g.settings.draft(id)
    fun saveDraft(t: String) = g.settings.saveDraft(id, t)

    fun send(text: String): Boolean {
        val urls = uploads.items.value.readyAttachmentUrls() ?: return false
        if (text.isBlank() && urls.isEmpty()) return false
        val p = Pending(System.nanoTime().toString(), text.trim().ifBlank { "See attached." }, urls)
        _ui.update { it.copy(pending = it.pending + p) }
        uploads.clear()
        saveDraft("")
        deliver(p)
        return true
    }

    fun retry(p: Pending) {
        _ui.update { u -> u.copy(pending = u.pending.map { if (it.localId == p.localId) it.copy(failed = false, error = null) else it }) }
        deliver(p.copy(failed = false))
    }

    fun discard(p: Pending) {
        _ui.update { u -> u.copy(pending = u.pending.filterNot { it.localId == p.localId }) }
        unsentIds -= p.localId
        saveOutbox()
    }

    private fun saveOutbox() {
        val unsent = _ui.value.pending.filter { it.localId in unsentIds }.map { it.copy(failed = true) }
        g.settings.saveOutbox(id, unsent.takeIf { it.isNotEmpty() }?.let { json.encodeToString(ListSerializer(Pending.serializer()), it) })
    }

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
            unsentIds -= p.localId
            saveOutbox()
            refresh()
        }.onFailure { e ->
            _ui.update { u -> u.copy(pending = u.pending.map { if (it.localId == p.localId) it.copy(failed = true, error = e.userMessage()) else it }) }
            unsentIds += p.localId
            saveOutbox()
        }
    }

    fun act(block: suspend () -> Session, onDone: (String?) -> Unit) = viewModelScope.launch {
        runCatching { block() }.onSuccess { s -> SessionWatcher.markSeen(ctx, s); _ui.update { it.copy(session = s) }; onDone(null) }.onFailure { onDone(it.message) }
    }

    suspend fun download(a: SessionAttachment): ByteArray = g.api.download(a.url)
}
