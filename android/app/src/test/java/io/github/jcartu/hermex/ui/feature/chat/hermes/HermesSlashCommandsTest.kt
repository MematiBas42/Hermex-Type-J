package io.github.jcartu.hermex.ui.feature.chat.hermes

import io.github.jcartu.hermex.model.AgentCommand
import io.github.jcartu.hermex.model.hermes.BotJSON
import io.github.jcartu.hermex.ui.feature.chat.AgentSlashCommandSuggestion
import io.github.jcartu.hermex.ui.feature.chat.HermesSlashCommandCatalog
import io.github.jcartu.hermex.ui.feature.chat.ComposerSendModeChoice
import io.github.jcartu.hermex.ui.feature.chat.composerSendModeChoices
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Behavioral ports of `HermesSlashCommandTests.swift` and `BotSlashCommandTests.swift`. */
class HermesSlashCommandsTest {
    @Test
    fun `catalog keeps canonical commands aliases and unshadowed skills`() {
        val catalog = HermesSlashCatalog(catalogReply())

        assertEquals(listOf("ctx"), catalog.commands.single { it.name == "context" }.aliases)
        assertEquals("context", catalog.command(named = "CTX"))
        assertTrue(catalog.skills.any { it.name == "demo-skill" })
        assertFalse(catalog.commands.any { it.name == "demo-skill" })
        assertNull(catalog.command(named = "demo-skill"))
        assertTrue(catalog.hostArgumentNames.containsAll(listOf("context", "ctx", "hello")))
        assertTrue(catalog.hostArgumentNames.none { it in setOf("model", "new", "reset") })
    }

    @Test
    fun `agent command ranking resolves aliases once at their best score`() {
        val commands = listOf(
            AgentCommand(name = "contextual", description = "Other context"),
            AgentCommand(name = "context", description = "Show context", aliases = listOf("ctx", "tokens")),
            AgentCommand(name = "triage", description = "ctx appears only in prose"),
        )

        val ranked = AgentSlashCommandSuggestion.matching(
            query = "ctx",
            commands = commands,
            matchingAliases = true,
        )

        assertEquals("context", ranked.first().name)
        assertEquals(1, ranked.count { it.name == "context" })
    }

    @Test
    fun `bot policy refuses leaving and rewriting commands including aliases`() {
        val policy = HermesChatPolicy.BOT_CHAT
        assertTrue(policy.refusal(typed = "reset", canonical = "new")!!.contains("keeps one chat"))
        assertTrue(policy.refusal(typed = "fork", canonical = "branch")!!.contains("can’t be forked"))
        assertNull(policy.refusal(typed = "compress", canonical = "compress"))
        assertFalse(policy.offersHistoryActions)
        assertFalse(policy.picksProfile)
        assertTrue(policy.offersMentions)

        val offered = HermesSlashCommandCatalog.commands.filter {
            policy.refusal(typed = it.name, canonical = it.name) == null
        }.mapTo(HashSet()) { it.name }
        assertTrue(offered.containsAll(listOf("btw", "background", "goal", "yolo", "compress", "model")))
        assertTrue(offered.none { it in setOf("new", "clear", "resume", "sessions", "branch", "fork", "title", "undo", "retry") })
    }

    @Test
    fun `unicode scalar replace from never splits a surrogate pair`() {
        val completion = HermesSlashCompletion(
            text = "/queue 🧪li",
            items = listOf(HermesSlashCompletion.Item(text = "list", display = "list", meta = "")),
            replaceFrom = 8,
        )

        assertTrue(completion.applies("/queue 🧪"))
        assertTrue(completion.applies("/queue 🧪li"))
        assertFalse(completion.applies("/queue 🧪list two"))
        assertEquals("/queue 🧪list", completion.applying(completion.items.single()))
    }

    @Test
    fun `slash replies preserve terminal output and reject unknown shapes`() {
        val reply = HermesSlashReply.from(
            BotJSON.parse("""{"type":"exec","warning":"careful","output":"a  b\n```\n"}"""),
            typed = "/hello",
        ) as HermesSlashReply.Notice

        assertTrue(reply.text.startsWith("careful\n\n````text\n"))
        assertTrue(reply.text.endsWith("\n````"))
        assertNull(HermesSlashReply.from(BotJSON.parse("""{"type":"future"}"""), typed = "/hello"))
    }

    @Test
    fun `send choices can remove Steer while keeping Queue and stop-and-send`() {
        assertEquals(
            listOf(ComposerSendModeChoice.QUEUE, ComposerSendModeChoice.STOP_AND_SEND),
            composerSendModeChoices(
                isRunActive = true,
                hasPayload = true,
                allowedChoices = setOf(
                    ComposerSendModeChoice.QUEUE,
                    ComposerSendModeChoice.STOP_AND_SEND,
                ),
            ),
        )
    }

    private fun catalogReply(): BotJSON = BotJSON.parse(
        """
        {

          "canon": {
            "/context": "/context",
            "/ctx": "/context",
            "/hello": "/hello",
            "/model": "/model",
            "/new": "/new",
            "/reset": "/new"
          },
          "commands": {
            "/context": {"argument_mode":"options"},
            "/model": {"argument_mode":"options"}
          },
          "pairs": [
            ["/context", "Show context"],
            ["/hello", "Say hello"],
            ["/model", "Switch model"],
            ["/new", "New chat"],
            ["/demo-skill", "Run the demo"]
          ],
          "skills": {
            "/demo-skill": {"origin":"bundled"},
            "/model": {"origin":"unsafe-shadow"}
          },
          "future": {"nested":true}
        }
        """.trimIndent(),
    )
}
