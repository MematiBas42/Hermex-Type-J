package io.github.jcartu.hermex.core.share

import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class SharedImportRepositoryTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val dispatcher = StandardTestDispatcher()
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val root get() = File(context.filesDir, "shared-import")

    @Before fun setUp() { root.deleteRecursively() }
    @After fun tearDown() { root.deleteRecursively() }

    @Test fun `text share persists across repository recreation and acknowledges atomically`() = runTest(dispatcher) {
        val first = SharedImportRepository(context, json, dispatcher)
        val intent = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, "Type-J")
            .putExtra(Intent.EXTRA_TEXT, "Review this plan")
        val imported = first.ingest(intent)
        assertEquals("Review this plan\n\nType-J", imported?.text)
        assertTrue(File(root, "pending.json").isFile)

        val restored = SharedImportRepository(context, json, dispatcher)
        restored.refresh()
        assertEquals(imported?.id, restored.pending.value?.id)
        assertEquals("Review this plan\n\nType-J", restored.pending.value?.text)

        restored.acknowledge(imported!!.id)
        assertNull(restored.pending.value)
        assertFalse(root.resolve("pending.json").exists())
    }

    @Test fun `shared content uri is copied before temporary grant disappears`() = runTest(dispatcher) {
        val uri = Uri.parse("content://type-j/shared/report.txt")
        Shadows.shadowOf(context.contentResolver).registerInputStream(
            uri,
            ByteArrayInputStream("kanban report".toByteArray()),
        )
        val intent = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM, uri)
        val repo = SharedImportRepository(context, json, dispatcher)
        val imported = repo.ingest(intent)
        val attachment = imported?.attachments?.single()
        assertEquals("report.txt", attachment?.filename)
        assertEquals("kanban report", attachment?.file?.readText())
        assertTrue(attachment!!.file.canonicalPath.startsWith(root.canonicalPath))
    }

    @Test fun `unsupported intent cannot overwrite a pending import`() = runTest(dispatcher) {
        val repo = SharedImportRepository(context, json, dispatcher)
        val original = repo.ingest(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "keep me"))
        assertNotNull(original)
        assertNull(repo.ingest(Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com"))))
        assertEquals(original?.id, repo.pending.value?.id)
    }

    @Test fun `empty share is discarded`() = runTest(dispatcher) {
        val repo = SharedImportRepository(context, json, dispatcher)
        assertNull(repo.ingest(Intent(Intent.ACTION_SEND).setType("text/plain")))
        assertNull(repo.pending.value)
    }
}
