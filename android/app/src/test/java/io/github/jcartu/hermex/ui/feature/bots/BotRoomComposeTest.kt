package io.github.jcartu.hermex.ui.feature.bots

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import io.github.jcartu.hermex.HermexApplication
import io.github.jcartu.hermex.model.hermes.BotGroupRoom
import io.github.jcartu.hermex.model.hermes.BotJSON
import io.github.jcartu.hermex.model.hermes.BotProfile
import io.github.jcartu.hermex.model.hermes.BotRoomEvent
import io.github.jcartu.hermex.model.hermes.BotRoomStatus
import io.github.jcartu.hermex.model.hermes.BotRoomThread
import io.github.jcartu.hermex.ui.adaptive.LocalPaneCount
import io.github.jcartu.hermex.ui.theme.HermexTheme
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The room's selected thread composes without a client at cover, split and trifold counts. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = HermexApplication::class)
class BotRoomComposeTest {
    @Test
    fun `room thread content composes at pane counts one two and three`() {
        val room = requireNotNull(
            BotGroupRoom.fromJsonOrNull(
                RoomTestFixture.room(
                    latest = 2,
                    members = listOf(RoomTestFixture.member("chief"), RoomTestFixture.member("writer")),
                ),
            ),
        )
        val events = listOf(
            RoomTestFixture.event(1, text = "What should we ship?", threadID = "thread:one"),
            RoomTestFixture.event(2, kind = "message.member", text = "Ship the bounded room reader.", threadID = "thread:one"),
        ).mapNotNull(BotRoomEvent::fromJsonOrNull)
        val state = BotRoomReader.State(
            room = room,
            events = events,
            threads = BotRoomThread.group(events, hasEarlier = false),
            status = BotRoomStatus.EMPTY,
            link = BotRoomReader.Link.LIVE,
            drafts = mapOf("thread:one" to "A reply"),
        )
        val presentation = BotRoomPresentation(
            state = state,
            connectionName = "Mac",
            showsWelcome = false,
            showsComposer = true,
            showsStop = false,
            mayStop = false,
            showsRename = true,
            showsDisband = true,
            mayRename = true,
            mayDisband = true,
            maySend = { true },
            mayResend = { false },
            mayEditDraft = { true },
            mayAct = { false },
        )
        val roster = listOf(profile("chief"), profile("writer"))

        for (paneCount in 1..3) {
            val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
            activity.setContent {
                HermexTheme(darkTheme = true) {
                    CompositionLocalProvider(LocalPaneCount provides paneCount) {
                        BotRoomScreenContent(
                            presentation = presentation,
                            roster = roster,
                            avatars = emptyMap(),
                            selectedThreadID = "thread:one",
                            initialSequence = 2,
                            actions = BotRoomScreenActions(),
                            paneCount = paneCount,
                        )
                    }
                }
            }
            shadowOf(Looper.getMainLooper()).idle()
            assertNotNull(activity.window.decorView)
            activity.finish()
        }
    }

    private fun profile(id: String): BotProfile = requireNotNull(
        BotProfile.fromJsonOrNull(
            BotJSON.objectOf(
                "name" to BotJSON.string(id),
                "display_name" to BotJSON.string(id.replaceFirstChar(Char::uppercase)),
            ),
        ),
    )
}
