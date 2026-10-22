package io.github.jcartu.hermex.core.share

import io.github.jcartu.hermex.model.ServerAccount
import io.github.jcartu.hermex.model.ServerKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Share-target routing by the active server's kind (#1181) and the SharedImportGate port. */
class ShareRoutingTest {

    private fun account(id: String, kind: ServerKind): ServerAccount =
        ServerAccount(id = id, urlString = id, kind = kind)

    @Test fun `share opens a Hermes new chat when the active server is Hermes`() {
        val hermes = account("http://100.64.1.5:9119", ServerKind.HERMES)
        assertEquals(ShareRouting.Route.HermesNewChat(hermes.id), ShareRouting.route(hermes))
    }

    @Test fun `share opens the webui new chat composer when the active server is webui`() {
        val webui = account("https://a.example.com", ServerKind.WEBUI)
        assertEquals(ShareRouting.Route.WebuiNewChat, ShareRouting.route(webui))
    }

    @Test fun `gate opens the first share anywhere and later ones only on a Hermes home`() {
        val webui = account("https://a.example.com", ServerKind.WEBUI)
        val hermes = account("http://100.64.1.5:9119", ServerKind.HERMES)
        val servers = listOf(webui, hermes)
        // Nothing routed yet: the first share opens regardless of kind.
        assertTrue(SharedImportGate.opensNext(afterRouting = false, activeServerId = webui.id, servers = servers))
        assertTrue(SharedImportGate.opensNext(afterRouting = false, activeServerId = hermes.id, servers = servers))
        // After one routed: a webui list offers later shares in its banner; a Hermes home opens each as it arrives.
        assertFalse(SharedImportGate.opensNext(afterRouting = true, activeServerId = webui.id, servers = servers))
        assertTrue(SharedImportGate.opensNext(afterRouting = true, activeServerId = hermes.id, servers = servers))
        // An active id that names no configured server is not a Hermes home.
        assertFalse(SharedImportGate.opensNext(afterRouting = true, activeServerId = null, servers = servers))
    }
}
