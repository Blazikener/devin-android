package com.blazikener.devin.ui.reader

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.blazikener.devin.AppGraph
import com.blazikener.devin.data.SessionAttachment
import com.blazikener.devin.ui.components.MarkdownParser
import com.blazikener.devin.ui.components.MdBlock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class ReaderUi(
    val loading: Boolean = true,
    val online: Boolean = true,
    val result: ReaderResult = ReaderResult(),
    val blocks: List<MdBlock>? = null,
    val anchor: ReadingAnchor? = null,
    val updated: Boolean = false,
    val working: Boolean = false,
)

internal fun readerOwner(g: AppGraph) = documentVersion(g.auth.account.value.toString().toByteArray())

class ReaderViewModel(
    private val graph: AppGraph,
    context: Context,
    private val session: String,
    private val id: String,
    private val initialAnchor: ReadingAnchor?,
) : ViewModel() {
    private val owner = readerOwner(graph)
    private val directory = File(context.cacheDir, "api/reader/$owner")
    private val store = DocumentStore(directory)
    private val source = object : ReaderSource {
        override suspend fun index(session: String): List<SessionAttachment> {
            checkIdentity()
            val files = graph.api.attachments(session)
            checkIdentity()
            graph.cache.putAttachments(session, files)
            mutable.update { it.copy(result = it.result.copy(file = files.find { attachment -> attachment.id == id })) }
            return files
        }
        override suspend fun cachedIndex(session: String) = graph.cache.attachments(session)
        override suspend fun download(file: SessionAttachment): ByteArray {
            checkIdentity()
            val bytes = graph.api.download(file.url, 2 * 1024 * 1024)
            checkIdentity()
            return bytes
        }
    }
    private val repository = ReaderRepository(source, store)
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private fun connected(): Boolean = connectivity.getNetworkCapabilities(connectivity.activeNetwork)
        ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    private val mutable = MutableStateFlow(ReaderUi(online = connected()))
    val ui: StateFlow<ReaderUi> = mutable
    private var request: Job? = null
    private var anchorWrite: Job? = null
    private var lastAnchor = initialAnchor
    private var watching = false
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { mutable.update { it.copy(online = connected()) } }
        override fun onLost(network: Network) { mutable.update { it.copy(online = connected()) } }
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) { mutable.update { it.copy(online = connected()) } }
    }

    init {
        watching = runCatching { connectivity.registerDefaultNetworkCallback(callback); true }.getOrDefault(false)
        retry()
    }

    private fun checkIdentity() {
        if (graph.auth.account.value == null || readerOwner(graph) != owner) throw CancellationException("Account changed")
    }

    fun retry() {
        request?.cancel()
        request = viewModelScope.launch {
            mutable.update { it.copy(loading = true) }
            val hint = store.read(session, id)?.file ?: source.cachedIndex(session)?.find { it.id == id }
            mutable.update { it.copy(result = it.result.copy(file = hint ?: it.result.file)) }
            val result = repository.load(session, id, mutable.value.online)
            ensureActive()
            checkIdentity()
            val document = result.document
            val saved = lastAnchor ?: store.anchor(session, id)
            val blocks = if (document != null && documentFormat(document.file) == DocumentFormat.Markdown) {
                withContext(Dispatchers.Default) { runCatching { MarkdownParser.parse(document.text) }.getOrNull() }
            } else null
            val anchor = document?.let { saved?.forVersion(it.version) ?: ReadingAnchor(it.version) }
            mutable.update { it.copy(loading = false, result = result, blocks = blocks, anchor = anchor,
                updated = document != null && saved != null && saved.version != document.version) }
            lastAnchor = anchor
        }
    }

    fun rememberAnchor(anchor: ReadingAnchor) {
        lastAnchor = anchor
        anchorWrite?.cancel()
        anchorWrite = viewModelScope.launch {
            checkIdentity()
            store.saveAnchor(session, id, anchor)
        }
    }

    fun noViewer() { mutable.update { it.copy(result = it.result.copy(problem = ReaderProblem.NoViewer), working = false) } }

    /** Only unsupported files use this export path; Markdown/text always remain in the Reader. */
    suspend fun export(): File? {
        val file = mutable.value.result.file ?: return null
        mutable.update { it.copy(working = true) }
        var downloaded: File? = null
        return try {
            checkIdentity()
            val target = withContext(Dispatchers.IO) { newExportFile(directory, file.name) }
            downloaded = target
            graph.api.downloadTo(file.url, target, 20 * 1024 * 1024)
            checkIdentity()
            target
        } catch (cancel: CancellationException) {
            downloaded?.delete()
            throw cancel
        } catch (failure: Exception) {
            val problem = if (failure is com.blazikener.devin.data.ApiException && failure.isAuth) ReaderProblem.Denied
                else if (failure is com.blazikener.devin.data.DocumentTooLargeException) ReaderProblem.ExportTooLarge else ReaderProblem.Transient
            if (problem == ReaderProblem.Denied) store.remove(session, id)
            mutable.update { it.copy(result = ReaderResult(file, problem = problem)) }
            null
        } finally {
            // Successful handoffs remain readable; remove only this attempt's empty directory.
            downloaded?.parentFile?.delete()
            mutable.update { it.copy(working = false) }
        }
    }

    override fun onCleared() {
        if (watching) connectivity.unregisterNetworkCallback(callback)
    }
}
