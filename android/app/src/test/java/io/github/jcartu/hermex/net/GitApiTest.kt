package io.github.jcartu.hermex.net

import io.github.jcartu.hermex.core.prefs.ServerConfig
import io.github.jcartu.hermex.model.GitBranchMode
import io.github.jcartu.hermex.model.GitCheckoutTarget
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class GitApiTest {
    private lateinit var server: MockWebServer
    private lateinit var api: HermesApi
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = false }

    @Before fun setUp() {
        server = MockWebServer(); server.start()
        api = HermesApi(OkHttpClient(), json, { ServerConfig(server.url("/").toString(), "pw") }, { emptyList() })
    }
    @After fun tearDown() = server.shutdown()

    private fun record(response: String = "{}", call: suspend () -> Any?): RecordedRequest {
        server.enqueue(MockResponse().setResponseCode(200).addHeader("Content-Type", "application/json").setBody(response))
        runBlocking { call() }
        return server.takeRequest().also {
            assertNull(it.getHeader("Origin")); assertNull(it.getHeader("Referer"))
        }
    }

    @Test fun `status and diff are session scoped`() {
        assertEquals("/api/git/status?session_id=s1", record("""{"git":{"is_git":true}}""") { api.gitStatus("s1") }.path)
        assertEquals(
            "/api/git/diff?session_id=s1&path=src/App.kt&kind=unstaged",
            record("""{"diff":{"path":"src/App.kt"}}""") { api.gitDiff("s1", "src/App.kt") }.path,
        )
    }

    @Test fun `new local checkout sends new mode and dirty guard`() {
        val request = record("""{"ok":true}""") {
            api.gitCheckout("s1", GitCheckoutTarget("main", GitBranchMode.LOCAL, "feature/type-j"))
        }
        assertEquals("POST", request.method)
        assertEquals("/api/git/checkout", request.path)
        val body = json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals("new", body["mode"]!!.jsonPrimitive.content)
        assertEquals("feature/type-j", body["new_branch"]!!.jsonPrimitive.content)
        assertEquals("block", body["dirty_mode"]!!.jsonPrimitive.content)
    }

    @Test fun `stash checkout omits dirty mode`() {
        val request = record("""{"ok":true}""") {
            api.gitStashCheckout("s1", GitCheckoutTarget("origin/main", GitBranchMode.REMOTE, track = true))
        }
        val body = json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals("remote", body["mode"]!!.jsonPrimitive.content)
        assertEquals(true, body["track"]!!.jsonPrimitive.content.toBoolean())
        assertFalse("dirty_mode" in body)
    }

    @Test fun `discard explicitly controls untracked deletion`() {
        val request = record("""{"ok":true,"git":{"is_git":true}}""") {
            api.gitDiscard("s1", listOf("a.kt", "new.txt"), deleteUntracked = true)
        }
        val body = json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals(listOf("a.kt", "new.txt"), body["paths"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(true, body["delete_untracked"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test fun `selected commit message and commit preserve path list`() {
        val generated = record("""{"ok":true,"message":"feat: type-j"}""") {
            api.gitCommitMessageSelected("s1", listOf("a.kt"))
        }
        assertEquals("/api/git/commit-message-selected", generated.path)
        assertEquals("a.kt", json.parseToJsonElement(generated.body.readUtf8()).jsonObject["paths"]!!.jsonArray.single().jsonPrimitive.content)

        val committed = record("""{"ok":true,"commit":"abc123"}""") {
            api.gitCommitSelected("s1", "feat: type-j", listOf("a.kt"))
        }
        val body = json.parseToJsonElement(committed.body.readUtf8()).jsonObject
        assertEquals("feat: type-j", body["message"]!!.jsonPrimitive.content)
        assertEquals("a.kt", body["paths"]!!.jsonArray.single().jsonPrimitive.content)
    }
}
