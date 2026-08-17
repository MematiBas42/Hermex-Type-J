package io.github.jcartu.hermex.service

import io.github.jcartu.hermex.net.ChatStreamEvent
import io.github.jcartu.hermex.net.InterimAssistantStreamEvent
import io.github.jcartu.hermex.net.MeteringStreamEvent
import io.github.jcartu.hermex.net.TitleStreamEvent
import io.github.jcartu.hermex.net.ToolStreamEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundRunStateTest {
    @Test fun `token preview is bounded and status includes real metering`() {
        var state = BackgroundRunState("Run")
        state = state.reduce(ChatStreamEvent.Token("x".repeat(300)))
        state = state.reduce(ChatStreamEvent.Metering(MeteringStreamEvent(42.4, true, false, "s1")))
        assertEquals(240, state.preview.length)
        assertTrue(state.notificationText.contains("42 tok/s"))
        assertNull(state.terminal)
    }

    @Test fun `reasoning and tools expose status without leaking arguments`() {
        var state = BackgroundRunState("Run").reduce(ChatStreamEvent.Reasoning("secret chain of thought"))
        assertEquals("Thinking", state.status)
        state = state.reduce(ChatStreamEvent.ToolStarted(ToolStreamEvent(name = "read", preview = "secret.txt")))
        assertEquals("Using read", state.status)
        assertTrue(!state.notificationText.contains("secret.txt"))
    }

    @Test fun `title and interim assistant update visible state`() {
        var state = BackgroundRunState("Old").reduce(ChatStreamEvent.Title(TitleStreamEvent(title = "Type-J build")))
        state = state.reduce(ChatStreamEvent.InterimAssistant(InterimAssistantStreamEvent("Working", false)))
        assertEquals("Type-J build", state.title)
        assertEquals("Working", state.preview)
    }

    @Test fun `terminal events map to durable result states`() {
        assertEquals(BackgroundRunTerminal.COMPLETE, BackgroundRunState("Run").reduce(ChatStreamEvent.StreamEnd).terminal)
        assertEquals(BackgroundRunTerminal.CANCELLED, BackgroundRunState("Run").reduce(ChatStreamEvent.Cancelled).terminal)
        val failed = BackgroundRunState("Run").reduce(ChatStreamEvent.Error("Server failed"))
        assertEquals(BackgroundRunTerminal.FAILED, failed.terminal)
        assertEquals("Server failed", failed.status)
    }

    @Test fun `transport error reconnects without becoming terminal`() {
        val state = BackgroundRunState("Run").reduce(ChatStreamEvent.TransportError("socket"))
        assertEquals("Reconnecting", state.status)
        assertNull(state.terminal)
    }
}
