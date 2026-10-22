package io.github.jcartu.hermex.ui.feature.bots

import io.github.jcartu.hermex.model.ModelCatalogOption
import io.github.jcartu.hermex.model.hermes.BotAvatarExpression
import io.github.jcartu.hermex.model.hermes.BotAvatarShape
import io.github.jcartu.hermex.model.hermes.BotConnection
import io.github.jcartu.hermex.model.hermes.BotFailure
import io.github.jcartu.hermex.model.hermes.BotJSON
import io.github.jcartu.hermex.model.hermes.BotProfile
import io.github.jcartu.hermex.net.hermes.BotProfileName
import io.github.jcartu.hermex.net.hermes.HermesCall
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class BotCreatorTest {
    @Test
    fun `ProfileName matches the Swift host-rule vectors`() {
        assertEquals("chief-of-staff", BotProfileName.slug("  Chief of Staff! "))
        assertEquals("emilie-s-bot-2", BotProfileName.slug("Émilie's Bot 2"))
        assertEquals("", BotProfileName.slug("---"))
        assertEquals(64, BotProfileName.slug("a".repeat(80)).length)
        assertTrue(BotProfileName.isValid("home-hunter_2"))
        assertFalse(BotProfileName.isValid("default"))
        assertFalse(BotProfileName.isValid("hermes"))
        assertFalse(BotProfileName.isValid("-lead"))
        assertFalse(BotProfileName.isValid("Chief"))
        assertFalse(BotProfileName.isValid(""))
    }

    @Test
    fun `create freezes values and writes profile look then adopt-before-mint chat`() = runTest {
        val fixture = CreatorFixture(backgroundScope)
        lateinit var creator: BotCreator
        fixture.wire.onCall = { method ->
            if (method == "profiles.create") {
                creator.setTitle("Changed after dispatch")
                creator.setSharesCredentials(false)
            }
        }
        creator = fixture.creator()
        creator.setTitle("Home Hunter")
        creator.setRole("Finds apartments")
        creator.setShape(BotAvatarShape.HEXAGON)
        creator.setColor("#f97316")
        creator.setExpression(BotAvatarExpression.HAPPY)
        creator.setModel(ModelCatalogOption("gpt-6", "gpt-6", "openai"))
        creator.setInstructions("Keep results sourced.")

        creator.create()

        assertEquals(
            listOf("profiles.create", "profiles.configure", "session.list", "session.create", "session.title"),
            fixture.wire.calls.map { it.first },
        )
        val profile = fixture.wire.calls[0].second
        assertEquals(BotJSON.string("home-hunter"), profile["name"])
        assertEquals(BotJSON.string("Finds apartments"), profile["description"])
        assertEquals(BotJSON.string("Keep results sourced."), profile["soul"])
        assertEquals(BotJSON.string("gpt-6"), profile["model"])
        assertEquals(BotJSON.string("openai"), profile["provider"])
        assertEquals(BotJSON.bool(true), profile["share_auth"])
        assertNull(profile["mirror_credentials"])
        val look = fixture.wire.calls[1].second.getValue("ui_meta")["hermes-bots"]
        assertEquals("Home Hunter", look["title"].text)
        assertEquals("hexagon", look["shape"].text)
        assertEquals("#f97316", look["color"].text)
        assertEquals("happy", look["expression"].text)
        assertEquals(BotJSON.number(0), fixture.wire.calls[1].second.getValue("ui_meta_expected_revisions")["hermes-bots"])
        assertEquals("home-hunter", fixture.wire.calls[3].second["profile"]?.text)
        assertEquals(true, fixture.wire.calls[3].second["hidden"]?.flag)
        assertEquals(listOf("home-hunter"), fixture.wire.platformNotes)
        assertEquals(BotCreator.Phase.CREATED, creator.current.phase)
        assertEquals("Home Hunter", creator.current.draft.title)
    }

    @Test
    fun `uncertain profile retry reconciles roster and never mints a second profile`() = runTest {
        val fixture = CreatorFixture(backgroundScope)
        var creates = 0
        fixture.wire.create = {
            creates += 1
            throw BotFailure.Transport
        }
        val creator = fixture.creator()
        creator.setTitle("Fresh")

        creator.create()
        assertSame(BotCreator.Outcome.Uncertain, creator.current.outcomes[BotCreator.Step.PROFILE])

        fixture.wire.roster = mutableListOf(profileRow("fresh"))
        fixture.wire.create = { error("a reconciled retry must not create again") }
        fixture.wire.calls.clear()
        creator.create()

        assertEquals(1, creates)
        assertEquals(
            listOf("profiles.list", "profiles.configure", "session.list", "session.create", "session.title"),
            fixture.wire.calls.map { it.first },
        )
        assertEquals(BotCreator.Phase.CREATED, creator.current.phase)
    }

    @Test
    fun `uncertain look and chat reconcile their exact frozen host state`() = runTest {
        val fixture = CreatorFixture(backgroundScope)
        val creator = fixture.creator()
        creator.setTitle("Fresh")
        var configureCalls = 0
        fixture.wire.configure = { params ->
            configureCalls += 1
            val look = params.getValue("ui_meta")["hermes-bots"]
            fixture.wire.roster = mutableListOf(
                BotJSON.objectOf(
                    "name" to BotJSON.string("fresh"),
                    "ui_meta" to BotJSON.objectOf("hermes-bots" to look),
                    "ui_meta_revisions" to BotJSON.objectOf("hermes-bots" to BotJSON.number(1)),
                ),
            )
            throw BotFailure.Transport
        }
        var firstChatRead = true
        fixture.wire.sessionList = {
            if (firstChatRead) {
                firstChatRead = false
                throw BotFailure.Transport
            }
            BotJSON.objectOf("sessions" to BotJSON.array(emptyList()))
        }

        creator.create()
        assertSame(BotCreator.Outcome.Uncertain, creator.current.outcomes[BotCreator.Step.LOOK])
        assertSame(BotCreator.Outcome.Uncertain, creator.current.outcomes[BotCreator.Step.CHAT])
        assertEquals(BotCreator.Phase.EDITING, creator.current.phase)

        fixture.wire.calls.clear()
        creator.create()

        assertEquals(1, configureCalls)
        assertEquals(
            listOf("profiles.list", "session.list", "session.create", "session.title"),
            fixture.wire.calls.map { it.first },
        )
        assertSame(BotCreator.Outcome.Done, creator.current.outcomes[BotCreator.Step.LOOK])
        assertSame(BotCreator.Outcome.Done, creator.current.outcomes[BotCreator.Step.CHAT])
    }

    @Test
    fun `duplicate clones instructions settings skills but sanitizes look and makes its own chat`() = runTest {
        val source = BotProfile.fromJsonOrNull(
            profileRow(
                "triage",
                mapOf(
                    "title" to BotJSON.string("Triage"),
                    "shape" to BotJSON.string("drop"),
                    "color" to BotJSON.string("#14b8a6"),
                    "sectionId" to BotJSON.string("desk"),
                    "pinned" to BotJSON.bool(true),
                ),
                description = "Sorts mail",
            ),
        )!!
        val fixture = CreatorFixture(backgroundScope, roster = mutableListOf(profileRow("triage")))
        val creator = fixture.creator(source)
        creator.setSharesCredentials(false)

        creator.create()

        val create = fixture.wire.calls[0].second
        assertEquals("triage", create["clone_from"]?.text)
        assertEquals(false, create.containsKey("soul"))
        assertEquals(false, create.containsKey("no_skills"))
        assertEquals(false, create.containsKey("share_auth"))
        assertEquals(false, create["mirror_credentials"]?.flag)
        val look = fixture.wire.calls[1].second.getValue("ui_meta")["hermes-bots"].fields!!
        assertEquals("drop", look["shape"]?.text)
        assertEquals("Triage copy", look["title"]?.text)
        assertFalse("sectionId" in look)
        assertFalse("pinned" in look)
        assertEquals("triage-copy", fixture.wire.calls[3].second["profile"]?.text)
    }
}

private class CreatorFixture(
    private val scope: kotlinx.coroutines.CoroutineScope,
    roster: MutableList<BotJSON> = mutableListOf(),
) {
    val connection = BotConnection(
        id = "connection-one",
        name = "Mac",
        address = "https://mac.example".toHttpUrl(),
        username = "u",
        password = "p",
    )
    val records = object : BotInbox.ConnectionRecords {
        var saved: BotConnection? = connection
        override fun load(server: String): BotConnection? = saved
        override fun save(connection: BotConnection, server: String) { saved = connection }
    }
    val wire = CreatorTransport(roster)

    fun creator(source: BotProfile? = null): BotCreator = BotCreator(
        server = "https://one.example",
        connection = connection,
        roster = wire.roster.mapNotNull(BotProfile::fromJsonOrNull),
        records = records,
        makeWire = { wire },
        scope = scope,
        source = source,
    )
}

private class CreatorTransport(var roster: MutableList<BotJSON>) : BotTransport {
    override var onEvent: ((BotJSON) -> Unit)? = null
    override var onDisconnect: ((Throwable) -> Unit)? = null
    val calls = mutableListOf<Pair<String, Map<String, BotJSON>>>()
    val platformNotes = mutableListOf<String>()
    var onCall: ((String) -> Unit)? = null
    var create: ((Map<String, BotJSON>) -> BotJSON)? = null
    var configure: ((Map<String, BotJSON>) -> BotJSON)? = null
    var sessionList: ((Map<String, BotJSON>) -> BotJSON)? = null
    var sessions: List<BotJSON> = emptyList()

    override suspend fun connect() = Unit
    override suspend fun close() = Unit
    override suspend fun keepPlatformNote(profile: String) { platformNotes += profile }

    override suspend fun call(call: HermesCall, validateDispatch: (() -> Unit)?): BotJSON {
        validateDispatch?.invoke()
        val params = call.params()
        calls += call.method to params
        onCall?.invoke(call.method)
        return when (call.method) {
            "profiles.list" -> BotJSON.objectOf("profiles" to BotJSON.array(roster))
            "profiles.create" -> create?.invoke(params) ?: BotJSON.objectOf(
                "ok" to BotJSON.bool(true),
                "name" to params.getValue("name"),
                "model_set" to BotJSON.bool(true),
            )
            "profiles.configure" -> configure?.invoke(params) ?: BotJSON.objectOf(
                "ok" to BotJSON.bool(true),
                "applied" to BotJSON.objectOf(
                    "ui_meta" to BotJSON.bool(true),
                    "ui_meta_revisions" to BotJSON.objectOf("hermes-bots" to BotJSON.number(1)),
                ),
            )
            "session.list" -> sessionList?.invoke(params)
                ?: BotJSON.objectOf("sessions" to BotJSON.array(sessions))
            "session.create" -> BotJSON.objectOf(
                "session_id" to BotJSON.string("runtime-1"),
                "stored_session_id" to BotJSON.string("stored-1"),
            )
            "session.title" -> BotJSON.objectOf("title" to BotJSON.string("Bot Chat"))
            "model.options" -> BotJSON.objectOf("providers" to BotJSON.array(emptyList()))
            else -> throw BotFailure.Unsupported
        }
    }
}

private fun profileRow(
    name: String,
    look: Map<String, BotJSON> = emptyMap(),
    description: String = "",
): BotJSON = BotJSON.objectOf(
    "name" to BotJSON.string(name),
    "description" to BotJSON.string(description),
    "has_avatar" to BotJSON.bool(false),
    "ui_meta" to BotJSON.objectOf("hermes-bots" to BotJSON.objectOf(look)),
    "ui_meta_revisions" to BotJSON.objectOf("hermes-bots" to BotJSON.number(3)),
)
