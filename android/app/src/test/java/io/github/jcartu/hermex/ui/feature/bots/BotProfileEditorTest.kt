package io.github.jcartu.hermex.ui.feature.bots

import android.graphics.Bitmap
import io.github.jcartu.hermex.model.ModelCatalogOption
import io.github.jcartu.hermex.model.hermes.BotConnection
import io.github.jcartu.hermex.model.hermes.BotFailure
import io.github.jcartu.hermex.model.hermes.BotJSON
import io.github.jcartu.hermex.model.hermes.BotProfile
import io.github.jcartu.hermex.model.hermes.BotProfileDetails
import io.github.jcartu.hermex.net.hermes.HermesCall
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class BotProfileEditorTest {
    @Test
    fun `describe tolerates missing future and duplicate capability rows`() {
        val details = BotProfileDetails.from(
            BotJSON.objectOf(
                "name" to BotJSON.string("same"),
                "skills" to BotJSON.array(
                    listOf(
                        BotJSON.objectOf("name" to BotJSON.string("research"), "enabled" to BotJSON.bool(false)),
                        BotJSON.objectOf("name" to BotJSON.string("research"), "enabled" to BotJSON.bool(true)),
                        BotJSON.objectOf("future" to BotJSON.bool(true)),
                    ),
                ),
                "future" to BotJSON.objectOf("new" to BotJSON.bool(true)),
            ),
            expectedName = "same",
        )

        assertEquals("same", details.name)
        assertEquals("", details.description)
        assertEquals("", details.instructions)
        assertNull(details.model)
        assertEquals(listOf("research"), details.skills.map { it.id })
        assertFalse(details.skills.single().enabled)
        assertTrue(details.toolsets.isEmpty())
        assertTrue(details.mcpServers.isEmpty())
    }

    @Test
    fun `partial apply advances only successful section baselines`() = runTest {
        val fixture = EditorFixture(backgroundScope)
        val editor = fixture.editor()
        editor.load()
        editor.setDescription("New role")
        editor.setInstructions("New instructions")
        editor.setEnabled(false, BotProfileEditor.Field.SKILLS, "research")
        fixture.wire.configure = {
            BotJSON.objectOf(
                "ok" to BotJSON.bool(false),
                "applied" to BotJSON.objectOf(
                    "description" to BotJSON.bool(false),
                    "soul" to BotJSON.bool(true),
                    "skills" to BotJSON.bool(true),
                ),
            )
        }

        editor.save()

        assertEquals(setOf(BotProfileEditor.Field.DESCRIPTION), editor.current.dirtyFields)
        assertEquals(BotProfileEditor.Outcome.Failed("Failed"), editor.current.outcomes[BotProfileEditor.Field.DESCRIPTION])
        assertSame(BotProfileEditor.Outcome.Saved, editor.current.outcomes[BotProfileEditor.Field.INSTRUCTIONS])
        assertSame(BotProfileEditor.Outcome.Saved, editor.current.outcomes[BotProfileEditor.Field.SKILLS])
        val params = fixture.wire.calls.last { it.first == "profiles.configure" }.second
        assertEquals("New role", params["description"]?.text)
        assertEquals("New instructions", params["soul"]?.text)
        assertEquals(listOf("research"), params["disabled_skills"]?.list?.mapNotNull { it.text })
    }

    @Test
    fun `guarded model confirmation resends only model with confirmation`() = runTest {
        val fixture = EditorFixture(backgroundScope)
        val editor = fixture.editor()
        editor.load()
        editor.setModel(ModelCatalogOption("guarded", "guarded", "provider"))
        var count = 0
        fixture.wire.configure = { params ->
            count += 1
            if (count == 1) {
                BotJSON.objectOf(
                    "ok" to BotJSON.bool(true),
                    "applied" to BotJSON.objectOf(emptyMap()),
                    "confirm_required" to BotJSON.bool(true),
                    "confirm_message" to BotJSON.string("This model may cost more."),
                )
            } else {
                assertEquals(setOf("name", "model", "provider", "confirm_expensive_model"), params.keys)
                assertEquals(true, params["confirm_expensive_model"]?.flag)
                BotJSON.objectOf(
                    "ok" to BotJSON.bool(true),
                    "applied" to BotJSON.objectOf("model" to BotJSON.bool(true)),
                )
            }
        }

        editor.save()
        assertEquals("This model may cost more.", editor.current.confirmation?.message)
        assertSame(BotProfileEditor.Outcome.ConfirmationRequired, editor.current.outcomes[BotProfileEditor.Field.MODEL])
        editor.confirmModelChange()

        assertFalse(BotProfileEditor.Field.MODEL in editor.current.dirtyFields)
        assertSame(BotProfileEditor.Outcome.Saved, editor.current.outcomes[BotProfileEditor.Field.MODEL])
        assertEquals(2, count)
    }

    @Test
    fun `appearance CAS conflict preserves local edits and blocks avatar write`() = runTest {
        val profile = editorProfile(
            look = mapOf(
                "title" to BotJSON.string("Researcher"),
                "sectionId" to BotJSON.string("desktop-section"),
                "groups" to BotJSON.array(listOf(BotJSON.string("ops"))),
            ),
            revision = 4,
        )
        val fixture = EditorFixture(backgroundScope, profile = profile)
        val editor = fixture.editor()
        editor.load()
        editor.setTitle("Phone title")
        editor.removeAvatar()
        fixture.wire.configure = { params ->
            val sent = params.getValue("ui_meta")["hermes-bots"]
            assertEquals("desktop-section", sent["sectionId"].text)
            assertEquals(listOf("ops"), sent["groups"].list?.mapNotNull { it.text })
            assertEquals(4, params.getValue("ui_meta_expected_revisions")["hermes-bots"].integer)
            BotJSON.objectOf(
                "ok" to BotJSON.bool(false),
                "applied" to BotJSON.objectOf(
                    "ui_meta" to BotJSON.bool(false),
                    "ui_meta_conflicts" to BotJSON.objectOf(
                        "hermes-bots" to BotJSON.objectOf(
                            "expected" to BotJSON.number(4),
                            "actual" to BotJSON.number(5),
                        ),
                    ),
                ),
            )
        }

        editor.save()

        assertSame(BotProfileEditor.Outcome.Conflict, editor.current.outcomes[BotProfileEditor.Field.APPEARANCE])
        assertEquals(BotProfileEditor.Outcome.Failed("Needs Attention"), editor.current.outcomes[BotProfileEditor.Field.AVATAR])
        assertTrue(BotProfileEditor.Field.APPEARANCE in editor.current.dirtyFields)
        assertTrue(BotProfileEditor.Field.AVATAR in editor.current.dirtyFields)
        assertFalse(fixture.wire.calls.any { it.first == "profiles.set_asset" })
        assertEquals("Phone title", editor.current.draft.appearance.title)
    }

    @Test
    fun `avatar asset is written only after metadata acknowledgement`() = runTest {
        val fixture = EditorFixture(backgroundScope)
        val editor = fixture.editor()
        editor.load()
        editor.removeAvatar()
        fixture.wire.configure = {
            BotJSON.objectOf(
                "ok" to BotJSON.bool(true),
                "applied" to BotJSON.objectOf(
                    "ui_meta" to BotJSON.bool(true),
                    "ui_meta_revisions" to BotJSON.objectOf("hermes-bots" to BotJSON.number(2)),
                ),
            )
        }

        editor.save()

        assertEquals(listOf("profiles.configure", "profiles.set_asset"), fixture.wire.calls.takeLast(2).map { it.first })
        val asset = fixture.wire.calls.last().second
        assertEquals("avatar", asset["asset"]?.text)
        assertEquals(true, asset["clear"]?.flag)
        assertFalse(BotProfileEditor.Field.AVATAR in editor.current.dirtyFields)
    }

    @Test
    fun `background during save preserves draft marks uncertainty and never retries`() = runTest {
        val fixture = EditorFixture(backgroundScope)
        val editor = fixture.editor()
        editor.load()
        editor.setInstructions("Changed on the phone")
        fixture.wire.configure = {
            editor.suspendForBackground()
            BotJSON.objectOf(
                "ok" to BotJSON.bool(true),
                "applied" to BotJSON.objectOf("soul" to BotJSON.bool(true)),
            )
        }

        editor.save()

        assertEquals("Changed on the phone", editor.current.draft.instructions)
        assertEquals(
            BotProfileEditor.Outcome.Failed("Outcome Uncertain"),
            editor.current.outcomes[BotProfileEditor.Field.INSTRUCTIONS],
        )
        assertTrue(BotProfileEditor.Field.INSTRUCTIONS in editor.current.dirtyFields)
        assertEquals(1, fixture.wire.calls.count { it.first == "profiles.configure" })
        assertFalse(editor.current.isSaving)
    }

    @Test
    fun `camera originals over two megabytes normalize before the output cap`() = runTest {
        val bitmap = mockk<Bitmap>()
        var decoderCalls = 0
        val decoder = BotProfilePhotoDecoder {
            decoderCalls += 1
            BotProfilePhoto(bitmap, "image/png", byteArrayOf(1, 2, 3))
        }
        val fixture = EditorFixture(backgroundScope, photoDecoder = decoder)
        val editor = fixture.editor()
        editor.load()

        val cameraOriginal = ByteArray(BotProfileEditor.MAX_PHOTO_ASSET_BYTES + 1)
        editor.selectAvatar(cameraOriginal)
        assertEquals(1, decoderCalls)
        assertSame(bitmap, editor.current.avatar)
        assertEquals("photo", editor.current.draft.appearance.imageKind)
        assertTrue(BotProfileEditor.Field.AVATAR in editor.current.dirtyFields)
        assertTrue(BotProfileEditor.Field.APPEARANCE in editor.current.dirtyFields)
    }

    @Test
    fun `decoder output over two megabytes is rejected`() = runTest {
        val fixture = EditorFixture(
            backgroundScope,
            photoDecoder = BotProfilePhotoDecoder {
                BotProfilePhoto(mockk(), "image/jpeg", ByteArray(BotProfileEditor.MAX_PHOTO_ASSET_BYTES + 1))
            },
        )
        val editor = fixture.editor()
        editor.load()

        assertTrue(runCatching { editor.selectAvatar(byteArrayOf(1)) }.exceptionOrNull() is BotProfileEditorImageException)
        assertFalse(editor.current.avatarChanged)
    }
    @Test
    fun `conflict reload reconnects and replaces only appearance`() = runTest {
        val initial = editorProfile(
            look = mapOf(
                "title" to BotJSON.string("Researcher"),
                "sectionId" to BotJSON.string("one"),
            ),
            revision = 4,
        )
        val fixture = EditorFixture(backgroundScope, profile = initial)
        val editor = fixture.editor()
        editor.load()
        editor.setTitle("Phone title")
        editor.setInstructions("Phone instructions")
        fixture.wire.configure = {
            BotJSON.objectOf(
                "ok" to BotJSON.bool(false),
                "applied" to BotJSON.objectOf(
                    "ui_meta" to BotJSON.bool(false),
                    "ui_meta_conflicts" to BotJSON.objectOf(
                        "hermes-bots" to BotJSON.objectOf(
                            "expected" to BotJSON.number(4),
                            "actual" to BotJSON.number(5),
                        ),
                    ),
                    "soul" to BotJSON.bool(false),
                ),
            )
        }
        editor.save()
        fixture.wire.roster = listOf(
            BotJSON.objectOf(
                "name" to BotJSON.string("same"),
                "display_name" to BotJSON.string("Same"),
                "has_avatar" to BotJSON.bool(false),
                "ui_meta" to BotJSON.objectOf(
                    "hermes-bots" to BotJSON.objectOf(
                        "title" to BotJSON.string("Desktop title"),
                        "sectionId" to BotJSON.string("two"),
                    ),
                ),
                "ui_meta_revisions" to BotJSON.objectOf("hermes-bots" to BotJSON.number(5)),
            ),
        )
        editor.close()

        editor.reloadAppearance()

        assertEquals("Desktop title", editor.current.draft.appearance.title)
        assertEquals("Phone instructions", editor.current.draft.instructions)
        assertEquals(setOf(BotProfileEditor.Field.INSTRUCTIONS), editor.current.dirtyFields)
        assertNull(editor.current.outcomes[BotProfileEditor.Field.APPEARANCE])
        assertTrue(editor.current.holdsConnection)
    }

}

private class EditorFixture(
    private val scope: CoroutineScope,
    val profile: BotProfile = editorProfile(),
    private val photoDecoder: BotProfilePhotoDecoder = BotProfilePhotoDecoder { null },
) {
    val connection = BotConnection(
        id = "connection-one",
        name = "Mac",
        address = "https://mac.example".toHttpUrl(),
        username = "u",
        password = "p",
    )
    private val records = object : BotInbox.ConnectionRecords {
        var saved: BotConnection? = connection
        override fun load(server: String): BotConnection? = saved
        override fun save(connection: BotConnection, server: String) { saved = connection }
    }
    val wire = EditorTransport(details())

    fun editor(): BotProfileEditor = BotProfileEditor(
        server = "https://one.example",
        connection = connection,
        profile = profile,
        avatar = null,
        records = records,
        avatarStore = BotAvatarStore(),
        makeWire = { wire },
        scope = scope,
        photoDecoder = photoDecoder,
    )
}

private class EditorTransport(var details: BotJSON) : BotTransport {
    override var onEvent: ((BotJSON) -> Unit)? = null
    override var onDisconnect: ((Throwable) -> Unit)? = null
    val calls = mutableListOf<Pair<String, Map<String, BotJSON>>>()
    var configure: ((Map<String, BotJSON>) -> BotJSON)? = null
    var roster: List<BotJSON> = emptyList()

    override suspend fun connect() = Unit
    override suspend fun close() = Unit

    override suspend fun call(call: HermesCall, validateDispatch: (() -> Unit)?): BotJSON {
        validateDispatch?.invoke()
        val params = call.params()
        calls += call.method to params
        return when (call.method) {
            "profiles.describe" -> details
            "profiles.list" -> BotJSON.objectOf("profiles" to BotJSON.array(roster))
            "model.options" -> BotJSON.objectOf(
                "model" to BotJSON.string("model-a"),
                "provider" to BotJSON.string("provider"),
                "providers" to BotJSON.array(
                    listOf(
                        BotJSON.objectOf(
                            "slug" to BotJSON.string("provider"),
                            "name" to BotJSON.string("Provider"),
                            "models" to BotJSON.array(listOf(BotJSON.string("model-a"), BotJSON.string("guarded"))),
                        ),
                    ),
                ),
            )
            "profiles.configure" -> configure?.invoke(params) ?: throw BotFailure.Unsupported
            "profiles.set_asset" -> BotJSON.objectOf(
                "ok" to BotJSON.bool(true),
                "asset" to BotJSON.string("avatar"),
                "size" to BotJSON.number(0),
            )
            "profiles.get_asset" -> BotJSON.objectOf("found" to BotJSON.bool(false))
            else -> throw BotFailure.Unsupported
        }
    }
}

private fun editorProfile(
    look: Map<String, BotJSON> = emptyMap(),
    revision: Int? = null,
): BotProfile {
    val fields = linkedMapOf<String, BotJSON>(
        "name" to BotJSON.string("same"),
        "display_name" to BotJSON.string("Same"),
        "has_avatar" to BotJSON.bool(false),
        "ui_meta" to BotJSON.objectOf("hermes-bots" to BotJSON.objectOf(look)),
    )
    if (revision != null) {
        fields["ui_meta_revisions"] = BotJSON.objectOf("hermes-bots" to BotJSON.number(revision))
    }
    return BotProfile.fromJsonOrNull(BotJSON.objectOf(fields))!!
}

private fun details(): BotJSON = BotJSON.objectOf(
    "name" to BotJSON.string("same"),
    "description" to BotJSON.string("Find sources"),
    "soul" to BotJSON.string("Be careful"),
    "model" to BotJSON.objectOf(
        "provider" to BotJSON.string("provider"),
        "default" to BotJSON.string("model-a"),
    ),
    "skills" to BotJSON.array(
        listOf(BotJSON.objectOf("name" to BotJSON.string("research"), "enabled" to BotJSON.bool(true))),
    ),
    "toolsets" to BotJSON.array(
        listOf(
            BotJSON.objectOf(
                "name" to BotJSON.string("web"),
                "label" to BotJSON.string("Web"),
                "description" to BotJSON.string("Search the web"),
                "tool_count" to BotJSON.number(4),
                "enabled" to BotJSON.bool(true),
            ),
        ),
    ),
    "toolsets_pinned" to BotJSON.bool(false),
    "mcp_servers" to BotJSON.array(
        listOf(
            BotJSON.objectOf(
                "name" to BotJSON.string("linear"),
                "transport" to BotJSON.string("http"),
                "enabled" to BotJSON.bool(true),
            ),
        ),
    ),
)
