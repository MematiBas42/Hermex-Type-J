package io.github.jcartu.hermex.core.navigation

import android.net.Uri
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class DeepLinkRepositoryTest {
    @get:Rule val temp = TemporaryFolder()
    private lateinit var scope: CoroutineScope
    private lateinit var file: File

    @Before fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        file = temp.newFile("links.preferences_pb")
    }
    @After fun tearDown() = scope.cancel()

    @Test fun `parser supports Type-J and legacy links`() {
        assertEquals(AppDeepLink.NewChat, DeepLinkParser.parse(Uri.parse("hermex-type-j://new-chat")))
        assertEquals(AppDeepLink.NewChat, DeepLinkParser.parse(Uri.parse("hermes-agent://chat")))
        assertEquals(AppDeepLink.Session("session 1"), DeepLinkParser.parse(Uri.parse("hermex-type-j://session/session%201")))
        assertEquals(AppDeepLink.Kanban("main", "card-7"), DeepLinkParser.parse(Uri.parse("hermex-type-j://kanban/main/card-7")))
        assertEquals(AppDeepLink.Git("s1"), DeepLinkParser.parse(Uri.parse("hermex-type-j://git/s1")))
    }

    @Test fun `parser rejects foreign malformed and path injection links`() {
        assertNull(DeepLinkParser.parse(Uri.parse("https://example.com/session/s1")))
        assertNull(DeepLinkParser.parse(Uri.parse("hermex-type-j://unknown/s1")))
        assertNull(DeepLinkParser.parse(Uri.parse("hermex-type-j://session/")))
        assertNull(DeepLinkParser.parse(Uri.parse("hermex-type-j://session/${"x".repeat(300)}")))
    }

    @Test fun `pending link survives repository recreation until acknowledgement`() = runBlocking {
        val first = repository()
        first.submit(Uri.parse("hermex-type-j://session/s1"))
        val raw = first.pending.value!!.first
        scope.cancel()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        val restored = repository()
        restored.load()
        assertEquals(AppDeepLink.Session("s1"), restored.pending.value?.second)
        restored.acknowledge(raw)
        assertNull(restored.pending.value)
    }

    private fun repository() = DeepLinkRepository(
        PreferenceDataStoreFactory.create(scope = scope, produceFile = { file }),
    )
}
