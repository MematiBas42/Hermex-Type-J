package io.github.jcartu.hermex.ui.feature.movetohermes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MoveToHermesPlannerTest {
    @Test
    fun `scan maps same-name Profiles first and unmatched Profiles to Hermes default`() {
        val scan = MoveScanResult(
            entries = listOf(
                MoveScanEntry(MoveSourceChat("a", profile = "work"), MoveChatLocation.Missing),
                MoveScanEntry(MoveSourceChat("b", profile = "legacy"), MoveChatLocation.present("archive")),
            ),
        )
        val catalog = HermesMoveProfileCatalog(listOf("default", "work", "archive"), defaultProfile = "default")

        val choices = MoveToHermesPlanner.profileChoices(scan, listOf("default", "work", "legacy"), catalog)
        val mappings = MoveToHermesPlanner.initialMappings(choices, catalog)
        val items = MoveToHermesPlanner.progressItems(scan, mappings)

        assertEquals("default", mappings.first { it.sourceProfile == "default" }.destinationProfile)
        assertEquals("work", mappings.first { it.sourceProfile == "work" }.destinationProfile)
        assertEquals("default", mappings.first { it.sourceProfile == "legacy" }.destinationProfile)
        assertEquals(MoveCopyState.PENDING, items.first { it.chat.id == "a" }.copyState)
        assertEquals(MoveCopyState.ALREADY_PRESENT, items.first { it.chat.id == "b" }.copyState)
        assertEquals("archive", items.first { it.chat.id == "b" }.locatedProfile)
    }

    @Test
    fun `unmatched Profile without a host default remains unchosen rather than silently kept`() {
        val scan = MoveScanResult(listOf(MoveScanEntry(MoveSourceChat("a", profile = "legacy"), MoveChatLocation.Missing)))
        val catalog = HermesMoveProfileCatalog(listOf("work"), defaultProfile = null)

        val mapping = MoveToHermesPlanner.initialMappings(
            MoveToHermesPlanner.profileChoices(scan, emptyList(), catalog),
            catalog,
        ).single()

        assertNull(mapping.destinationProfile)
        assertFalse(mapping.keepOnWebui)
        val kept = mapping.copy(keepOnWebui = true)
        assertTrue(kept.keepOnWebui)
        assertEquals(MoveCopyState.KEPT, MoveToHermesPlanner.progressItems(scan, listOf(kept)).single().copyState)
    }
}
