package io.github.jcartu.hermex.ui.feature.workspace.hermes

import io.github.jcartu.hermex.model.hermes.BotJSON
import io.github.jcartu.hermex.net.hermes.HermesFolderCompletion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HermesFolderCompletionTest {
    @Test
    fun `host listing limit with no folder asks for more typing`() {
        val items = (1..HermesFolderCompletion.HOST_LISTING_LIMIT).map { index ->
            BotJSON.objectOf(
                "display" to BotJSON.string("file-$index.txt"),
                "meta" to BotJSON.string("file"),
            )
        }
        val reply = BotJSON.objectOf("items" to BotJSON.array(items))

        val result = HermesFolderCompletion.suggestions(reply, "/srv/w")

        assertTrue(result.folders.isEmpty())
        assertTrue(result.needsMoreTyping)
        assertEquals(30, HermesFolderCompletion.HOST_LISTING_LIMIT)
    }

    @Test
    fun `folders keep the typed parent and hidden folders require a dot`() {
        val reply = BotJSON.objectOf(
            "items" to BotJSON.array(
                listOf(
                    BotJSON.objectOf("display" to BotJSON.string("app/"), "meta" to BotJSON.string("dir")),
                    BotJSON.objectOf("display" to BotJSON.string(".cache/"), "meta" to BotJSON.string("dir")),
                    BotJSON.objectOf("display" to BotJSON.string("readme.md"), "meta" to BotJSON.string("file")),
                ),
            ),
        )

        val visible = HermesFolderCompletion.suggestions(reply, "~/work/a")
        assertEquals(listOf("~/work/app/"), visible.folders)
        assertFalse(visible.needsMoreTyping)

        val hidden = HermesFolderCompletion.suggestions(reply, "~/work/.")
        assertEquals(listOf("~/work/app/", "~/work/.cache/"), hidden.folders)
    }
}
