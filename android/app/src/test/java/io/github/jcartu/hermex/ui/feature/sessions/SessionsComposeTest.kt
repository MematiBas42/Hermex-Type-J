package io.github.jcartu.hermex.ui.feature.sessions

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.repeatOnLifecycle
import io.github.jcartu.hermex.model.SessionSummary
import io.github.jcartu.hermex.ui.adaptive.LocalPaneCount
import io.github.jcartu.hermex.ui.theme.HermexTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Robolectric compose coverage for the 1.6.0–1.8.0 row and #283 header at all
 * three pane counts (1 = phone/cover, 2 = half-fold/tablet, 3 = trifold), plus
 * the resume-gated idle ticker.
 *
 * No `ui-test` artifact is on the classpath, so assertions walk the semantics
 * tree of the activity's `AndroidComposeView` directly.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class SessionsComposeTest {

    // MARK: - Harness

    private fun composeAndDump(
        paneCount: Int,
        widthDp: Int,
        visibleTextOnly: Boolean = false,
        content: @Composable () -> Unit,
    ): List<String> {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.setContent {
            HermexTheme {
                CompositionLocalProvider(LocalPaneCount provides paneCount) {
                    Box(Modifier.requiredWidth(widthDp.dp)) { content() }
                }
            }
        }
        org.robolectric.shadows.ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
        org.robolectric.shadows.ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
        return activity.semanticsDump(visibleTextOnly)
    }

    private companion object {
        /**
         * Walks the composed semantics tree without the (absent) ui-test
         * artifact: finds the activity's `AndroidComposeView` by class name
         * (the class is compose-internal) and reads its public `semanticsOwner`
         * reflectively. [visibleTextOnly] drops content descriptions, so layout
         * assertions measure what is drawn, not what a screen reader announces
         * (the row's summary always carries the match excerpt and fork title).
         */
        fun android.app.Activity.semanticsDump(visibleTextOnly: Boolean): List<String> {
            val composeView = window.decorView.findComposeView() ?: return emptyList()
            val owner = composeView.javaClass
                .getMethod("getSemanticsOwner")
                .invoke(composeView) as SemanticsOwner
            val out = mutableListOf<String>()
            fun walk(node: SemanticsNode) {
                for (entry in node.config) {
                    when (entry.key) {
                        SemanticsProperties.Text ->
                            @Suppress("UNCHECKED_CAST")
                            (entry.value as? List<AnnotatedString>)?.forEach { out += it.text }
                        SemanticsProperties.ContentDescription ->
                            if (!visibleTextOnly) {
                                @Suppress("UNCHECKED_CAST")
                                (entry.value as? List<String>)?.let { out += it }
                            }
                        SemanticsProperties.EditableText ->
                            (entry.value as? AnnotatedString)?.let { out += it.text }
                    }
                }
                node.children.forEach(::walk)
            }
            walk(owner.rootSemanticsNode)
            return out
        }

        fun android.view.View.findComposeView(): android.view.View? = when {
            javaClass.name == "androidx.compose.ui.platform.AndroidComposeView" -> this
            this is android.view.ViewGroup ->
                (0 until childCount).firstNotNullOfOrNull { getChildAt(it).findComposeView() }
            else -> null
        }
    }

    private fun baseSession(now: Long) = SessionSummary(
        sessionId = "s1",
        title = "Alpha chat",
        messageCount = 3,
        workspace = "/home/josh/code/hermes",
        lastMessageAt = (now - 7_200).toDouble(),
    )

    // MARK: - Rows at pane counts 1/2/3

    @Test
    fun `row renders title metadata and time at every pane count`() {
        val now = System.currentTimeMillis() / 1000
        for (paneCount in 1..3) {
            val dump = composeAndDump(paneCount = paneCount, widthDp = 320) {
                SessionRow(
                    session = baseSession(now),
                    showsMessageCount = true,
                    showsWorkspace = true,
                    isViewingCachedData = false,
                    nowEpochSeconds = now,
                )
            }
            assertTrue("paneCount=$paneCount title in $dump", dump.contains("Alpha chat"))
            assertTrue("paneCount=$paneCount metadata in $dump", dump.any { it.contains("3 messages") })
            assertTrue("paneCount=$paneCount workspace in $dump", dump.any { it.contains("hermes") })
            assertTrue("paneCount=$paneCount time in $dump", dump.contains("2h ago"))
        }
    }

    @Test
    fun `row attention states replace the relative time`() {
        val now = System.currentTimeMillis() / 1000
        for (paneCount in 1..3) {
            val dump = composeAndDump(paneCount = paneCount, widthDp = 320) {
                SessionRow(
                    session = baseSession(now),
                    showsMessageCount = true,
                    showsWorkspace = true,
                    isViewingCachedData = false,
                    attentionState = SessionRowAttentionState.APPROVAL,
                    nowEpochSeconds = now,
                )
            }
            assertTrue("paneCount=$paneCount approval in $dump", dump.contains("Approval"))
            assertTrue("paneCount=$paneCount hides time in $dump", !dump.contains("2h ago"))
            assertTrue(
                "paneCount=$paneCount a11y in $dump",
                dump.any { it.contains("Waiting for approval") },
            )
        }
    }

    @Test
    fun `unread dot shows only without an attention state`() {
        val now = System.currentTimeMillis() / 1000
        val unreadDump = composeAndDump(paneCount = 2, widthDp = 320) {
            SessionRow(
                session = baseSession(now),
                showsMessageCount = true,
                showsWorkspace = true,
                isViewingCachedData = false,
                isUnread = true,
                nowEpochSeconds = now,
            )
        }
        assertTrue(unreadDump.any { it.contains("Unread") })

        val busyDump = composeAndDump(paneCount = 2, widthDp = 320) {
            SessionRow(
                session = baseSession(now),
                showsMessageCount = true,
                showsWorkspace = true,
                isViewingCachedData = false,
                isUnread = true,
                attentionState = SessionRowAttentionState.WORKING,
                nowEpochSeconds = now,
            )
        }
        // The Working label wins the slot; the dot (and its label) hide.
        assertTrue(busyDump.contains("Working"))
        assertTrue(!busyDump.any { it.contains("Unread") })
    }

    @Test
    fun `search excerpt and fork subtitle render at wide widths and hide when narrow`() {
        val now = System.currentTimeMillis() / 1000
        val parent = SessionSummary(sessionId = "p1", title = "Original investigation")
        val fork = baseSession(now).copy(
            sessionId = "s2",
            sessionSource = "fork",
            parentSessionId = "p1",
        )
        val excerpt = SessionSearchExcerpt.of("the budget for Q3 is tight", "budget")

        val wide = composeAndDump(paneCount = 3, widthDp = 400) {
            SessionRow(
                session = fork,
                showsMessageCount = true,
                showsWorkspace = true,
                isViewingCachedData = false,
                searchExcerpt = excerpt,
                forkOrigin = ForkOrigin.resolve(fork, parent),
                nowEpochSeconds = now,
            )
        }
        assertTrue(wide.any { it.contains("the budget for Q3 is tight") })
        assertTrue(wide.contains("Forked from Original investigation"))

        // Below ~220dp the excerpt, fork line, supplemental row and time hide
        // from the LAYOUT (the accessibility summary still announces them).
        val narrow = composeAndDump(paneCount = 1, widthDp = 160, visibleTextOnly = true) {
            SessionRow(
                session = fork,
                showsMessageCount = true,
                showsWorkspace = true,
                isViewingCachedData = false,
                searchExcerpt = excerpt,
                forkOrigin = ForkOrigin.resolve(fork, parent),
                nowEpochSeconds = now,
            )
        }
        assertTrue(narrow.contains("Alpha chat"))
        assertTrue(!narrow.any { it.contains("the budget for Q3 is tight") })
        assertTrue(!narrow.any { it.contains("Forked from") })
    }

    @Test
    fun `a fork without a listed parent falls back and does not crash`() {
        val now = System.currentTimeMillis() / 1000
        val orphan = baseSession(now).copy(sessionSource = "fork", parentSessionId = "missing")
        val dump = composeAndDump(paneCount = 2, widthDp = 340) {
            SessionRow(
                session = orphan,
                showsMessageCount = true,
                showsWorkspace = true,
                isViewingCachedData = false,
                forkOrigin = ForkOrigin.resolve(orphan, parent = null),
                nowEpochSeconds = now,
            )
        }
        assertTrue(dump.contains("Forked from another chat"))
    }

    // MARK: - Header at pane counts 1/2/3

    @Test
    fun `header shows the wordmark and avatar at every pane count`() {
        for (paneCount in 1..3) {
            val dump = composeAndDump(paneCount = paneCount, widthDp = 360) {
                SessionsHeader(
                    logoColor = Color(0xFFFFD700),
                    avatarInitials = "JC",
                    avatarColor = Color(0xFFFFD700),
                    avatarForeground = Color.Black,
                    isSearchExpanded = false,
                    searchText = "",
                    onSearchTextChange = {},
                    onOpenSearch = {},
                    onCloseSearch = {},
                    onOpenSettings = {},
                    onSwitchServer = {},
                )
            }
            assertTrue("paneCount=$paneCount wordmark in $dump", dump.contains("HERMEX"))
            assertTrue("paneCount=$paneCount initials in $dump", dump.contains("JC"))
            assertTrue("paneCount=$paneCount search in $dump", dump.contains("Search sessions"))
        }
    }

    @Test
    fun `expanded search grows a field and turns the avatar into close`() {
        val dump = composeAndDump(paneCount = 2, widthDp = 360) {
            SessionsHeader(
                logoColor = Color(0xFFFFD700),
                avatarInitials = "JC",
                avatarColor = Color(0xFFFFD700),
                avatarForeground = Color.Black,
                isSearchExpanded = true,
                searchText = "budget",
                onSearchTextChange = {},
                onOpenSearch = {},
                onCloseSearch = {},
                onOpenSettings = {},
                onSwitchServer = {},
            )
        }
        // The wordmark is pushed out; the field carries the query; Close and
        // Clear replace the avatar.
        assertTrue(!dump.contains("HERMEX"))
        assertTrue(dump.contains("budget"))
        assertTrue(dump.contains("Close search"))
        assertTrue(dump.contains("Clear search"))
        assertTrue(!dump.contains("JC"))
    }

    // MARK: - Idle ticker (1.8.0): ticks only while resumed

    @kotlinx.coroutines.ExperimentalCoroutinesApi
    @Test
    fun `idle ticker ticks only while the list is resumed`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            lateinit var registry: LifecycleRegistry
            val owner = object : LifecycleOwner {
                override val lifecycle: LifecycleRegistry
                    get() = registry
            }
            registry = LifecycleRegistry(owner)

            val ticks = mutableListOf<Long>()
            var fakeNow = 1_700_000_000L
            // Fixed at a minute boundary: every tick waits exactly 60 virtual seconds.
            val fakeNowMillis = 120_000L
            val job = launch {
                owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                    SessionListIdleTicker.run(
                        nowEpochSeconds = { fakeNow },
                        nowMillis = { fakeNowMillis },
                        emit = ticks::add,
                    )
                }
            }

            // Initialized (never resumed): no ticks.
            testScheduler.runCurrent()
            assertTrue(ticks.isEmpty())

            registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
            testScheduler.runCurrent()
            assertEquals(listOf(fakeNow), ticks)

            fakeNow += 60
            advanceTimeBy(60_001)
            assertEquals(2, ticks.size)

            // Paused: the tick loop is cancelled; advancing time adds nothing.
            registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
            testScheduler.runCurrent()
            fakeNow += 300
            advanceTimeBy(300_000)
            assertEquals(2, ticks.size)

            // Resumed again: the ticker restarts with a fresh immediate tick.
            registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
            testScheduler.runCurrent()
            assertEquals(3, ticks.size)
            assertEquals(fakeNow, ticks.last())

            job.cancel()
        } finally {
            Dispatchers.resetMain()
        }
    }
}
