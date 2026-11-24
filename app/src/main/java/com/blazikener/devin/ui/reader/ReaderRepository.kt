package com.blazikener.devin.ui.reader

import com.blazikener.devin.data.ApiException
import com.blazikener.devin.data.DocumentTooLargeException
import com.blazikener.devin.data.SessionAttachment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest

enum class DocumentFormat(val label: String) { Markdown("Markdown"), PlainText("Plain text"), External("File"), Web("Web document") }

fun documentFormat(file: SessionAttachment): DocumentFormat {
    val extension = file.name.substringAfterLast('.', "").lowercase()
    val mime = file.contentType?.substringBefore(';')?.trim()?.lowercase()
    return when {
        extension in setOf("md", "markdown", "mdown") || mime in setOf("text/markdown", "text/x-markdown") -> DocumentFormat.Markdown
        extension in setOf("html", "htm") || mime == "text/html" -> DocumentFormat.Web
        extension in setOf("txt", "log", "csv", "json", "yaml", "yml", "xml") || mime?.startsWith("text/") == true -> DocumentFormat.PlainText
        else -> DocumentFormat.External
    }
}

@Serializable
data class ReadingAnchor(val version: String, val index: Int = 0, val offset: Int = 0) {
    fun forVersion(current: String): ReadingAnchor = if (version == current) copy(index = index.coerceAtLeast(0), offset = offset.coerceAtLeast(0)) else ReadingAnchor(current)
}

@Serializable
data class SavedDocument(val file: SessionAttachment, val text: String, val version: String, val savedAt: Long)

enum class ReaderProblem { Offline, Expired, Denied, Missing, Transient, TooLarge, Unsupported, WebOnly, NoViewer }

data class ReaderResult(
    val file: SessionAttachment? = null,
    val document: SavedDocument? = null,
    val problem: ReaderProblem? = null,
    val savedCopy: Boolean = false,
)

/** Only the known session attachment index resolves identities; route URLs are never trusted. */
interface ReaderSource {
    suspend fun index(session: String): List<SessionAttachment>
    suspend fun cachedIndex(session: String): List<SessionAttachment>?
    suspend fun download(file: SessionAttachment): ByteArray
}

interface ReaderStore {
    suspend fun read(session: String, file: String): SavedDocument?
    suspend fun write(session: String, document: SavedDocument)
    suspend fun remove(session: String, file: String)
    suspend fun anchor(session: String, file: String): ReadingAnchor?
    suspend fun saveAnchor(session: String, file: String, anchor: ReadingAnchor)
}

class ReaderRepository(private val source: ReaderSource, private val store: ReaderStore, private val now: () -> Long = System::currentTimeMillis) {
    suspend fun load(session: String, id: String, online: Boolean): ReaderResult {
        val cached = store.read(session, id)
        var file = cached?.file ?: source.cachedIndex(session)?.find { it.id == id }
        if (!online) return ReaderResult(file, cached, ReaderProblem.Offline, savedCopy = cached != null)
        try {
            file = source.index(session).find { it.id == id }
                ?: return ReaderResult(problem = ReaderProblem.Missing).also { store.remove(session, id) }
            when (documentFormat(file)) {
                DocumentFormat.External -> return ReaderResult(file, problem = ReaderProblem.Unsupported)
                DocumentFormat.Web -> return ReaderResult(file, problem = ReaderProblem.WebOnly)
                else -> Unit
            }
            val bytes = try {
                source.download(file)
            } catch (expired: ApiException) {
                // 401/403 are access failures, never blind retries. Only expired/missing links refresh once.
                if (expired.code !in setOf(404, 410)) throw expired
                file = source.index(session).find { it.id == id }
                    ?: return ReaderResult(problem = ReaderProblem.Missing).also { store.remove(session, id) }
                source.download(file)
            }
            val document = withContext(Dispatchers.Default) {
                SavedDocument(file, decodeDocument(bytes), documentVersion(bytes), now())
            }
            store.write(session, document)
            return ReaderResult(file, document)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (failure: Exception) {
            val problem = when (failure) {
                is ApiException -> when (failure.code) {
                    401, 403 -> ReaderProblem.Denied
                    404, 410 -> ReaderProblem.Expired
                    else -> ReaderProblem.Transient
                }
                is DocumentTooLargeException -> ReaderProblem.TooLarge
                is java.nio.charset.CharacterCodingException -> ReaderProblem.Unsupported
                else -> ReaderProblem.Transient
            }
            if (problem == ReaderProblem.Denied) store.remove(session, id)
            val usable = cached.takeIf { problem == ReaderProblem.Transient }
            return ReaderResult(file, usable, problem, savedCopy = usable != null)
        }
    }
}

internal fun documentVersion(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

internal fun decodeDocument(bytes: ByteArray): String {
    if (bytes.size > 2 * 1024 * 1024) throw DocumentTooLargeException()
    val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
    if ('\u0000' in text) throw java.nio.charset.MalformedInputException(1)
    return text
}
