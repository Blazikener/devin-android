package com.blazikener.devin.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import java.io.File

/** Small on-disk JSON cache so the app opens instantly and works offline (read-only). */
class Cache(context: Context) {
    private val dir = File(context.cacheDir, "api").apply { mkdirs() }

    private suspend fun <T> write(name: String, s: KSerializer<T>, v: T) = withContext(Dispatchers.IO) {
        runCatching {
            val tmp = File(dir, "$name.tmp")
            tmp.writeText(json.encodeToString(s, v))
            tmp.renameTo(File(dir, "$name.json"))
        }
    }

    private suspend fun <T> read(name: String, s: KSerializer<T>): T? = withContext(Dispatchers.IO) {
        runCatching { json.decodeFromString(s, File(dir, "$name.json").readText()) }.getOrNull()
    }

    suspend fun sessions(key: String) = read("sessions_$key", ListSerializer(Session.serializer()))
    suspend fun putSessions(key: String, v: List<Session>) = write("sessions_$key", ListSerializer(Session.serializer()), v)
    suspend fun messages(id: String) = read("msgs_$id", ListSerializer(SessionMessage.serializer()))
    suspend fun putMessages(id: String, v: List<SessionMessage>) = write("msgs_$id", ListSerializer(SessionMessage.serializer()), v)
    suspend fun session(id: String) = read("session_$id", Session.serializer())
    suspend fun putSession(v: Session) = write("session_${v.id}", Session.serializer(), v)

    fun clear() { dir.deleteRecursively(); dir.mkdirs() }
}
