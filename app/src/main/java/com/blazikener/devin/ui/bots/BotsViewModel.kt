package com.blazikener.devin.ui.bots

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.blazikener.devin.AppGraph
import com.blazikener.devin.data.ApiException
import com.blazikener.devin.data.Bot
import com.blazikener.devin.data.BotMessageRequest
import com.blazikener.devin.data.Session
import com.blazikener.devin.data.SessionMessage
import com.blazikener.devin.ui.session.userMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** The beta Bots API answers 403/404 when the key's organization or role has no Bots access. */
internal fun Throwable.botsMessage(): String? =
    if (this is ApiException && (code == 403 || code == 404)) "Bots aren't available for this API key." else userMessage()

data class BotsUi(val bots: List<Bot> = emptyList(), val loading: Boolean = true, val error: String? = null)

class BotsViewModel(private val g: AppGraph) : ViewModel() {
    private val _ui = MutableStateFlow(BotsUi())
    val ui: StateFlow<BotsUi> = _ui

    init { refresh() }

    fun refresh() = viewModelScope.launch {
        _ui.update { it.copy(loading = true) }
        runCatching {
            val all = mutableListOf<Bot>()
            var after: String? = null
            var pages = 0
            do {
                val page = g.api.listBots(after = after)
                all += page.items
                after = page.endCursor
            } while (page.hasNextPage && after != null && ++pages < 10)
            all.filterNot { it.isArchived }.sortedByDescending { it.updatedAt }
        }.onSuccess { bots -> _ui.value = BotsUi(bots, loading = false) }
            .onFailure { e ->
                if (e is CancellationException) throw e
                _ui.update { it.copy(loading = false, error = e.botsMessage()) }
            }
    }
}

data class PendingBotMessage(val localId: String, val text: String, val failed: Boolean = false, val error: String? = null)

data class BotUi(
    val bot: Bot? = null,
    val messages: List<SessionMessage> = emptyList(),
    val conversation: Session? = null,
    val pending: List<PendingBotMessage> = emptyList(),
    val sessions: List<Session> = emptyList(),
    val sessionsError: String? = null,
    val loading: Boolean = true,
    val error: String? = null,
)

class BotViewModel(private val g: AppGraph, val id: String) : ViewModel() {
    private val _ui = MutableStateFlow(BotUi())
    val ui: StateFlow<BotUi> = _ui
    private var poller: Job? = null

    init { viewModelScope.launch { refresh() } }

    fun startPolling() {
        poller?.cancel()
        poller = viewModelScope.launch {
            while (isActive) {
                val active = _ui.value.conversation?.state?.isActive == true || _ui.value.pending.any { !it.failed }
                delay(if (active) 4_000 else 15_000)
                refresh()
            }
        }
    }

    fun stopPolling() { poller?.cancel(); poller = null }

    suspend fun refresh() {
        runCatching {
            val bot = g.api.bot(id)
            val sessions = runCatching {
                val all = mutableListOf<Session>()
                var after: String? = null
                var pages = 0
                do {
                    val page = g.api.botSessions(id, first = 100, after = after)
                    all += page.items
                    after = page.endCursor
                } while (page.hasNextPage && after != null && ++pages < 10)
                all.distinctBy { it.id }
            }.onFailure { if (it is CancellationException) throw it }
            val convId = bot.conversationSessionId
            val conversation = convId?.let { runCatching { g.api.session(it) }.getOrNull() }
            val messages = convId?.let { cid ->
                runCatching {
                    val all = mutableListOf<SessionMessage>()
                    var after: String? = null
                    var pages = 0
                    do {
                        val page = g.api.messages(cid, after = after)
                        all += page.items
                        after = page.endCursor
                    } while (page.hasNextPage && after != null && ++pages < 20)
                    all.distinctBy { it.eventId }.sortedBy { it.createdAt }
                }.getOrNull()
            }
            Triple(bot, sessions, conversation to messages)
        }.onSuccess { (bot, sessions, conv) ->
            val (conversation, messages) = conv
            _ui.update { u ->
                val msgs = messages ?: u.messages
                val echoed = msgs.filter { !it.fromDevin }.map { it.message.trim() }.toMutableList()
                // Messages without a readable conversation stay listed as sent.
                val pend = if (bot.conversationSessionId == null) u.pending
                else u.pending.filter { p -> p.failed || !echoed.remove(p.text.trim()) }
                u.copy(
                    bot = bot,
                    sessions = sessions.getOrNull()?.sortedByDescending { it.updatedAt } ?: u.sessions,
                    sessionsError = sessions.exceptionOrNull()?.botsMessage(),
                    conversation = conversation ?: u.conversation, messages = msgs, pending = pend,
                    loading = false, error = null,
                )
            }
        }.onFailure { e ->
            if (e is CancellationException) throw e
            _ui.update { it.copy(loading = false, error = e.botsMessage()) }
        }
    }

    fun send(text: String) {
        val p = PendingBotMessage(java.util.UUID.randomUUID().toString(), text.trim())
        _ui.update { it.copy(pending = it.pending + p) }
        deliver(p)
    }

    fun retry(p: PendingBotMessage) {
        _ui.update { u -> u.copy(pending = u.pending.map { if (it.localId == p.localId) it.copy(failed = false, error = null) else it }) }
        deliver(p)
    }

    fun discard(p: PendingBotMessage) = _ui.update { u -> u.copy(pending = u.pending.filterNot { it.localId == p.localId }) }

    private fun deliver(p: PendingBotMessage) = viewModelScope.launch {
        runCatching { g.api.sendBotMessage(id, BotMessageRequest(p.text, messageAsUserId = g.settings.settings.value.createAsUserId)) }
            .onSuccess { refresh() }
            .onFailure { e ->
                if (e is CancellationException) throw e
                _ui.update { u -> u.copy(pending = u.pending.map { if (it.localId == p.localId) it.copy(failed = true, error = e.botsMessage()) else it }) }
            }
    }
}
