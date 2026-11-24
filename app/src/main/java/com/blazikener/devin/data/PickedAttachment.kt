package com.blazikener.devin.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

private const val MAX_ATTACHMENT_MIB = 25
internal const val MAX_ATTACHMENT_BYTES = MAX_ATTACHMENT_MIB * 1024L * 1024L
internal const val DEFAULT_ATTACHMENT_NAME = "Attachment"

internal class AttachmentTooLarge : IOException("File exceeds the ${MAX_ATTACHMENT_MIB} MiB limit")
internal class AttachmentUnreadable : IOException("Couldn't read the file. Pick it again.")

internal suspend fun DevinApi.uploadPickedAttachment(
    context: Context,
    uri: Uri,
    onName: (String) -> Unit,
): DraftAttachment = withContext(Dispatchers.IO) {
    val resolver = context.contentResolver
    val (name, size, mime) = readPickedFile {
        val (displayName, reportedSize) = resolver.query(
            uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null,
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            val displayName = if (nameIndex >= 0) cursor.getString(nameIndex) else null
            val size = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex) else null
            displayName to size
        } ?: (null to null)
        val type = resolver.getType(uri)?.toMediaTypeOrNull()?.toString() ?: "application/octet-stream"
        Triple(displayName?.takeIf { it.isNotBlank() } ?: DEFAULT_ATTACHMENT_NAME, reportedSize, type)
    }
    onName(name)
    if (size != null && size > MAX_ATTACHMENT_BYTES) throw AttachmentTooLarge()

    val file = readPickedFile { File.createTempFile("upload-", ".tmp", context.cacheDir) }
    try {
        readPickedFile {
            val input = resolver.openInputStream(uri) ?: throw AttachmentUnreadable()
            input.use { source ->
                file.outputStream().use { target ->
                    copyAttachment(source, target, checkActive = { ensureActive() })
                }
            }
        }
        val uploaded = upload(name, mime, file)
        check(uploaded.url.isNotBlank())
        DraftAttachment(name, uploaded.url)
    } finally {
        file.delete()
    }
}

private inline fun <T> readPickedFile(block: () -> T): T = try {
    block()
} catch (e: AttachmentTooLarge) {
    throw e
} catch (_: IOException) {
    throw AttachmentUnreadable()
} catch (_: SecurityException) {
    throw AttachmentUnreadable()
} catch (_: IllegalArgumentException) {
    throw AttachmentUnreadable()
}

internal fun copyAttachment(
    input: InputStream,
    output: OutputStream,
    maxBytes: Long = MAX_ATTACHMENT_BYTES,
    checkActive: () -> Unit = {},
) {
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var remaining = maxBytes
    while (true) {
        checkActive()
        val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining + 1).toInt())
        if (count == -1) return
        if (count > remaining) throw AttachmentTooLarge()
        output.write(buffer, 0, count)
        remaining -= count
    }
}
