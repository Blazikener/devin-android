package com.blazikener.devin.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import java.io.IOException
import java.util.concurrent.TimeUnit

class ApiException(val code: Int, message: String) : IOException(message) {
    val isAuth: Boolean get() = code == 401 || code == 403
}

val json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    coerceInputValues = true
}

internal fun sameOrigin(a: String, b: String): Boolean {
    val first = a.toHttpUrlOrNull() ?: return false
    val second = b.toHttpUrlOrNull() ?: return false
    return first.scheme == second.scheme && first.host == second.host && first.port == second.port
}

/** Thin client for the public Devin v3 API (https://docs.devin.ai/api-reference). */
class DevinApi(
    private val baseUrl: () -> String,
    private val apiKey: () -> String?,
    private val orgId: () -> String?,
    client: OkHttpClient? = null,
) {
    val http: OkHttpClient = client ?: OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val jsonType = "application/json".toMediaType()

    private fun url(path: String, query: List<Pair<String, String?>> = emptyList()): HttpUrl {
        val b = (baseUrl().trimEnd('/') + path).toHttpUrl().newBuilder()
        query.forEach { (k, v) -> if (v != null) b.addQueryParameter(k, v) }
        return b.build()
    }

    private fun org(): String = orgId() ?: throw ApiException(0, "Not signed in")

    private fun Request.Builder.auth(key: String? = apiKey()): Request.Builder = apply {
        if (key != null) header("Authorization", "Bearer $key")
        header("Accept", "application/json")
        header("User-Agent", "DevinAndroid/0.1")
    }

    private suspend fun execute(req: Request): String = withContext(Dispatchers.IO) {
        http.newCall(req).execute().use { resp -> resp.bodyOrThrow() }
    }

    private fun Response.bodyOrThrow(): String {
        val text = body?.string().orEmpty()
        if (!isSuccessful) throw ApiException(code, errorMessage(code, text))
        return text
    }

    private fun errorMessage(code: Int, body: String): String {
        val detail = runCatching {
            val o = json.parseToJsonElement(body).jsonObject
            (o["detail"] ?: o["title"])?.jsonPrimitive?.content
        }.getOrNull()
        return when (code) {
            401 -> "API key was rejected (401). Check it in Settings."
            403 -> detail ?: "This key isn't allowed to do that (403)."
            404 -> detail ?: "Not found (404)."
            429 -> "Rate limited by Devin. Try again in a moment."
            in 500..599 -> "Devin's API is having trouble ($code)."
            else -> detail ?: "Request failed ($code)"
        }
    }

    private suspend fun get(path: String, query: List<Pair<String, String?>> = emptyList(), key: String? = apiKey()): String =
        execute(Request.Builder().url(url(path, query)).auth(key).get().build())

    private suspend fun post(path: String, body: String = "{}"): String =
        execute(Request.Builder().url(url(path)).auth().post(body.toRequestBody(jsonType)).build())

    suspend fun self(key: String): Self = json.decodeFromString(get("/v3/self", key = key))

    suspend fun listSessions(
        first: Int = 50,
        after: String? = null,
        archived: Boolean? = null,
        userIds: List<String>? = null,
        sessionIds: List<String>? = null,
        updatedAfter: Long? = null,
    ): Page<Session> {
        val q = mutableListOf<Pair<String, String?>>(
            "first" to first.toString(),
            "after" to after,
            "is_archived" to archived?.toString(),
            "updated_after" to updatedAfter?.toString(),
        )
        userIds?.forEach { q += "user_ids" to it }
        sessionIds?.forEach { q += "session_ids" to it }
        return json.decodeFromString(get("/v3/organizations/${org()}/sessions", q))
    }

    suspend fun session(id: String): Session =
        json.decodeFromString(get("/v3/organizations/${org()}/sessions/$id"))

    suspend fun messages(id: String, after: String? = null, first: Int = 200): Page<SessionMessage> =
        json.decodeFromString(
            get("/v3/organizations/${org()}/sessions/$id/messages", listOf("first" to "$first", "after" to after))
        )

    suspend fun sendMessage(id: String, req: SendMessageRequest): Session =
        json.decodeFromString(post("/v3/organizations/${org()}/sessions/$id/messages", json.encodeToString(SendMessageRequest.serializer(), req)))

    suspend fun createSession(req: CreateSessionRequest): Session {
        val payload = json.encodeToString(CreateSessionRequest.serializer(), req).toRequestBody(jsonType)
        val body = object : RequestBody() {
            override fun contentType() = payload.contentType()
            override fun contentLength() = payload.contentLength()
            override fun isOneShot() = true
            override fun writeTo(sink: BufferedSink) = payload.writeTo(sink)
        }
        return json.decodeFromString(execute(Request.Builder().url(url("/v3/organizations/${org()}/sessions")).auth().post(body).build()))
    }

    suspend fun sleep(id: String): Session = json.decodeFromString(post("/v3/organizations/${org()}/sessions/$id/sleep"))
    suspend fun archive(id: String): Session = json.decodeFromString(post("/v3/organizations/${org()}/sessions/$id/archive"))
    suspend fun unarchive(id: String): Session = json.decodeFromString(post("/v3/organizations/${org()}/sessions/$id/unarchive"))

    suspend fun terminate(id: String): Session = json.decodeFromString(
        execute(Request.Builder().url(url("/v3/organizations/${org()}/sessions/$id")).auth().delete().build())
    )

    suspend fun attachments(id: String): List<SessionAttachment> =
        json.decodeFromString(get("/v3/organizations/${org()}/sessions/$id/attachments"))

    suspend fun upload(name: String, mime: String, bytes: ByteArray): UploadedAttachment {
        val body: RequestBody = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", name, bytes.toRequestBody(mime.toMediaType()))
            .build()
        return json.decodeFromString(
            execute(Request.Builder().url(url("/v3/organizations/${org()}/attachments")).auth().post(body).build())
        )
    }

    suspend fun repositories(filter: String? = null, first: Int = 100, after: String? = null): Page<Repository> =
        json.decodeFromString(get("/v3/organizations/${org()}/repositories", listOf("first" to "$first", "filter_name" to filter, "after" to after)))

    suspend fun playbooks(first: Int = 200, after: String? = null): Page<Playbook> =
        json.decodeFromString(get("/v3/organizations/${org()}/playbooks", listOf("first" to "$first", "include_enterprise" to "true", "after" to after)))

    /** Downloads an auth-gated attachment. */
    suspend fun download(url: String): ByteArray = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url).apply {
            if (sameOrigin(url, baseUrl())) auth()
        }.build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw ApiException(resp.code, "Download failed (${resp.code})")
            resp.body!!.bytes()
        }
    }
}
