package io.github.jcartu.hermex.ui.feature.skills

import io.github.jcartu.hermex.model.SkillSummary
import io.github.jcartu.hermex.model.SkillsResponse
import io.github.jcartu.hermex.model.ToggleSkillResponse
import io.github.jcartu.hermex.net.ApiError
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SkillsViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun makeClient(skills: List<SkillSummary>): SkillsDataClient =
        mockk<SkillsDataClient>().also { client ->
            coEvery { client.skills() } returns skills
        }

    @Test
    fun `load groups skills by category with fallbacks and sorting`() = runTest(dispatcher) {
        val skills = listOf(
            SkillSummary(name = "zsh-help", category = "Writing"),
            SkillSummary(name = "Blogging", category = "Writing"),
            SkillSummary(name = "git", category = "coding"),
            SkillSummary(name = "orphan", category = "  "),
            SkillSummary(name = null, category = null),
        )
        val viewModel = SkillsViewModel(makeClient(skills))
        advanceUntilIdle()

        val groups = viewModel.uiState.value.groups
        // Case-insensitive category ordering: coding < Uncategorized < Writing.
        assertEquals(listOf("coding", "Uncategorized", "Writing"), groups.map { it.category })

        val uncategorized = groups[1]
        assertEquals(2, uncategorized.skills.size)
        // "orphan" sorts before "Unnamed Skill" fallback name.
        assertEquals("orphan", SkillsViewModel.displayName(uncategorized.skills[0]))
        assertEquals("Unnamed Skill", SkillsViewModel.displayName(uncategorized.skills[1]))

        // Skills inside a category sort case-insensitively by display name.
        assertEquals(listOf("Blogging", "zsh-help"), groups[2].skills.map { it.name })
    }

    @Test
    fun `search matches name description category and tags`() = runTest(dispatcher) {
        val git = SkillSummary(name = "git", category = "Coding", description = "Version control")
        val prose = SkillSummary(
            name = "prose",
            category = "Writing",
            description = "Editing helper",
            tags = listOf("markdown", "style"),
        )
        val viewModel = SkillsViewModel(makeClient(listOf(git, prose)))
        advanceUntilIdle()

        viewModel.setSearchText("GIT")
        assertEquals(listOf("git"), viewModel.uiState.value.groups.flatMap { it.skills }.map { it.name })

        viewModel.setSearchText("version")
        assertEquals(listOf("git"), viewModel.uiState.value.groups.flatMap { it.skills }.map { it.name })

        viewModel.setSearchText("writing")
        assertEquals(listOf("prose"), viewModel.uiState.value.groups.flatMap { it.skills }.map { it.name })

        viewModel.setSearchText("markdown")
        assertEquals(listOf("prose"), viewModel.uiState.value.groups.flatMap { it.skills }.map { it.name })

        viewModel.setSearchText("no-such-skill")
        assertTrue(viewModel.uiState.value.groups.isEmpty())

        viewModel.setSearchText("")
        assertEquals(2, viewModel.uiState.value.groups.size)
    }

    @Test
    fun `load failure surfaces error message`() = runTest(dispatcher) {
        val client = mockk<SkillsDataClient>()
        coEvery { client.skills() } throws ApiError.Http(500, null)

        val viewModel = SkillsViewModel(client)
        advanceUntilIdle()

        assertNotNull(viewModel.uiState.value.errorMessage)
        assertFalse(viewModel.uiState.value.isLoading)
    }

    @Test
    fun `toggle optimistically flips then reloads from the server`() = runTest(dispatcher) {
        val enabled = SkillSummary(name = "git", category = "Coding", disabled = false)
        val disabledFromServer = enabled.copy(disabled = true)
        val client = mockk<SkillsDataClient>()
        coEvery { client.skills() } returnsMany listOf(
            listOf(enabled),
            listOf(disabledFromServer),
        )
        coEvery { client.toggleSkill("git", false) } returns
            ToggleSkillResponse(ok = true, name = "git", enabled = false)

        val viewModel = SkillsViewModel(client)
        advanceUntilIdle()
        assertEquals(false, viewModel.uiState.value.skills.single().disabled)

        viewModel.setSkill(enabled, enabled = false)
        // Optimistic flip is synchronous, before the network round-trip.
        assertEquals(true, viewModel.uiState.value.skills.single().disabled)
        assertTrue("git" in viewModel.uiState.value.togglingSkillNames)

        advanceUntilIdle()
        assertEquals(true, viewModel.uiState.value.skills.single().disabled)
        assertTrue(viewModel.uiState.value.togglingSkillNames.isEmpty())
        coVerify { client.toggleSkill("git", false) }
        coVerify(exactly = 2) { client.skills() }
    }

    @Test
    fun `toggle failure rolls back and surfaces the error`() = runTest(dispatcher) {
        val enabled = SkillSummary(name = "git", category = "Coding", disabled = false)
        val client = mockk<SkillsDataClient>()
        coEvery { client.skills() } returns listOf(enabled)
        coEvery { client.toggleSkill("git", false) } throws ApiError.Http(500, null)

        val viewModel = SkillsViewModel(client)
        advanceUntilIdle()

        viewModel.setSkill(enabled, enabled = false)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(false, state.skills.single().disabled)
        assertNotNull(state.errorMessage)
        assertTrue(state.togglingSkillNames.isEmpty())
    }

    @Test
    fun `toggle ignores skills without a usable name`() = runTest(dispatcher) {
        val nameless = SkillSummary(name = "  ", disabled = false)
        val client = makeClient(listOf(nameless))
        val viewModel = SkillsViewModel(client)
        advanceUntilIdle()

        viewModel.setSkill(nameless, enabled = false)
        advanceUntilIdle()

        coVerify(exactly = 0) { client.toggleSkill(any(), any()) }
    }

    @Test
    fun `decodes skills fixture with unknown fields`() = runTest(dispatcher) {
        val fixture = """
            {
              "skills": [
                {
                  "name": "git",
                  "category": "Coding",
                  "description": "Version control workflows",
                  "path": "/skills/git",
                  "disabled": false,
                  "tags": ["vcs"],
                  "related_skills": ["github"],
                  "unknown_field": 42
                }
              ],
              "extra": "ignored"
            }
        """.trimIndent()
        val response = json.decodeFromString<SkillsResponse>(fixture)

        val client = mockk<SkillsDataClient>()
        coEvery { client.skills() } returns response.skills.orEmpty()
        val viewModel = SkillsViewModel(client)
        advanceUntilIdle()

        val skill = viewModel.uiState.value.skills.single()
        assertEquals("git", skill.name)
        assertEquals(listOf("github"), skill.relatedSkills)
        assertEquals(listOf("vcs"), skill.tags)
        assertNull(viewModel.uiState.value.errorMessage)
    }
}
