package com.blazikener.devin.ui.reader

import com.blazikener.devin.data.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** Each handoff has its own immutable path, including repeated exports of the same name. */
internal fun newExportFile(directory: File, name: String): File {
    pruneExports(directory)
    val attempt = File(directory, "exports/${UUID.randomUUID()}")
    check(attempt.mkdirs()) { "Cannot create export directory" }
    val basename = File(name).name.takeUnless { it.isBlank() || it == "." || it == ".." } ?: "document"
    return File(attempt, basename)
}

/** Reserve 20 MiB for the next download; retain recent handoffs within a 100 MiB cache budget. */
internal fun pruneExports(directory: File, keepBytes: Long = 80L * 1024 * 1024, now: Long = System.currentTimeMillis()) {
    var retained = 0L
    File(directory, "exports").listFiles().orEmpty().sortedByDescending { it.lastModified() }.forEach { attempt ->
        val size = attempt.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        if (now - attempt.lastModified() > 24L * 60 * 60 * 1000 || size > keepBytes - retained) {
            attempt.deleteRecursively()
        } else retained += size
    }
}

internal fun removeExport(file: File) {
    file.delete()
    file.parentFile?.delete()
}

/** Scoped to the current credential identity, inside Cache.clear()'s protected tree. */
class DocumentStore(private val directory: File) : ReaderStore {
    private fun file(session: String, id: String, suffix: String) =
        File(directory, documentVersion("$session\u0000$id".toByteArray()) + suffix)

    override suspend fun read(session: String, file: String): SavedDocument? = withContext(Dispatchers.IO) {
        runCatching { json.decodeFromString<SavedDocument>(file(session, file, ".json").readText()) }.getOrNull()
    }

    override suspend fun write(session: String, document: SavedDocument) = withContext(Dispatchers.IO) {
        runCatching {
            directory.mkdirs()
            val target = file(session, document.file.id, ".json")
            val temporary = File.createTempFile("document", ".tmp", directory)
            temporary.writeText(json.encodeToString(SavedDocument.serializer(), document))
            if (!temporary.renameTo(target)) temporary.delete()
        }
        Unit
    }

    override suspend fun remove(session: String, file: String) = withContext(Dispatchers.IO) {
        file(session, file, ".json").delete()
        file(session, file, ".anchor").delete()
        Unit
    }

    override suspend fun anchor(session: String, file: String): ReadingAnchor? = withContext(Dispatchers.IO) {
        runCatching { json.decodeFromString<ReadingAnchor>(file(session, file, ".anchor").readText()) }.getOrNull()
    }

    override suspend fun saveAnchor(session: String, file: String, anchor: ReadingAnchor) = withContext(Dispatchers.IO) {
        runCatching {
            directory.mkdirs()
            val target = file(session, file, ".anchor")
            val temporary = File.createTempFile("anchor", ".tmp", directory)
            temporary.writeText(json.encodeToString(ReadingAnchor.serializer(), anchor))
            if (!temporary.renameTo(target)) temporary.delete()
        }
        Unit
    }
}
