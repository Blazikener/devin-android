package com.blazikener.devin.ui.reader

import com.blazikener.devin.data.ApiException
import com.blazikener.devin.data.DocumentTooLargeException
import com.blazikener.devin.data.SessionAttachment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class ReaderRepositoryTest {
    private val markdown = SessionAttachment("report", "report.md", "https://files.example/old", "application/octet-stream")
    private class MemoryStore : ReaderStore {
        var saved: SavedDocument? = null
        var place: ReadingAnchor? = null
        override suspend fun read(session: String, file: String) = saved
        override suspend fun write(session: String, document: SavedDocument) { saved = document }
        override suspend fun remove(session: String, file: String) { saved = null; place = null }
        override suspend fun anchor(session: String, file: String) = place
        override suspend fun saveAnchor(session: String, file: String, anchor: ReadingAnchor) { place = anchor }
    }
    private inner class Source : ReaderSource {
        var requests = 0
        var downloads = mutableListOf<SessionAttachment>()
        var metadata: (Int) -> List<SessionAttachment> = { listOf(markdown) }
        var body: (SessionAttachment) -> ByteArray = { "# Report\n\nReadable **here**".toByteArray() }
        override suspend fun index(session: String): List<SessionAttachment> = metadata(++requests)
        override suspend fun cachedIndex(session: String) = listOf(markdown)
        override suspend fun download(file: SessionAttachment): ByteArray { downloads += file; return body(file) }
    }

    @Test fun markdownWithGenericMimeStaysNativeAndSavesContentVersion() = runTest {
        val source = Source(); val store = MemoryStore()
        val result = ReaderRepository(source, store) { 42L }.load("session", "report", true)
        assertEquals(DocumentFormat.Markdown, documentFormat(result.file!!))
        assertEquals("# Report\n\nReadable **here**", result.document!!.text)
        assertEquals(documentVersion(source.body(markdown)), result.document.version)
        assertEquals(42L, result.document.savedAt)
        assertEquals(result.document, store.saved)
    }

    @Test fun offlineSavedCopyIsReadableWithoutNetworkRequest() = runTest {
        val source = Source(); val store = MemoryStore()
        val repository = ReaderRepository(source, store)
        repository.load("session", "report", true)
        val offline = repository.load("session", "report", false)
        assertEquals(ReaderProblem.Offline, offline.problem)
        assertTrue(offline.savedCopy)
        assertNotNull(offline.document)
        assertEquals(1, source.requests)
        assertEquals(1, source.downloads.size)
    }

    @Test fun offlineWithoutSavedCopyKeepsFileContext() = runTest {
        val source = Source()
        val result = ReaderRepository(source, MemoryStore()).load("session", "report", false)
        assertEquals(markdown, result.file)
        assertEquals(ReaderProblem.Offline, result.problem)
        assertNull(result.document)
        assertEquals(0, source.requests)
    }

    @Test fun expiredUrlRefreshesIndexOnceAndDownloadsCurrentUrl() = runTest {
        val source = Source()
        source.metadata = { listOf(markdown.copy(url = if (it == 1) "old" else "new")) }
        source.body = { if (it.url == "old") throw ApiException(410, "Expired") else "Latest".toByteArray() }
        val result = ReaderRepository(source, MemoryStore()).load("session", "report", true)
        assertEquals("Latest", result.document!!.text)
        assertEquals(listOf("old", "new"), source.downloads.map { it.url })
        assertEquals(2, source.requests)
    }

    @Test fun stillExpiredStopsAfterOneRefreshAndNeverReturnsProtectedCache() = runTest {
        val source = Source(); val store = MemoryStore()
        val repository = ReaderRepository(source, store)
        repository.load("session", "report", true)
        source.body = { throw ApiException(404, "Expired") }
        val result = repository.load("session", "report", true)
        assertEquals(ReaderProblem.Expired, result.problem)
        assertNull(result.document)
        assertEquals(3, source.requests)
        assertEquals(3, source.downloads.size)
    }

    @Test fun accessDeniedNeverRetriesAndEvictsSavedDocumentAndAnchor() = runTest {
        for (status in listOf(401, 403)) {
            val source = Source(); val store = MemoryStore()
            val repository = ReaderRepository(source, store)
            repository.load("session", "report", true)
            store.place = ReadingAnchor(store.saved!!.version, 3, 10)
            source.body = { throw ApiException(status, "Denied") }
            val result = repository.load("session", "report", true)
            assertEquals(ReaderProblem.Denied, result.problem)
            assertNull(result.document)
            assertNull(store.saved)
            assertNull(store.place)
            assertEquals(2, source.requests)
            assertEquals(2, source.downloads.size)
        }
    }

    @Test fun deniedIndexDoesNotFallBackToCachedContent() = runTest {
        val source = Source(); val store = MemoryStore()
        val repository = ReaderRepository(source, store)
        repository.load("session", "report", true)
        source.metadata = { throw ApiException(403, "Denied") }
        assertEquals(ReaderProblem.Denied, repository.load("session", "report", true).problem)
        assertNull(store.saved)
        assertEquals(1, source.downloads.size)
    }

    @Test fun transientFailureRetainsUsableSavedCopy() = runTest {
        val source = Source(); val store = MemoryStore()
        val repository = ReaderRepository(source, store)
        val first = repository.load("session", "report", true)
        source.metadata = { throw IOException("No connection") }
        val result = repository.load("session", "report", true)
        assertEquals(ReaderProblem.Transient, result.problem)
        assertEquals(first.document, result.document)
        assertTrue(result.savedCopy)
    }

    @Test fun removedIdentityNeverDownloadsAnotherFileOrTrustsOldUrl() = runTest {
        val source = Source(); val store = MemoryStore()
        val repository = ReaderRepository(source, store)
        repository.load("session", "report", true)
        source.metadata = { listOf(markdown.copy(id = "different")) }
        assertEquals(ReaderProblem.Missing, repository.load("session", "report", true).problem)
        assertNull(store.saved)
        assertEquals(1, source.downloads.size)
    }

    @Test fun unsupportedAndWebDocumentsDoNotDownloadForNativePreview() = runTest {
        for ((name, problem) in listOf("report.pdf" to ReaderProblem.Unsupported, "report.html" to ReaderProblem.WebOnly)) {
            val source = Source()
            source.metadata = { listOf(markdown.copy(name = name)) }
            assertEquals(problem, ReaderRepository(source, MemoryStore()).load("session", "report", true).problem)
            assertTrue(source.downloads.isEmpty())
        }
    }

    @Test fun oversizedAndInvalidUtf8HaveExplicitRecovery() = runTest {
        val source = Source()
        source.body = { throw DocumentTooLargeException() }
        assertEquals(ReaderProblem.TooLarge, ReaderRepository(source, MemoryStore()).load("session", "report", true).problem)
        source.body = { byteArrayOf(0xc3.toByte(), 0x28) }
        assertEquals(ReaderProblem.Unsupported, ReaderRepository(source, MemoryStore()).load("session", "report", true).problem)
        source.body = { "binary\u0000text".toByteArray() }
        assertEquals(ReaderProblem.Unsupported, ReaderRepository(source, MemoryStore()).load("session", "report", true).problem)
    }

    @Test fun cancellationIsNotTurnedIntoAnErrorOrSavedCopy() = runTest {
        val source = Source(); source.body = { throw CancellationException("Back pressed") }
        try { ReaderRepository(source, MemoryStore()).load("session", "report", true); fail("Cancellation must propagate") }
        catch (_: CancellationException) { }
    }

    @Test fun onlySameContentVersionRestoresAnchor() {
        val original = documentVersion("original".toByteArray())
        val changed = documentVersion("changed".toByteArray())
        val anchor = ReadingAnchor(original, 9, 80)
        assertEquals(anchor, anchor.forVersion(original))
        assertEquals(ReadingAnchor(changed), anchor.forVersion(changed))
    }

    @Test fun plainTextIsLiteralAndUtf8BomIsHandled() {
        assertEquals(DocumentFormat.PlainText, documentFormat(markdown.copy(name = "notes.txt", contentType = "text/plain")))
        assertEquals("**literal** <script>", decodeDocument("\uFEFF**literal** <script>".toByteArray()))
    }
}
