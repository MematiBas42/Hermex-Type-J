package io.github.jcartu.hermex.model

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class KanbanModelTest {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
    }

    @Test
    fun `configuration tolerates assignee unions and lossy scalars`() {
        val value = json.decodeFromString<KanbanConfiguration>(
            """{
              "columns":["triage","ready","running","done"],
              "assignees":["default",{"name":"reviewer"},42,null],
              "default_tenant":7,
              "lane_by_profile":"yes",
              "include_archived_by_default":0,
              "read_only":"false",
              "future_field":{"ignored":true}
            }""",
        )
        assertEquals(listOf("default", "reviewer", "42"), value.assignees)
        assertEquals("7", value.defaultTenant)
        assertEquals(true, value.laneByProfile)
        assertEquals(false, value.includeArchivedByDefault)
        assertEquals(false, value.readOnly)
    }

    @Test
    fun `card preserves unknown status and lossy numeric fields`() {
        val card = json.decodeFromString<KanbanCard>(
            """{
              "id":123,
              "title":"Contract audit",
              "status":"future-review",
              "priority":"4",
              "comment_count":2.9,
              "age_seconds":"3601",
              "skills":["android","review"],
              "unknown":"safe"
            }""",
        )
        assertEquals("123", card.cardId)
        assertEquals(4, card.priority)
        assertEquals(2, card.commentCount)
        assertFalse(card.status!!.isSupported)
        assertEquals(KanbanStaleness.NONE, card.staleness)
    }

    @Test
    fun `running and blocked cards derive staleness`() {
        assertEquals(KanbanStaleness.WARNING, KanbanCard(status = KanbanStatus("running"), ageSeconds = 700.0).staleness)
        assertEquals(KanbanStaleness.CRITICAL, KanbanCard(status = KanbanStatus("running"), ageSeconds = 3_700.0).staleness)
        assertEquals(KanbanStaleness.CRITICAL, KanbanCard(status = KanbanStatus("blocked"), ageSeconds = 90_000.0).staleness)
    }

    @Test
    fun `dispatch counts arrays and scalar variants`() {
        val result = json.decodeFromString<KanbanDispatchResult>(
            """{"spawned":[{"id":1},{"id":2}],"promoted":"3","reclaimed":1,"timed_out":[1]}""",
        )
        assertEquals(2, result.spawnedCount)
        assertEquals(3, result.promotedCount)
        assertEquals(1, result.reclaimedCount)
        assertTrue(result.hasKnownCategory)
    }

    @Test
    fun `mutation validator rejects missing or mismatched identity`() {
        val good = KanbanCardMutationEnvelope(KanbanCard(cardId = "c1", status = KanbanStatus("todo")))
        assertEquals("c1", KanbanValidators.requireCard(good, "c1").cardId)
        assertThrows(KanbanContractException::class.java) { KanbanValidators.requireCard(good, "c2") }
        assertThrows(KanbanContractException::class.java) { KanbanValidators.requireCard(KanbanCardMutationEnvelope()) }
    }

    @Test
    fun `dependency validator requires echoed identities`() {
        val request = KanbanDependencyMutationRequest("main", "parent", "child")
        KanbanValidators.requireDependency(
            KanbanDependencyMutationEnvelope(true, true, "parent", "child"), request,
        )
        assertThrows(KanbanContractException::class.java) {
            KanbanValidators.requireDependency(
                KanbanDependencyMutationEnvelope(true, true, "wrong", "child"), request,
            )
        }
    }
}
