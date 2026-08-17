package io.github.jcartu.hermex.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
        encodeDefaults = false
    }

    @Test
    fun `sessions response decodes snake_case rows and ignores unknown fields`() {
        val response = json.decodeFromString(
            SessionsResponse.serializer(),
            """
            {
              "sessions": [
                {
                  "session_id": "abc123",
                  "title": "Fix the build",
                  "workspace": "/srv/repo",
                  "model": "gpt-5",
                  "model_provider": "openai",
                  "message_count": 12,
                  "created_at": 1723890000.5,
                  "updated_at": 1723891000.5,
                  "last_message_at": 1723892000.5,
                  "pinned": true,
                  "archived": false,
                  "project_id": "proj-1",
                  "profile": "default",
                  "input_tokens": 100,
                  "output_tokens": 200,
                  "estimated_cost": 0.42,
                  "active_stream_id": "stream-1",
                  "is_streaming": true,
                  "is_cli_session": false,
                  "user_message_count": 4,
                  "has_pending_user_message": false,
                  "worktree_path": "/srv/repo/.wt",
                  "source_tag": "webui",
                  "parent_session_id": "parent-1",
                  "relationship_type": "fork",
                  "read_only": false,
                  "is_read_only": false,
                  "match_type": "title",
                  "brand_new_server_field": {"x": 1}
                }
              ],
              "cli_count": 3,
              "archived_count": 7,
              "server_time": 1723892222.0,
              "server_tz": "America/New_York",
              "unknown_top_level": [true]
            }
            """.trimIndent(),
        )

        assertEquals(3, response.cliCount)
        assertEquals(7, response.archivedCount)
        assertEquals("America/New_York", response.serverTz)

        val session = response.sessions!!.single()
        assertEquals("abc123", session.sessionId)
        assertEquals("abc123", session.id)
        assertEquals("Fix the build", session.title)
        assertEquals("openai", session.modelProvider)
        assertEquals(12, session.messageCount)
        assertEquals(1723890000.5, session.createdAt!!, 0.0)
        assertEquals(true, session.pinned)
        assertEquals("proj-1", session.projectId)
        assertEquals(100, session.inputTokens)
        assertEquals(0.42, session.estimatedCost!!, 0.0)
        assertEquals("stream-1", session.activeStreamId)
        assertEquals(true, session.isStreaming)
        assertEquals(4, session.userMessageCount)
        assertEquals("parent-1", session.parentSessionId)
        assertEquals("title", session.matchType)
    }

    @Test
    fun `summary id falls back to title and timestamp`() {
        val summary = SessionSummary(title = "  My chat  ", createdAt = 12.5)
        assertEquals("session-My chat-12.5", summary.id)
        assertEquals("session-untitled-0.0", SessionSummary().id)
    }

    @Test
    fun `replacingTitle preserves all other metadata`() {
        val summary = SessionSummary(sessionId = "s1", title = "old", pinned = true, inputTokens = 9)
        val renamed = summary.replacingTitle("new")
        assertEquals("new", renamed.title)
        assertEquals(summary.copy(title = "new"), renamed)
    }

    @Test
    fun `source marker helpers classify subagent claude and cron rows`() {
        assertTrue(SessionSummary(sessionId = "s", sourceTag = " Subagent ").isDelegatedSubagentSession)
        assertFalse(SessionSummary(sessionId = "s", sourceLabel = "claude_code").isClaudeCodeSession)
        assertTrue(SessionSummary(sessionId = "s", rawSource = "claude_code").isClaudeCodeSession)
        assertTrue(SessionSummary(sessionId = "CRON_daily").isCronSession)
        assertTrue(SessionSummary(sessionId = "s", sessionSource = "cron").isCronSession)
        assertFalse(SessionSummary(sessionId = "s").isCronSession)
        assertTrue(SessionSummary(sessionId = "s", sourceTag = "subagent").isSessionReadOnly)
        assertTrue(SessionSummary(sessionId = "s", isReadOnly = true).isSessionReadOnly)
        assertFalse(SessionSummary(sessionId = "s").isSessionReadOnly)
    }

    @Test
    fun `empty untitled placeholder is hidden from the session list`() {
        val placeholder = SessionSummary(sessionId = "s", title = "Untitled", messageCount = 0)
        assertTrue(placeholder.isEmptySidebarPlaceholder)
        assertFalse(placeholder.shouldAppearInSessionList)

        // Any sidebar state keeps the row visible.
        assertFalse(placeholder.copy(pinned = true).isEmptySidebarPlaceholder)
        assertFalse(placeholder.copy(activeStreamId = "st").isEmptySidebarPlaceholder)
        assertFalse(placeholder.copy(messageCount = 2).isEmptySidebarPlaceholder)
        assertFalse(SessionSummary(sessionId = "s", title = "Real title").isEmptySidebarPlaceholder)
    }

    @Test
    fun `automated visibility toggles hide matching rows`() {
        val cron = SessionSummary(sessionId = "cron_1")
        val cli = SessionSummary(sessionId = "s", isCliSession = true)
        val subagent = SessionSummary(sessionId = "s", sourceTag = "subagent")
        val plain = SessionSummary(sessionId = "s")

        assertTrue(AutomatedSessionVisibility.showAll.shows(cron))
        assertTrue(AutomatedSessionVisibility.showAll.shows(subagent))

        val defaults = AutomatedSessionVisibility(showsCron = false, showsCli = false)
        assertFalse(defaults.shows(cron))
        assertFalse(defaults.shows(cli))
        assertFalse(defaults.shows(subagent))
        assertTrue(defaults.shows(plain))
    }

    @Test
    fun `session detail decodes lossy scalars alternate keys and tolerant arrays`() {
        val response = json.decodeFromString(
            SessionResponse.serializer(),
            """
            {
              "session": {
                "session_id": 987,
                "title": "Detail",
                "message_count": "3",
                "pinned": "yes",
                "estimated_cost": "0.5",
                "pending_user_message": "queued",
                "pending_attachments": [{"name": "a.png"}],
                "context_length": 200000.0,
                "is_cli_session": 0,
                "messages": [
                  {"role": "user", "content": "hi", "message_id": "m1"},
                  17,
                  {"role": "assistant", "content": "yo", "message_id": "m2"}
                ],
                "_messages_truncated": true,
                "_messages_offset": "40",
                "compression_anchor_visible_idx": 5,
                "compression_anchor_message_key": {"role": "user", "ts": 1.5, "text": "hi", "attachments": 0},
                "compression_anchor_summary": "compacted",
                "unknown_field": "whatever"
              }
            }
            """.trimIndent(),
        )

        val detail = response.session!!
        assertEquals("987", detail.sessionId)
        assertEquals(3, detail.messageCount)
        assertEquals(true, detail.pinned)
        assertEquals(0.5, detail.estimatedCost!!, 0.0)
        assertEquals("queued", detail.pendingUserMessage)
        assertEquals(1, detail.pendingAttachments!!.size)
        assertEquals(200000, detail.contextLength)
        assertEquals(false, detail.isCliSession)
        // Malformed message entry (17) dropped, valid ones kept.
        assertEquals(listOf("m1", "m2"), detail.messages!!.map { it.messageId })
        assertEquals(true, detail.messagesTruncated)
        assertEquals(40, detail.messagesOffset)
        assertEquals(5, detail.compressionAnchorVisibleIdx)
        assertEquals(
            CompressionAnchorMessageKey(role = "user", ts = 1.5, text = "hi", attachments = 0),
            detail.compressionAnchorMessageKey,
        )
        assertEquals("compacted", detail.compressionAnchorSummary)
    }

    @Test
    fun `summary from detail infers pending flag and message count`() {
        val detail = json.decodeFromString(
            SessionDetail.serializer(),
            """
            {
              "session_id": "s1",
              "title": "T",
              "pending_user_message": "waiting",
              "messages": [
                {"role": "user", "content": "a"},
                {"role": "assistant", "content": "b"}
              ]
            }
            """.trimIndent(),
        )

        val summary = SessionSummary(detail)
        assertEquals("s1", summary.sessionId)
        assertEquals(2, summary.messageCount)
        assertEquals(true, summary.hasPendingUserMessage)
        assertNull(summary.isStreaming)
        assertNull(summary.matchType)

        val quiet = SessionSummary(detail.copy(pendingUserMessage = "   ", messages = null, messageCount = 7))
        assertNull(quiet.hasPendingUserMessage)
        assertEquals(7, quiet.messageCount)
    }

    @Test
    fun `projects and mutation responses decode tolerantly`() {
        val projects = json.decodeFromString(
            ProjectsResponse.serializer(),
            """{"projects": [{"project_id": 42, "name": "Alpha", "created_at": "170.5"}]}""",
        )
        val project = projects.projects!!.single()
        assertEquals("42", project.projectId)
        assertEquals("Alpha", project.name)
        assertEquals(170.5, project.createdAt!!, 0.0)
        assertEquals("42", project.id)

        // Malformed projects payload decodes to null instead of throwing.
        val malformed = json.decodeFromString(ProjectsResponse.serializer(), """{"projects": "oops"}""")
        assertNull(malformed.projects)

        val mutation = json.decodeFromString(
            ProjectMutationResponse.serializer(),
            """{"ok": 1, "project": {"name": "Beta"}, "error": null}""",
        )
        assertEquals(true, mutation.ok)
        assertEquals("Beta", mutation.project!!.name)
        assertNull(mutation.error)
    }

    @Test
    fun `compression summary extracts trailing token estimate`() {
        val arrow = SessionCompressionSummary(tokenLine = "120,000 \u2192 8,500 tokens")
        assertEquals(8500, arrow.compressedTokenEstimate)

        val ascii = SessionCompressionSummary(tokenLine = "120000 -> 9000")
        assertEquals(9000, ascii.compressedTokenEstimate)

        assertNull(SessionCompressionSummary(tokenLine = "no numbers here").compressedTokenEstimate)
        assertNull(SessionCompressionSummary(tokenLine = null).compressedTokenEstimate)
    }
}
