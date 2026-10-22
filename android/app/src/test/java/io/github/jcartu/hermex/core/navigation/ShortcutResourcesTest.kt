package io.github.jcartu.hermex.core.navigation

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Launcher shortcut intent construction: every static shortcut in
 * `res/xml/shortcuts.xml` targets MainActivity with a deep link the grammar
 * parses, and the New Chat shortcuts land on the active server's kind (#337,
 * #338, #1180).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class ShortcutResourcesTest {

    private fun shortcutIntents(): Map<String, String> {
        // Unit tests run with the module dir as the working directory.
        val xml = File("src/main/res/xml/shortcuts.xml")
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml)
        val shortcuts = document.getElementsByTagName("shortcut")
        val result = linkedMapOf<String, String>()
        for (index in 0 until shortcuts.length) {
            val shortcut = shortcuts.item(index) as org.w3c.dom.Element
            val id = shortcut.getAttribute("android:shortcutId")
            val intents = shortcut.getElementsByTagName("intent")
            check(intents.length == 1) { "shortcut $id must carry exactly one intent" }
            val intent = intents.item(0) as org.w3c.dom.Element
            assertEquals("android.intent.action.VIEW", intent.getAttribute("android:action"))
            assertEquals("io.github.jcartu.hermex", intent.getAttribute("android:targetPackage"))
            assertEquals("io.github.jcartu.hermex.MainActivity", intent.getAttribute("android:targetClass"))
            result[id] = intent.getAttribute("android:data")
        }
        return result
    }

    @Test fun `every shortcut intent data parses through the grammar`() {
        val intents = shortcutIntents()
        assertEquals(setOf("new_chat", "new_chat_voice", "kanban", "git"), intents.keys)
        assertEquals(AppDeepLink.NewChat, DeepLinkParser.parse(Uri.parse(intents.getValue("new_chat"))))
        assertEquals(AppDeepLink.NewChatVoice, DeepLinkParser.parse(Uri.parse(intents.getValue("new_chat_voice"))))
        assertEquals(AppDeepLink.Kanban(null, null), DeepLinkParser.parse(Uri.parse(intents.getValue("kanban"))))
        assertEquals(AppDeepLink.Git(null), DeepLinkParser.parse(Uri.parse(intents.getValue("git"))))
    }

    @Test fun `new chat shortcut links route by the active server kind`() {
        val hermes = io.github.jcartu.hermex.model.ServerAccount(
            id = "http://100.64.1.5:9119", urlString = "http://100.64.1.5:9119", kind = io.github.jcartu.hermex.model.ServerKind.HERMES,
        )
        val webui = io.github.jcartu.hermex.model.ServerAccount(
            id = "https://a.example.com", urlString = "https://a.example.com", kind = io.github.jcartu.hermex.model.ServerKind.WEBUI,
        )
        val intents = shortcutIntents()
        val newChat = DeepLinkParser.parse(Uri.parse(intents.getValue("new_chat")))!!

        val hermesRoute = DeepLinkRouter.dispatch(
            newChat,
            LinkRoutingContext(activeServerId = hermes.id, signedIn = true, servers = listOf(hermes, webui)),
        )
        assertTrue(hermesRoute is DeepLinkRouter.Route.OpenHermesNewChat)

        val webuiRoute = DeepLinkRouter.dispatch(
            newChat,
            LinkRoutingContext(activeServerId = webui.id, signedIn = true, servers = listOf(webui, hermes)),
        )
        assertEquals(DeepLinkRouter.Route.OpenWebuiNewChat(null, autoStartsVoiceInput = false), webuiRoute)

        val voice = DeepLinkParser.parse(Uri.parse(intents.getValue("new_chat_voice")))!!
        val voiceRoute = DeepLinkRouter.dispatch(
            voice,
            LinkRoutingContext(activeServerId = webui.id, signedIn = true, servers = listOf(webui)),
        )
        assertEquals(DeepLinkRouter.Route.OpenWebuiNewChat(null, autoStartsVoiceInput = true), voiceRoute)
        assertNotNull(voiceRoute)
    }
}
