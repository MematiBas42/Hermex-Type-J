package io.github.jcartu.hermex.net

import io.github.jcartu.hermex.core.prefs.ServerConfig
import io.github.jcartu.hermex.model.ChatMessage
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

/** Request shapes transcribed from APIClient+Sessions/ServerPanels.swift and Models/ChatMessage.swift. */
class ChatCoreRequestShapeTest {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    @Test fun `clear targets server history and effort includes session id`() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val api = HermesApi(OkHttpClient(), json, { ServerConfig(server.url("/").toString(), "pw") }, { emptyList() })
            server.enqueue(MockResponse().setBody("{\"ok\":true}"))
            api.clearSession("s1")
            val clear = server.takeRequest()
            assertEquals("POST", clear.method)
            assertEquals("/api/session/clear", clear.path)
            assertEquals("s1", json.parseToJsonElement(clear.body.readUtf8()).jsonObject["session_id"]!!.jsonPrimitive.content)
            assertNull(clear.getHeader("Origin"))
            assertNull(clear.getHeader("Referer"))

            server.enqueue(MockResponse().setBody("{}"))
            api.saveReasoningEffort("high", sessionId = "s1")
            val effort = server.takeRequest()
            assertEquals("/api/reasoning", effort.path)
            val body = json.parseToJsonElement(effort.body.readUtf8()).jsonObject
            assertEquals("high", body["effort"]!!.jsonPrimitive.content)
            assertEquals("s1", body["session_id"]!!.jsonPrimitive.content)
        } finally { server.shutdown() }
    }

    @Test fun `new session includes selected profile and project`() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val api = HermesApi(OkHttpClient(), json, { ServerConfig(server.url("/").toString(), "pw") }, { emptyList() })
            server.enqueue(MockResponse().setBody("{}"))
            api.createSession(null, null, null, "work", projectId = "project")
            val request = server.takeRequest()
            assertEquals("/api/session/new", request.path)
            val body = json.parseToJsonElement(request.body.readUtf8()).jsonObject
            assertEquals("work", body["profile"]!!.jsonPrimitive.content)
            assertEquals("project", body["project_id"]!!.jsonPrimitive.content)
        } finally { server.shutdown() }
    }

    @Test fun `turn duration and steering metadata decode tolerantly and round trip`() {
        val message = json.decodeFromString<ChatMessage>("""{"role":"user","content":"turn left","displayKind":"steer","_turn_duration":"4.5","unknown":{"nested":true}}""")
        assertTrue(message.isSteerMessage)
        assertEquals("turn left", message.steerText)
        assertEquals(4.5, message.turnDuration!!, 0.0)
        assertEquals(message, json.decodeFromString<ChatMessage>(json.encodeToString(ChatMessage.serializer(), message)))
    }
}
