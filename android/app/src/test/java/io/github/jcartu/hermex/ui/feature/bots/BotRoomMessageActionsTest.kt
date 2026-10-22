package io.github.jcartu.hermex.ui.feature.bots

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BotRoomMessageActionsTest {
    @Test
    fun `copy footer keeps original markdown source and offers no mutation`() {
        var copied: String? = null
        val source = "```kotlin\nval answer = 42\n```"
        val actions = botRoomMessageActions(source) { copied = it }
        assertEquals(listOf("Copy"), actions.map { it.title })
        actions.single().perform()
        assertEquals(source, copied)
        assertTrue(botRoomMessageActions("  ") { }.isEmpty())
    }
}
