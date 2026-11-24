package com.blazikener.devin.ui.reader

import com.blazikener.devin.data.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

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
