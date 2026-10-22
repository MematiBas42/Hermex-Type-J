package io.github.jcartu.hermex.ui.feature.kanban

import io.github.jcartu.hermex.model.KanbanCardStatusRequest
import io.github.jcartu.hermex.model.KanbanConfiguration
import io.github.jcartu.hermex.model.KanbanEditCardRequest
import io.github.jcartu.hermex.net.hermes.HermesKanbanClient
import io.github.jcartu.hermex.net.hermes.KanbanUnavailable
import io.github.jcartu.hermex.net.hermes.KanbanWriteRefusal
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class HermesKanbanDataSourceTest {
    @Test
    fun `unavailable plugin copy is preserved`() = runTest {
        val transport = mockk<HermesKanbanClient>()
        val unavailable = KanbanUnavailable()
        coEvery { transport.kanbanConfiguration() } throws unavailable

        val failure = runCatching {
            HermesKanbanDataSource(transport).kanbanConfiguration()
        }.exceptionOrNull()

        assertSame(unavailable, failure)
        assertEquals(
            "Kanban isn't available on this Hermes host. Install and enable the Kanban plugin, then restart the dashboard.",
            failure?.message,
        )
    }

    @Test
    fun `write refusal keeps the host's own words`() = runTest {
        val transport = mockk<HermesKanbanClient>()
        val refusal = KanbanWriteRefusal(409, "A prerequisite is still open")
        val request = KanbanCardStatusRequest("c1", "main", "ready")
        coEvery { transport.setKanbanCardStatus(request) } throws refusal

        val failure = runCatching {
            HermesKanbanDataSource(transport).setKanbanCardStatus(request)
        }.exceptionOrNull()

        assertSame(refusal, failure)
        assertEquals(409, (failure as KanbanWriteRefusal).status)
        assertEquals("A prerequisite is still open", failure.message)
    }

    @Test
    fun `edit forwards whether assignment actually changed`() = runTest {
        val transport = mockk<HermesKanbanClient>()
        val request = KanbanEditCardRequest(
            cardId = "c1",
            board = "main",
            title = "Keep tenant",
            body = "Body",
            tenant = "immutable-tenant",
            priority = 3,
            assignee = "inbox",
            status = null,
        )
        coEvery { transport.editKanbanCard(request, false) } returns io.github.jcartu.hermex.model.KanbanCardMutationEnvelope()

        HermesKanbanDataSource(transport).editKanbanCard(request, changesAssignee = false)

        coVerify(exactly = 1) { transport.editKanbanCard(request, false) }
    }

    @Test
    fun `Hermes capabilities expose dispatcher logs and tenant read-only rule`() {
        val transport = mockk<HermesKanbanClient>(relaxed = true)
        val source = HermesKanbanDataSource(transport)

        assertEquals(KanbanBackend.HERMES, source.backend)
        assertTrue(source.capabilities.dispatcher)
        assertTrue(source.capabilities.workerLogs)
        assertFalse(source.capabilities.onlyMineFilter)
        assertTrue(KanbanBackend.HERMES.canEditTenant(isEditing = false))
        assertFalse(KanbanBackend.HERMES.canEditTenant(isEditing = true))
        assertTrue(KanbanBackend.WEBUI.canEditTenant(isEditing = true))
    }

    @Test
    fun `configuration delegates without inventing columns`() = runTest {
        val transport = mockk<HermesKanbanClient>()
        val configuration = KanbanConfiguration(defaultTenant = "default", columns = null, readOnly = false)
        coEvery { transport.kanbanConfiguration() } returns configuration

        assertSame(configuration, HermesKanbanDataSource(transport).kanbanConfiguration())
    }
}
