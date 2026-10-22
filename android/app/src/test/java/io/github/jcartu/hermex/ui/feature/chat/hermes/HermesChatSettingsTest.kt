package io.github.jcartu.hermex.ui.feature.chat.hermes

import io.github.jcartu.hermex.model.ModelCatalogOption
import io.github.jcartu.hermex.model.hermes.BotArtifactContext
import io.github.jcartu.hermex.model.hermes.BotConnection
import io.github.jcartu.hermex.model.hermes.BotJSON
import io.github.jcartu.hermex.model.hermes.HermesSessionPage
import io.github.jcartu.hermex.net.hermes.HermesCall
import java.util.ArrayDeque
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Behavioral ports of `HermesChatSettingsTests.swift` and `BotChatControlsTests.swift`. */
class HermesChatSettingsTest {
    @Test
    fun `capabilities clamp the effort ladder and snapshots never write config`() = runTest {
        val wire = SettingsWire(active = "opus")
        val settings = openSettings(wire)

        assertTrue(settings.showsEffort)
        assertFalse("none" in settings.effortLevels)
        assertTrue(settings.effortLevels.containsAll(listOf("minimal", "xhigh", "max", "ultra")))
        assertEquals(listOf("default", "work"), settings.profiles)
        assertEquals(3, settings.roster.size)

        settings.apply(
            BotJSON.parse(
                """{"reasoning_effort":"xhigh","reasoning_effort_wire":"high","fast":false,"model":"opus"}""",
            ),
            idle = false,
        )
        assertEquals("xhigh", settings.effort)
        assertEquals("high", settings.sentEffort)
        assertEquals(false, settings.fast)
        assertTrue(wire.configWrites.isEmpty())

        wire.active = "plain"
        settings.reload()
        assertFalse(settings.showsEffort)
        assertNull(settings.fast)
        assertTrue(wire.configWrites.isEmpty())
    }

    @Test
    fun `expensive model confirmation resends once and never changes Profile defaults`() = runTest {
        val wire = SettingsWire(active = "base")
        val settings = openSettings(wire)
        val opus = ModelCatalogOption(id = "opus", displayName = "opus", providerID = "provider")
        wire.configReplies.addLast(modelReply(confirm = true))

        assertFalse(settings.select(opus))
        assertEquals("Opus costs more.", settings.confirmation?.message)
        settings.cancelConfirmation()
        assertEquals(1, wire.configWrites.size)

        wire.configReplies.addLast(modelReply(confirm = true))
        assertFalse(settings.select(opus))
        wire.configReplies.addLast(modelReply(confirm = false))
        assertTrue(settings.confirm())

        val modelWrites = wire.configWrites.map { it.setting as HermesCall.SessionSetting.Model }
        assertEquals(listOf(false, false, true), modelWrites.map { it.confirmExpensive })
        assertTrue(modelWrites.all { it.value == "opus --provider provider --session" })
        assertEquals("opus", settings.selectedModel?.id)
        assertTrue(wire.configWrites.all { it.params()["scope"]?.text == "session" })
    }

    @Test
    fun `deferred model stays selected until the live catalog catches up`() = runTest {
        val wire = SettingsWire(active = "base")
        val settings = openSettings(wire)
        val opus = ModelCatalogOption(id = "opus", displayName = "opus", providerID = "provider")
        wire.configReplies.addLast(modelReply(confirm = false, deferred = true))

        assertTrue(settings.select(opus))
        assertEquals("opus", settings.selectedModel?.id)
        assertEquals("base", settings.catalog.active?.id)

        wire.active = "opus"
        settings.reload()
        assertEquals("opus", settings.catalog.active?.id)
        assertNull(settings.pendingModel)
    }

    @Test
    fun `personality waits for confirmation and is the only Profile wide setting`() = runTest {
        val wire = SettingsWire(active = "base")
        val settings = openSettings(wire)

        settings.askPersonality("pirate")
        assertEquals("pirate", settings.pendingPersonality)
        settings.cancelPersonality()
        assertTrue(wire.configWrites.isEmpty())

        settings.askPersonality("pirate")
        wire.configReplies.addLast(BotJSON.parse("""{"key":"personality","value":"pirate"}"""))
        settings.setPersonality("pirate")

        val call = wire.configWrites.single()
        assertTrue(call.setting is HermesCall.SessionSetting.Personality)
        assertNull(call.params()["scope"])
        assertNull(settings.pendingPersonality)
    }

    private suspend fun TestScope.openSettings(wire: SettingsWire): HermesChatSettings {
        val engine = HermesConversation(
            server = "https://hermes.example",
            connection = BotConnection(
                id = "connection",
                name = "Hermes",
                address = "http://127.0.0.1:9120".toHttpUrl(),
                username = "user",
                password = "secret",
            ),
            target = ConversationTarget.Session(profile = "work", key = "tip"),
            wire = wire,
            scope = this,
        )
        engine.activate()
        assertEquals(HermesConversation.ConnectionState.CONNECTED, engine.connectionState)
        val settings = HermesChatSettings(engine)
        settings.connect(runtime = "runtime", attempt = engine.generation)
        assertNotNull(settings.catalog.active)
        return settings
    }

    private fun modelReply(confirm: Boolean, deferred: Boolean = false): BotJSON = BotJSON.parse(
        """
        {
          "key":"model",
          "value":"opus",
          "scope":"session",
          "confirm_required":$confirm,
          "confirm_message":"Opus costs more.",
          "deferred":$deferred
        }
        """.trimIndent(),
    )
}

private class SettingsWire(
    var active: String,
) : BotTransport {
    override val replayEpoch: String = "epoch"
    override val serverVersion: String = "2.0"
    override val unavailableMethods: MutableSet<String> = mutableSetOf()
    override var onEvent: ((BotJSON) -> Unit)? = null
    override var onDisconnect: ((Throwable) -> Unit)? = null

    val calls = mutableListOf<HermesCall>()
    val configWrites: List<HermesCall.ConfigSet> get() = calls.filterIsInstance<HermesCall.ConfigSet>()
    val configReplies = ArrayDeque<BotJSON>()

    override suspend fun connect() = Unit
    override suspend fun close() = Unit

    override suspend fun call(call: HermesCall, validateDispatch: (() -> Unit)?): BotJSON {
        validateDispatch?.invoke()
        calls += call
        return when (call) {
            is HermesCall.SessionResume -> BotJSON.parse(
                """{"session_id":"runtime","session_key":"tip","running":false,"messages":[],"info":{"profile_name":"work"}}""",
            )
            is HermesCall.SessionEventsSince -> BotJSON.parse(
                """{"latest_seq":0,"epoch":"epoch","truncated":false,"events":[]}""",
            )
            is HermesCall.ModelOptions -> catalog(active)
            is HermesCall.ProfilesList -> BotJSON.parse(
                """{"profiles":[{"name":"default"},{"name":"work"},{"name":"work","future":true}]}""",
            )
            is HermesCall.ConfigSet -> configReply(call)
            else -> error("Unexpected test call: ${call.method}")
        }
    }

    private fun configReply(call: HermesCall.ConfigSet): BotJSON {
        val reply = if (configReplies.isEmpty()) defaultReply(call.setting) else configReplies.removeFirst()
        val model = call.setting as? HermesCall.SessionSetting.Model
        if (model != null && reply["confirm_required"].flag == false && reply["deferred"].flag != true) {
            active = model.value.substringBefore(' ')
        }
        return reply
    }

    private fun defaultReply(setting: HermesCall.SessionSetting): BotJSON = when (setting) {
        is HermesCall.SessionSetting.Model -> modelReplyForWire()
        is HermesCall.SessionSetting.Reasoning -> BotJSON.objectOf(
            "key" to BotJSON.string("reasoning"),
            "value" to BotJSON.string(setting.value),
        )
        is HermesCall.SessionSetting.Fast -> BotJSON.objectOf(
            "key" to BotJSON.string("fast"),
            "value" to BotJSON.string(if (setting.enabled) "fast" else "normal"),
        )
        is HermesCall.SessionSetting.Personality -> BotJSON.objectOf(
            "key" to BotJSON.string("personality"),
            "value" to BotJSON.string(setting.name),
        )
        is HermesCall.SessionSetting.Yolo -> BotJSON.objectOf(
            "key" to BotJSON.string("yolo"),
            "value" to BotJSON.string(if (setting.enabled) "on" else "off"),
        )
    }

    private fun modelReplyForWire(): BotJSON = BotJSON.parse(
        """{"key":"model","value":"opus","scope":"session","confirm_required":false,"deferred":false}""",
    )

    private fun catalog(active: String): BotJSON = BotJSON.parse(
        """
        {
          "model":"$active",
          "provider":"provider",
          "providers":[{
            "slug":"provider",
            "name":"Provider",
            "authenticated":true,
            "models":["base","opus","plain"],
            "capabilities":{
              "base":{"reasoning":true,"fast":false},
              "opus":{"reasoning":true,"fast":true,"can_disable_reasoning":false},
              "plain":{"reasoning":false,"fast":false}
            },
            "future":{"nested":true}
          }],
          "future_top_level":true
        }
        """.trimIndent(),
    )

    override suspend fun keepPlatformNote(profile: String) = Unit
    override suspend fun currentProfile(): String = "work"
    override suspend fun sessionMessages(key: String, profile: String, offset: Int?): List<BotJSON>? = emptyList()
    override suspend fun sessionRow(key: String, profile: String): BotJSON? = null
    override suspend fun sessionPage(profile: String, offset: Int, archived: Boolean): HermesSessionPage =
        HermesSessionPage(rows = emptyList())
    override suspend fun artifactData(path: String, context: BotArtifactContext, limit: Int?): ByteArray = byteArrayOf()
    override suspend fun uploadImage(data: ByteArray, filename: String, context: BotArtifactContext): String = filename
    override suspend fun speech(text: String, profile: String): ByteArray = byteArrayOf()
}
