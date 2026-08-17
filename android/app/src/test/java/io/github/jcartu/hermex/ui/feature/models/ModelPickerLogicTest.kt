package io.github.jcartu.hermex.ui.feature.models

import io.github.jcartu.hermex.model.ModelCatalogGroup
import io.github.jcartu.hermex.model.ModelCatalogOption
import io.github.jcartu.hermex.model.ModelFavoriteKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelPickerSectionExpansionStateTest {

    @Test
    fun `groups are collapsed by default outside search`() {
        val state = ModelPickerSectionExpansionState()
        assertFalse(state.isExpanded("anthropic"))
    }

    @Test
    fun `explicit expansion persists outside search`() {
        val state = ModelPickerSectionExpansionState()
            .settingExpanded(true, "anthropic")
        assertTrue(state.isExpanded("anthropic"))
        assertFalse(state.settingExpanded(false, "anthropic").isExpanded("anthropic"))
    }

    @Test
    fun `search expands every group until explicitly collapsed`() {
        val searching = ModelPickerSectionExpansionState().updatingSearchText("claude")
        assertTrue(searching.isExpanded("anthropic"))
        assertTrue(searching.isExpanded("openai"))

        val collapsed = searching.settingExpanded(false, "openai")
        assertFalse(collapsed.isExpanded("openai"))
        assertTrue(collapsed.isExpanded("anthropic"))
    }

    @Test
    fun `changing the query resets search collapses`() {
        val state = ModelPickerSectionExpansionState()
            .updatingSearchText("claude")
            .settingExpanded(false, "openai")
            .updatingSearchText("gpt")
        assertTrue(state.isExpanded("openai"))
    }

    @Test
    fun `whitespace-only query change is ignored`() {
        val state = ModelPickerSectionExpansionState()
            .updatingSearchText("claude")
            .settingExpanded(false, "openai")
            .updatingSearchText("  claude  ")
        assertFalse(state.isExpanded("openai"))
    }

    @Test
    fun `clearing the query returns to opt-in expansion`() {
        val state = ModelPickerSectionExpansionState()
            .settingExpanded(true, "anthropic")
            .updatingSearchText("claude")
            .updatingSearchText("")
        assertTrue(state.isExpanded("anthropic"))
        assertFalse(state.isExpanded("openai"))
    }
}

class ModelPickerLogicTest {

    private val anthropic = ModelCatalogGroup(
        id = "anthropic",
        name = "Anthropic",
        providerID = "anthropic",
        models = listOf(
            ModelCatalogOption("claude-fable-5", "Claude Fable 5", "anthropic"),
            ModelCatalogOption("claude-opus-5", "Claude Opus 5", "anthropic"),
        ),
    )
    private val openai = ModelCatalogGroup(
        id = "openai",
        name = "OpenAI",
        providerID = "openai",
        models = listOf(ModelCatalogOption("codex-sol", "Codex Sol", "openai")),
    )
    private val groups = listOf(anthropic, openai)

    @Test
    fun `search filters options by name id and provider`() {
        assertEquals(listOf("anthropic"), filteredCatalogGroups(groups, "fable").map { it.id })
        assertEquals(1, filteredCatalogGroups(groups, "fable").single().models.size)
        // provider match keeps the whole provider's models
        assertEquals(2, filteredCatalogGroups(groups, "ANTHROPIC").single().models.size)
        assertEquals(groups, filteredCatalogGroups(groups, "   "))
        assertTrue(filteredCatalogGroups(groups, "zzz").isEmpty())
    }

    @Test
    fun `custom option requires model and provider and lowercases provider`() {
        assertNull(customOption("", "openai"))
        assertNull(customOption("gpt-x", "   "))
        val option = customOption("  gpt-x  ", "  OpenAI  ")
        assertNotNull(option)
        assertEquals("gpt-x", option!!.id)
        assertEquals("gpt-x", option.displayName)
        assertEquals("openai", option.providerID)
    }

    @Test
    fun `selectedCustomOption only reports selections missing from the catalog`() {
        assertNull(selectedCustomOption(groups, "claude-fable-5", "anthropic", ""))
        val custom = selectedCustomOption(groups, "my-model", "my-provider", "")
        assertEquals("my-model", custom!!.id)
        // search-filtered like every other row
        assertNull(selectedCustomOption(groups, "my-model", "my-provider", "claude"))
    }

    @Test
    fun `storedCustomOptions keeps only non-catalog favorites and recents`() {
        val favorites = listOf(
            ModelFavoriteKey("claude-fable-5", "anthropic"), // in catalog → dropped
            ModelFavoriteKey("custom-a", "prov"),
        )
        val recents = listOf(
            ModelFavoriteKey("custom-a", "prov"), // duplicate → dropped
            ModelFavoriteKey("custom-b", "prov"),
        )
        val stored = storedCustomOptions(groups, favorites, recents, "")
        assertEquals(listOf("custom-a", "custom-b"), stored.map { it.id })
    }

    @Test
    fun `customModelGroups orders current custom before saved custom`() {
        val favorites = listOf(ModelFavoriteKey("custom-a", "prov"))
        val result = customModelGroups(
            modelGroups = groups,
            favoriteKeys = favorites,
            recentKeys = emptyList(),
            selectedModelID = "other-custom",
            selectedModelProviderID = "prov",
            searchText = "",
        )
        assertEquals(listOf(CURRENT_CUSTOM_GROUP_ID, SAVED_CUSTOM_GROUP_ID), result.map { it.id })
        assertTrue(result.single { it.id == SAVED_CUSTOM_GROUP_ID }.allowsDelete)
        assertFalse(result.single { it.id == CURRENT_CUSTOM_GROUP_ID }.allowsDelete)
    }

    @Test
    fun `customModelGroups omits current custom when it is already saved`() {
        val favorites = listOf(ModelFavoriteKey("custom-a", "prov"))
        val result = customModelGroups(
            modelGroups = groups,
            favoriteKeys = favorites,
            recentKeys = emptyList(),
            selectedModelID = "custom-a",
            selectedModelProviderID = "prov",
            searchText = "",
        )
        assertEquals(listOf(SAVED_CUSTOM_GROUP_ID), result.map { it.id })
    }

    @Test
    fun `providerChoices prepends the unknown selected provider and dedupes case-insensitively`() {
        val choices = providerChoices(groups, "MyProvider")
        assertEquals(listOf("MyProvider", "anthropic", "openai"), choices.map { it.id })
        // provider already in the catalog is not duplicated
        assertEquals(listOf("anthropic", "openai"), providerChoices(groups, "ANTHROPIC").map { it.id })
        assertEquals(listOf("anthropic", "openai"), providerChoices(groups, null).map { it.id })
    }

    @Test
    fun `buildDisplayGroups pins favorites and recents before catalog groups`() {
        val favorites = listOf(ModelFavoriteKey("claude-fable-5", "anthropic"))
        val recents = listOf(
            ModelFavoriteKey("codex-sol", "openai"),
            ModelFavoriteKey("claude-fable-5", "anthropic"), // favorite → excluded from recents
        )
        val result = buildDisplayGroups(
            modelGroups = groups,
            favoriteKeys = favorites,
            recentKeys = recents,
            selectedModelID = null,
            selectedModelProviderID = null,
            searchText = "",
        )
        assertEquals(
            listOf(FAVORITES_GROUP_ID, RECENTS_GROUP_ID, "anthropic", "openai"),
            result.map { it.id },
        )
        assertEquals(listOf("claude-fable-5"), result.first { it.id == FAVORITES_GROUP_ID }.models.map { it.id })
        assertEquals(listOf("codex-sol"), result.first { it.id == RECENTS_GROUP_ID }.models.map { it.id })
    }
}

class WidestHingeFreeSegmentTest {

    @Test
    fun `no hinges returns the full window`() {
        assertEquals(0f to 1000f, widestHingeFreeSegment(1000f, emptyList()))
    }

    @Test
    fun `single centered hinge halves the window`() {
        val (start, width) = widestHingeFreeSegment(1000f, listOf(500f))
        assertEquals(0f, start)
        assertEquals(500f, width)
    }

    @Test
    fun `off-center hinge picks the wider side`() {
        val (start, width) = widestHingeFreeSegment(1000f, listOf(300f))
        assertEquals(300f, start)
        assertEquals(700f, width)
    }

    @Test
    fun `two hinges pick the widest of three segments`() {
        val (start, width) = widestHingeFreeSegment(1200f, listOf(300f, 900f))
        assertEquals(300f, start)
        assertEquals(600f, width)
    }

    @Test
    fun `out-of-window hinges are ignored`() {
        assertEquals(0f to 800f, widestHingeFreeSegment(800f, listOf(-10f, 0f, 800f, 900f)))
    }
}

class WorkspaceRowLogicTest {

    @Test
    fun `lastPathComponentFallback returns last segment or the input`() {
        assertEquals("project", lastPathComponentFallback("/home/josh/project"))
        assertEquals("project", lastPathComponentFallback("/home/josh/project/"))
        assertEquals("plain", lastPathComponentFallback("plain"))
        assertEquals("/", lastPathComponentFallback("/"))
    }

    @Test
    fun `savedWorkspaceRows drops empty paths and dedups`() {
        val rows = savedWorkspaceRows(
            listOf(
                io.github.jcartu.hermex.model.WorkspaceRoot(path = "/a", name = "A"),
                io.github.jcartu.hermex.model.WorkspaceRoot(path = null, name = "ghost"),
                io.github.jcartu.hermex.model.WorkspaceRoot(path = "", name = "empty"),
                io.github.jcartu.hermex.model.WorkspaceRoot(path = "/a", name = "dup"),
                io.github.jcartu.hermex.model.WorkspaceRoot(path = "/b", name = null),
            ),
        )
        assertEquals(listOf("/a", "/b"), rows.map { it.path })
        assertEquals("A", rows.first().name)
    }

    @Test
    fun `suggestionRows excludes saved paths current selection and duplicates`() {
        val saved = listOf(WorkspacePickerRow(path = "/a", name = "A"))
        val result = suggestionRows(
            suggestions = listOf("/a", "/b", "/b", "", "/current", "/c"),
            savedRows = saved,
            effectiveSelectedPath = "/current",
        )
        assertEquals(listOf("/b", "/c"), result)
    }
}
