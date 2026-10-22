package io.github.jcartu.hermex.ui.feature.bots

import android.graphics.Bitmap
import io.github.jcartu.hermex.model.hermes.BotAvatarColors
import io.github.jcartu.hermex.model.hermes.BotAvatarExpression
import io.github.jcartu.hermex.model.hermes.BotAvatarShape
import io.github.jcartu.hermex.model.hermes.BotJSON
import io.github.jcartu.hermex.model.hermes.BotProfile
import io.github.jcartu.hermex.model.hermes.BotProfileAppearance
import io.github.jcartu.hermex.net.hermes.HermesCall
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

/** Avatar metadata/revision/ownership rules from BotAvatarStore and BotProfileAppearance.swift. */
@OptIn(ExperimentalCoroutinesApi::class)
class BotPresentationTest {
    @Test fun `appearance changes preserve Desktop-only metadata`() {
        val look = BotJSON.parse("""{"title":"Before","pinned":true,"sectionId":"team","future":{"preserve":true},"custom":true,"shape":"circle","imageKind":"photo"}""").fields!!
        val appearance = BotProfileAppearance.fromLook(look, "profile").copy(title = " After ", shape = "drop", imageKind = "shape", expression = "curious")
        val merged = appearance.merging(look)
        assertEquals("After", merged["title"]?.text)
        assertEquals("drop", merged["shape"]?.text)
        assertEquals(true, merged["pinned"]?.flag)
        assertEquals("team", merged["sectionId"]?.text)
        assertEquals(true, merged["future"]?.get("preserve")?.flag)
        val reset = appearance.copy(shape = null, color = null, expression = null, custom = false, imageKind = null).merging(look)
        assertFalse(reset.containsKey("shape"))
        assertFalse(reset.containsKey("imageKind"))
        assertTrue(reset.containsKey("future"))
        assertEquals(BotAvatarExpression.NEUTRAL, BotAvatarExpression.resolve("future-face"))
    }

    @Test fun `default face and adaptive color remain legible`() {
        val presentation = BotAvatarColors.presentation("default", BotProfileAppearance("Hermes"))
        assertEquals(BotAvatarShape.SQUIRCLE, presentation.shape)
        assertEquals("#8b5cf6", presentation.bodyHex)
        assertEquals(0xFFFFFFFF.toInt(), BotAvatarColors.body("#ffffff", true))
        assertEquals(0xFF000000.toInt(), BotAvatarColors.body("#ffffff", false))
        assertNotEquals(BotAvatarColors.body("#ffffff", true), BotAvatarColors.eyes("#ffffff", true))
        assertNull(BotAvatarColors.argb("#invalid"))
        assertEquals(BotAvatarColors.presentation("worker", BotProfileAppearance("One")), BotAvatarColors.presentation("worker", BotProfileAppearance("Renamed")))
    }

    private fun pngHeader(): ByteArray = ByteArray(24).also {
        byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A).copyInto(it)
        "IHDR".toByteArray().copyInto(it, 12)
        it[19] = 160.toByte(); it[23] = 160.toByte()
    }
    private fun reply(bytes: ByteArray) = BotJSON.objectOf("found" to BotJSON.bool(true), "data" to BotJSON.string("data:image/png;base64," + Base64.getEncoder().encodeToString(bytes)))
    private fun profile(revision: Int?) = BotProfile.fromJsonOrNull(BotJSON.parse("""{"name":"helper","has_avatar":true,"ui_meta_revisions":{"hermes-bots":${revision ?: "null"}}}"""))!!

    @Test fun `Desktop face copy stays drawn but explicit photo wins`() {
        val image = mockk<Bitmap>()
        val decoder = BotAvatarDecoder { _, _ -> image }
        assertEquals(BotAvatarStore.Asset.FaceCopy, BotAvatarStore.asset(reply(pngHeader()), false, decoder))
        assertSame(image, BotAvatarStore.asset(reply(pngHeader()), true, decoder)?.image)
        assertNull(BotAvatarStore.asset(reply(pngHeader()), false, BotAvatarDecoder { _, _ -> null }))
        assertNull(BotAvatarStore.asset(BotJSON.parse("""{"found":false}"""), false, decoder))
        assertNull(BotAvatarStore.dataURLBytes("https://foreign.example/avatar.png"))
        assertNull(BotAvatarStore.dataURLBytes("data:image/png;base64,NOT BASE64"))
    }

    @Test fun `revision caches readable face copies and unknown revision refetches`() = runTest {
        val cache = BotAvatarStore(BotAvatarDecoder { _, _ -> mockk<Bitmap>() })
        var reads = 0
        val wire = object : BotTransport {
            override var onEvent: ((BotJSON) -> Unit)? = null
            override var onDisconnect: ((Throwable) -> Unit)? = null
            override suspend fun connect() = Unit
            override suspend fun close() = Unit
            override suspend fun call(call: HermesCall, validateDispatch: (() -> Unit)?): BotJSON { reads++; return reply(pngHeader()) }
        }
        cache.refresh(listOf(profile(1)), "one", wire, onUpdate = {})
        cache.refresh(listOf(profile(1)), "one", wire, onUpdate = {})
        assertEquals(1, reads)
        assertTrue(cache.images("one").isEmpty())
        cache.refresh(listOf(profile(null)), "one", wire, onUpdate = {})
        cache.refresh(listOf(profile(null)), "one", wire, onUpdate = {})
        assertEquals(3, reads)
    }

    @Test fun `removed connection cannot repopulate avatars from late fetch`() = runTest {
        val image = mockk<Bitmap>()
        val cache = BotAvatarStore(BotAvatarDecoder { _, _ -> image })
        val replyReady = CompletableDeferred<BotJSON>()
        val wire = object : BotTransport {
            override var onEvent: ((BotJSON) -> Unit)? = null
            override var onDisconnect: ((Throwable) -> Unit)? = null
            override suspend fun connect() = Unit
            override suspend fun close() = Unit
            override suspend fun call(call: HermesCall, validateDispatch: (() -> Unit)?): BotJSON = replyReady.await()
        }
        val job = launch { cache.refresh(listOf(profile(1)), "old", wire, onUpdate = {}) }
        runCurrent()
        cache.removeAll("old")
        replyReady.complete(reply(byteArrayOf(1)))
        job.join()
        assertTrue(cache.images("old").isEmpty())
    }

    @Test fun `local organization is tolerant and connection scoped`() {
        val defaults = BotLocalDefaults.InMemory()
        val marks = BotUnreadStore(defaults)
        val sections = BotSectionOrderStore(defaults)
        val rooms = BotRoomOrganizeStore(defaults)
        marks.save(mapOf("same-profile" to 4.0), "one")
        assertTrue(marks.load("two").isEmpty())
        sections.save(listOf("b", "a"), "server", "one")
        assertTrue(sections.load("other", "one").isEmpty())
        assertEquals(listOf("b", "a"), sections.load("server", "one"))
        rooms.save(BotRoomOrganizeStore.Flags(setOf("r"), setOf("h")), "one")
        assertTrue(rooms.load("two").pinned.isEmpty())
        defaults.put("bot-inbox-rooms.one", "{\"future\":true}")
        assertEquals(BotRoomOrganizeStore.Flags(), rooms.load("one"))
        defaults.put("bot-inbox-seen.one", "broken")
        assertTrue(marks.load("one").isEmpty())
    }
}
