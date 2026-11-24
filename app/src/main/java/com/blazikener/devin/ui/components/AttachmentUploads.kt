package com.blazikener.devin.ui.components

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.blazikener.devin.data.ApiException
import com.blazikener.devin.data.AttachmentTooLarge
import com.blazikener.devin.data.AttachmentUnreadable
import com.blazikener.devin.data.DEFAULT_ATTACHMENT_NAME
import com.blazikener.devin.data.DevinApi
import com.blazikener.devin.data.DraftAttachment
import com.blazikener.devin.data.uploadPickedAttachment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.IOException

internal data class AttachmentUpload(
    val id: Long,
    val name: String = DEFAULT_ATTACHMENT_NAME,
    val result: DraftAttachment? = null,
    val error: String? = null,
) {
    val uploading: Boolean get() = result == null && error == null
}

internal fun List<AttachmentUpload>.readyAttachmentUrls(): List<String>? =
    map { (it.result ?: return null).url }

internal class AttachmentUploads(
    private val scope: CoroutineScope,
    private val context: Context,
    private val api: DevinApi,
) {
    private val _items = MutableStateFlow<List<AttachmentUpload>>(emptyList())
    val items = _items.asStateFlow()
    private val jobs = mutableMapOf<Long, Job>()
    private val queue = Semaphore(1)
    private var nextId = 0L

    fun add(uri: Uri) {
        val item = AttachmentUpload(++nextId)
        _items.update { it + item }
        jobs[item.id] = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val result = queue.withPermit {
                    api.uploadPickedAttachment(context, uri) { name ->
                        update(item.id) { it.copy(name = name) }
                    }
                }
                update(item.id) { it.copy(result = result) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val error = when (e) {
                    is AttachmentTooLarge, is AttachmentUnreadable, is ApiException -> e.message
                    is IOException -> "Couldn't upload. Check your connection."
                    else -> null
                } ?: "Couldn't upload the file."
                update(item.id) { it.copy(error = error) }
            } finally {
                jobs.remove(item.id)
            }
        }
        jobs.getValue(item.id).start()
    }

    fun remove(id: Long) {
        _items.update { items -> items.filterNot { it.id == id } }
        jobs.remove(id)?.cancel()
    }

    fun clear() {
        _items.value = emptyList()
        val pending = jobs.values.toList()
        jobs.clear()
        pending.forEach { it.cancel() }
    }

    private fun update(id: Long, transform: (AttachmentUpload) -> AttachmentUpload) {
        _items.update { items -> items.map { if (it.id == id) transform(it) else it } }
    }
}

internal class AttachmentUploadsViewModel(context: Context, api: DevinApi) : ViewModel() {
    val uploads = AttachmentUploads(viewModelScope, context, api)
}
