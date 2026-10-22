package io.github.jcartu.hermex.ui.feature.tasks

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import io.github.jcartu.hermex.model.CronJob
import io.github.jcartu.hermex.model.CronRunHistoryResponse
import io.github.jcartu.hermex.model.InsightsResponse
import io.github.jcartu.hermex.model.ProviderQuotaResponse
import io.github.jcartu.hermex.model.ProvidersResponse
import io.github.jcartu.hermex.model.SessionsResponse
import io.github.jcartu.hermex.ui.adaptive.LocalPaneCount
import io.github.jcartu.hermex.ui.feature.insights.InsightsDataClient
import io.github.jcartu.hermex.ui.feature.insights.InsightsFeatures
import io.github.jcartu.hermex.ui.feature.insights.InsightsRoute
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Compose smoke: the Tasks agenda, Task Detail and Usage screens compose at
 * pane counts 1, 2 and 3 without crashing (Robolectric). The fork's contract
 * is that every screen works on phone, tablet/fold and trifold; this test
 * composes each surface at each pane count and asserts composition completed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TasksUsageComposeSmokeTest {

    private class SmokeCronClient : CronDataClient {
        override val cronFeatures: CronFeatures = CronFeatures.hermes
        override suspend fun cronJobs() = CronJobList(
            jobs = listOf(CronJob(jobId = "j1", name = "One", profile = "inbox")),
            runningJobs = emptyMap(),
            recentRuns = emptyList(),
        )
        override suspend fun cronRecent() = io.github.jcartu.hermex.model.CronRecentCompletionsResponse(completions = null)
        override suspend fun cronDeliveryOptions(profile: String?) = io.github.jcartu.hermex.model.CronDeliveryOptionsResponse(platforms = null)
        override suspend fun createCron(prompt: String, schedule: String, name: String?, deliver: String?, skills: List<String>, model: String?, provider: String?, profile: String?, toastNotifications: Boolean) = io.github.jcartu.hermex.model.CronMutationResponse(ok = true)
        override suspend fun updateCron(jobId: String, prompt: String?, schedule: String?, name: String?, deliver: String?, skills: List<String>?, model: String?, provider: String?, profile: String?, toastNotifications: Boolean?) = io.github.jcartu.hermex.model.CronMutationResponse(ok = true)
        override suspend fun runCron(jobId: String, profile: String?) = io.github.jcartu.hermex.model.CronMutationResponse(ok = true)
        override suspend fun pauseCron(jobId: String, profile: String?, reason: String?) = io.github.jcartu.hermex.model.CronMutationResponse(ok = true)
        override suspend fun resumeCron(jobId: String, profile: String?) = io.github.jcartu.hermex.model.CronMutationResponse(ok = true)
        override suspend fun deleteCron(jobId: String, profile: String?) = io.github.jcartu.hermex.model.CronMutationResponse(ok = true)
        override suspend fun cronOutput(jobId: String, limit: Int?) = io.github.jcartu.hermex.model.CronOutputResponse(outputs = null)
        override suspend fun cronHistory(jobId: String, profile: String?, offset: Int, limit: Int) = CronRunHistoryResponse()
        override suspend fun cronRunDetail(jobId: String, profile: String?, filename: String) = io.github.jcartu.hermex.model.CronRunDetailResponse(filename = filename, content = "")
        override suspend fun cronModelGroups(profile: String?) = emptyList<io.github.jcartu.hermex.model.ModelCatalogGroup>()
        override suspend fun cronProfiles() = emptyList<io.github.jcartu.hermex.model.ProfileSummary>()
        override suspend fun cronSkills(profile: String?) = emptyList<io.github.jcartu.hermex.model.SkillSummary>()
    }

    private class SmokeInsightsClient : InsightsDataClient {
        override val insightsFeatures: InsightsFeatures = InsightsFeatures.webui
        override suspend fun sessions() = SessionsResponse()
        override suspend fun insights(days: Int) = InsightsResponse()
        override suspend fun providers() = ProvidersResponse()
        override suspend fun providerQuota(provider: String, refresh: Boolean) = ProviderQuotaResponse()
    }

    private fun composeAtPaneCounts(content: @androidx.compose.runtime.Composable (Int) -> Unit) {
        for (paneCount in 1..3) {
            val composed = mutableStateOf(false)
            val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            controller.get().runOnUiThread {
                val composeView = ComposeView(controller.get())
                composeView.setContent {
                    CompositionLocalProvider(LocalPaneCount provides paneCount) {
                        DisposableEffect(Unit) {
                            composed.value = true
                            onDispose { }
                        }
                        content(paneCount)
                    }
                }
                controller.get().setContentView(composeView)
            }
            shadowOf(Looper.getMainLooper()).idle()
            controller.pause().stop().destroy()
            assertTrue("composition completed at pane count $paneCount", composed.value)
        }
    }

    @Test
    fun `Hermes tasks agenda composes at pane counts 1 to 3`() {
        composeAtPaneCounts { TasksRoute(client = SmokeCronClient()) }
    }

    @Test
    fun `task detail composes at pane counts 1 to 3`() {
        composeAtPaneCounts { paneCount ->
            TaskDetailRoute(
                job = CronJob(jobId = "j1", name = "One"),
                runningElapsed = null,
                client = SmokeCronClient(),
                onMutation = {},
                onJobStartedRunning = {},
                onJobPaused = {},
                onDeleted = {},
                onBack = if (paneCount == 1) ({}) else null,
            )
        }
    }

    @Test
    fun `usage screen composes at pane counts 1 to 3`() {
        composeAtPaneCounts { InsightsRoute(client = SmokeInsightsClient()) }
    }
}
