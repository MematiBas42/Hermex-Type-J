package io.github.jcartu.hermex.ui.feature.skills

import io.github.jcartu.hermex.model.SkillSummary
import io.github.jcartu.hermex.model.ToggleSkillResponse
import io.github.jcartu.hermex.model.hermes.HermesSkillDetail
import io.github.jcartu.hermex.net.hermes.HermesSkillsClient
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HermesSkillsDataClientTest {
    @Test
    fun `list toggle and linked file metadata map without losing Hermes flags`() = runTest {
        val transport = mockk<HermesSkillsClient>()
        val skill = SkillSummary(name = "git", category = "Coding", disabled = false)
        coEvery { transport.skills() } returns listOf(skill)
        coEvery { transport.toggleSkill("git", false) } returns
            ToggleSkillResponse(ok = true, name = "git", enabled = false)
        coEvery { transport.skillContent("git", null) } returns HermesSkillDetail(
            name = "git",
            content = "# Git",
            linkedFiles = listOf("references/branches.md", "usage.md"),
        )
        coEvery { transport.skillContent("git", "references/branches.md") } returns HermesSkillDetail(
            name = "git",
            content = null,
            isBinary = true,
            isTruncated = true,
        )
        val client = HermesSkillsDataClient(transport)

        assertEquals(listOf(skill), client.skills())
        assertEquals(
            listOf("references/branches.md", "usage.md"),
            client.skillContent("git").linkedFiles,
        )
        val linked = client.skillContent("git", "references/branches.md")
        assertTrue(linked.isBinary)
        assertTrue(linked.isTruncated)
        assertEquals(false, client.toggleSkill("git", false).enabled)
        coVerify { transport.toggleSkill("git", false) }
    }
}
