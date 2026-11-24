package com.blazikener.devin.data

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DevinApiTest {
    private lateinit var server: MockWebServer
    private lateinit var api: DevinApi

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = DevinApi(
            baseUrl = { server.url("/").toString() },
            apiKey = { "cog_x" },
            orgId = { "org-1" },
            client = OkHttpClient(),
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun selfSendsBearerKey() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"principal_type":"service_user","org_id":"org-1"}"""))

        assertEquals("org-1", api.self("cog_x").orgId)
        assertEquals("Bearer cog_x", server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun listSessionsBuildsQueryAndIgnoresUnknownFields() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"items":[{"session_id":"devin-abc","status":"running","future_field":"ignored"}],"end_cursor":"cursor-1","has_next_page":true,"total":9,"new_page_field":true}""",
            ),
        )

        val page = api.listSessions(first = 50, archived = false, userIds = listOf("u1"))
        val request = server.takeRequest()
        val url = request.requestUrl!!

        assertEquals("GET", request.method)
        assertEquals("/v3/organizations/org-1/sessions", url.encodedPath)
        assertEquals("50", url.queryParameter("first"))
        assertEquals("false", url.queryParameter("is_archived"))
        assertEquals(listOf("u1"), url.queryParameterValues("user_ids"))
        assertEquals("Bearer cog_x", request.getHeader("Authorization"))
        assertEquals(1, page.items.size)
        assertEquals("devin-abc", page.items.single().id)
        assertEquals("cursor-1", page.endCursor)
        assertTrue(page.hasNextPage)
        assertEquals(9, page.total)
    }

    @Test
    fun repositoriesSendsAfterCursor() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"items":[],"has_next_page":false}"""))

        api.repositories(after = "c1")

        val request = server.takeRequest()
        assertEquals("/v3/organizations/org-1/repositories", request.requestUrl!!.encodedPath)
        assertEquals("c1", request.requestUrl!!.queryParameter("after"))
    }

    @Test
    fun unauthorizedResponseIsReadableAuthException() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"detail":"Invalid API key"}"""))

        val exception = runCatching { api.self("cog_x") }.exceptionOrNull() as ApiException

        assertTrue(exception.isAuth)
        assertTrue(exception.message.orEmpty().contains("API key"))
        server.takeRequest()
        Unit
    }

    @Test
    fun rateLimitAndServerErrorsHaveReadableMessages() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(429).setBody("""{"detail":"slow down"}"""))
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"detail":"failed"}"""))

        val rateLimit = runCatching { api.self("cog_x") }.exceptionOrNull() as ApiException
        val serverError = runCatching { api.self("cog_x") }.exceptionOrNull() as ApiException

        assertFalse(rateLimit.message.isNullOrBlank())
        assertFalse(serverError.message.isNullOrBlank())
    }

    @Test
    fun sendMessagePostsNonNullRequestFieldsOnly() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"session_id":"devin-abc"}"""))

        api.sendMessage("devin-abc", SendMessageRequest("hello", attachmentUrls = listOf("https://example.test/file")))

        val request = server.takeRequest()
        val body = json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals("POST", request.method)
        assertEquals("/v3/organizations/org-1/sessions/devin-abc/messages", request.requestUrl!!.encodedPath)
        assertEquals("hello", body["message"]?.jsonPrimitive?.content)
        assertEquals("https://example.test/file", body["attachment_urls"]?.jsonArray?.single()?.jsonPrimitive?.content)
        assertFalse(body.containsKey("message_as_user_id"))
    }

    @Test
    fun terminateUsesDelete() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"session_id":"devin-abc","status":"exit"}"""))

        assertEquals("exit", api.terminate("devin-abc").status)
        val request = server.takeRequest()
        assertEquals("DELETE", request.method)
        assertEquals("/v3/organizations/org-1/sessions/devin-abc", request.requestUrl!!.encodedPath)
    }

    @Test
    fun uploadUsesMultipartFilePart() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"attachment_id":"file-1","name":"note.txt","url":"https://example.test/note.txt"}"""))

        val uploaded = api.upload("note.txt", "text/plain", "hello".toByteArray())
        val request = server.takeRequest()
        val contentType = request.getHeader("Content-Type")
        val requestBody = ByteArrayOutputStream()
        request.body!!.writeTo(requestBody)

        assertEquals("file-1", uploaded.id)
        assertNotNull(contentType)
        assertTrue(contentType.orEmpty().startsWith("multipart/form-data;"))
        assertTrue(requestBody.toString(Charsets.UTF_8).contains("name=\"file\"; filename=\"note.txt\""))
    }

    @Test
    fun sameOriginRequiresMatchingSchemeHostAndPort() {
        assertTrue(sameOrigin("https://api.devin.ai/v3", "https://api.devin.ai"))
        assertFalse(sameOrigin("https://api.devin.ai.attacker.example/x", "https://api.devin.ai"))
        assertFalse(sameOrigin("https://api.devin.ai:8443/x", "https://api.devin.ai"))
        assertFalse(sameOrigin("http://api.devin.ai/x", "https://api.devin.ai"))
    }

    @Test
    fun limitedDownloadRejectsDeclaredAndChunkedOversizeBodies() = runBlocking {
        server.enqueue(MockResponse().setBody("123456"))
        server.enqueue(MockResponse().setChunkedBody("123456", 2))
        repeat(2) {
            val failure = runCatching { api.download(server.url("/file").toString(), maxBytes = 5) }.exceptionOrNull()
            assertTrue(failure is DocumentTooLargeException)
        }
    }

    @Test
    fun limitedDownloadKeepsSameOriginAuthButNeverSendsItToAnotherOrigin() = runBlocking {
        server.enqueue(MockResponse().setBody("text"))
        assertEquals("text", api.download(server.url("/report.md").toString(), 10).decodeToString())
        assertEquals("Bearer cog_x", server.takeRequest().getHeader("Authorization"))
        val external = MockWebServer()
        external.start()
        try {
            external.enqueue(MockResponse().setBody("public"))
            assertEquals("public", api.download(external.url("/file").toString(), 10).decodeToString())
            assertEquals(null, external.takeRequest().getHeader("Authorization"))
        } finally { external.shutdown() }
    }
}
