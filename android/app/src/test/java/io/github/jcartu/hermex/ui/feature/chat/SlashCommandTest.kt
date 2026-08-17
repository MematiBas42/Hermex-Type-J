package io.github.jcartu.hermex.ui.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SlashCommandTest {

    // MARK: - Parsing (port of the SlashCommandExecutor.parse contract)

    @Test
    fun `parse splits name and args`() {
        val parsed = SlashCommandExecutor.parse("/model gpt-x")

        assertNotNull(parsed)
        assertEquals("model", parsed!!.name)
        assertEquals("gpt-x", parsed.args)
        assertEquals("model", parsed.command?.name)
    }

    @Test
    fun `parse trims surrounding whitespace and arg whitespace`() {
        val parsed = SlashCommandExecutor.parse("  /title   My Session  ")

        assertNotNull(parsed)
        assertEquals("title", parsed!!.name)
        assertEquals("My Session", parsed.args)
    }

    @Test
    fun `bare slash parses to an empty command`() {
        val parsed = SlashCommandExecutor.parse("/")

        assertNotNull(parsed)
        assertNull(parsed!!.command)
        assertEquals("", parsed.name)
        assertEquals("", parsed.args)
    }

    @Test
    fun `non-slash text does not parse`() {
        assertNull(SlashCommandExecutor.parse("hello world"))
        assertNull(SlashCommandExecutor.parse(""))
    }

    @Test
    fun `unknown command parses with a null catalog entry`() {
        val parsed = SlashCommandExecutor.parse("/zzz do things")

        assertNotNull(parsed)
        assertNull(parsed!!.command)
        assertEquals("zzz", parsed.name)
        assertEquals("do things", parsed.args)
    }

    @Test
    fun `command without args parses with empty args`() {
        val parsed = SlashCommandExecutor.parse("/status")

        assertNotNull(parsed)
        assertEquals("status", parsed!!.name)
        assertEquals("", parsed.args)
        assertEquals(
            SlashCommandHandler.ServerSide(ServerSideAction.STATUS),
            parsed.command?.handler,
        )
    }

    // MARK: - Catalog

    @Test
    fun `catalog contains the full iOS command list in order`() {
        assertEquals(
            listOf(
                "help", "clear", "model", "workspace", "reasoning", "new", "stop", "title",
                "personality", "skills", "compress", "compact", "retry", "undo", "branch",
                "fork", "queue", "steer", "interrupt", "status", "goal", "btw", "background", "bg",
            ),
            SlashCommandCatalog.allCommands.map { it.name },
        )
    }

    @Test
    fun `aliases share their target's handler`() {
        assertEquals(
            SlashCommandHandler.ServerSide(ServerSideAction.COMPRESS),
            SlashCommandCatalog.command("compact")?.handler,
        )
        assertEquals(
            SlashCommandHandler.ServerSide(ServerSideAction.BRANCH),
            SlashCommandCatalog.command("fork")?.handler,
        )
        assertEquals(
            SlashCommandHandler.ServerSide(ServerSideAction.BACKGROUND),
            SlashCommandCatalog.command("bg")?.handler,
        )
    }

    @Test
    fun `command lookup is case-insensitive`() {
        assertEquals("model", SlashCommandCatalog.command("MODEL")?.name)
        assertNull(SlashCommandCatalog.command("nope"))
    }

    @Test
    fun `matching filters by name prefix or description substring`() {
        val byPrefix = SlashCommandCatalog.matching("mo")
        assertTrue(byPrefix.any { it.name == "model" })
        assertFalse(byPrefix.any { it.name == "workspace" })

        // "conversation" appears only in /clear's and /branch|/fork's descriptions.
        val byDescription = SlashCommandCatalog.matching("conversation")
        assertTrue(byDescription.any { it.name == "clear" })
        assertTrue(byDescription.any { it.name == "branch" })
    }

    @Test
    fun `blank query matches everything`() {
        assertEquals(SlashCommandCatalog.allCommands, SlashCommandCatalog.matching("   "))
    }

    @Test
    fun `sub-arg vocabularies match iOS`() {
        assertEquals(
            listOf("show", "hide", "none", "minimal", "low", "medium", "high", "xhigh"),
            SlashCommandCatalog.reasoningLevels,
        )
        assertEquals(listOf("status", "pause", "resume", "clear"), SlashCommandCatalog.goalActions)
        assertEquals(SlashCommandSubArgs.MODELS, SlashCommandCatalog.command("model")?.subArgs)
        assertEquals(SlashCommandSubArgs.GOAL_ACTIONS, SlashCommandCatalog.command("goal")?.subArgs)
    }

    @Test
    fun `noEcho flags mirror the Swift catalog`() {
        assertFalse(SlashCommandCatalog.command("help")!!.noEcho)
        assertFalse(SlashCommandCatalog.command("status")!!.noEcho)
        assertFalse(SlashCommandCatalog.command("title")!!.noEcho)
        assertTrue(SlashCommandCatalog.command("clear")!!.noEcho)
        assertTrue(SlashCommandCatalog.command("model")!!.noEcho)
        assertTrue(SlashCommandCatalog.command("goal")!!.noEcho)
    }

    // MARK: - Unsupported-command vocabulary

    @Test
    fun `known unsupported commands are blocked with friendly messages`() {
        assertTrue(SlashCommandExecutor.isKnownUnsupportedCommand("terminal"))
        assertTrue(SlashCommandExecutor.isKnownUnsupportedCommand("THEME"))
        assertTrue(SlashCommandExecutor.isKnownUnsupportedCommand("voice"))
        assertTrue(SlashCommandExecutor.isKnownUnsupportedCommand("yolo"))
        assertFalse(SlashCommandExecutor.isKnownUnsupportedCommand("zzz"))

        assertEquals(
            "Terminal is not available in the mobile app.",
            SlashCommandExecutor.unsupportedMessage("terminal"),
        )
        assertEquals(
            "Theme switching is not available from mobile slash commands.",
            SlashCommandExecutor.unsupportedMessage("theme"),
        )
        assertEquals(
            "This command is not available in the mobile app.",
            SlashCommandExecutor.unsupportedMessage("anything-else"),
        )
    }

    @Test
    fun `slash command equality is by name only`() {
        val catalogModel = SlashCommandCatalog.command("model")!!
        val bareModel = SlashCommand(name = "model", description = "different")

        assertEquals(catalogModel, bareModel)
        assertEquals(catalogModel.hashCode(), bareModel.hashCode())
    }
}
