package io.github.jcartu.hermex.ui.feature.tasks

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.ComposeView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import io.github.jcartu.hermex.model.CronJob
import io.github.jcartu.hermex.ui.adaptive.LocalPaneCount
import io.github.jcartu.hermex.ui.feature.insights.InsightsRoute
import io.github.jcartu.hermex.ui.feature.insights.WebuiInsightsDataClient
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
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
@Config(sdk = [35], manifest = Config.NONE)
class TasksUsageComposeSmokeTest {

    private class SmokeCronClient : CronDataClient {
        override val cronFeatures: CronFeatures = CronFeatures.webui
        override suspend fun cronJobs() = CronJobList(jobs = listOf(CronJob(jobId = "j1", name = "One")), runningJobs = emptyMap())
        override suspend fun cronRecent() = io.github.jcartu.hermex.model.CronRecentCompletionsResponse(emptyList())
        override suspend fun cronDeliveryOptions(profile: String?) = io.github.jcartu.hermex.model.CronDeliveryOptionsResponse(emptyList())
        override suspend fun createCron(prompt: String, schedule: String, name: String?, deliver: String?, skills: List<String>, model: String?, provider: String?, profile: String?, toastNotifications: Boolean) = io.github.jcartu.hermex.model.CronMutationResponse(ok = true)
        override suspend fun updateCron(jobId: String, prompt: String?, schedule: String?, name: String?, deliver: String?, skills: List<String>?, model: String?, provider: String?, profile: String?, toastNotifications: Boolean?) = io.github.jcartu.hermex.model.CronMutationResponse(ok = true)
        override suspend fun runCron(jobId: String, profile: String?) = io.github.jcartu.hermex.model.CronMutationResponse(ok = true)
        override suspend fun pauseCron(jobId: String, profile: String?, reason: String?) = io.github.jcartu.hermex.model.CronMutationResponse(ok = true)
        override suspend fun resumeCron(jobId: String, profile: String?) = io.github.jcartu.hermex.model.CronMutationResponse(ok = true)
        override suspend fun deleteCron(jobId: String, profile: String?) = io.github.jcartu.hermex.model.CronMutationResponse(ok = true)
        override suspend fun cronOutput(jobId: String, limit: Int?) = io.github.jcartu.hermex.model.CronOutputResponse(emptyList())
        override suspend fun cronHistory(jobId: String, profile: String?, offset: Int, limit: Int) = CronRunHistoryResponse(runs = emptyList())
        override suspend fun cronRunDetail(jobId: String, profile: String?, filename: String) = io.github.jcartu.hermex.model.CronRunDetailResponse(filename = filename, content = "")
        override suspend fun cronModelGroups(profile: String?) = emptyList<io.github.jcartu.hermex.model.ModelCatalogGroup>()
        override suspend fun cronProfiles() = emptyList<io.github.jcartu.hermex.model.ProfileSummary>()
        override suspend fun cronSkills(profile: String?) = emptyList<io.github.jcartu.hermex.model.SkillSummary>()
    }

    private class SmokeInsightsClient(private val api: io.github.jcartu.hermex.net.HermesApi? = null) : InsightsDataClient {
        override val insightsFeatures: InsightsFeatures = InsightsFeatures.webui
        override suspend fun sessions() = SessionsResponse()
        override suspend fun insights(days: Int) = InsightsResponse()
        override suspend fun providers() = ProvidersResponse()
        override suspend fun providerQuota(provider: String, refresh: Boolean) = ProviderQuotaResponse()
    }

    private fun composeAtPaneCounts(content: @androidx.compose.runtime.Composable (Int) -> Unit) {
        for (paneCount in 1..3) {
            var composed = false
            ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val composeView = ComposeView(activity)
                    composeView.setContent {
                        CompositionLocalProvider(LocalPaneCount provides paneCount) {
                            DisposableEffect(Unit) {
                                composed = true
                                onDispose { }
                            }
                            content(paneCount)
                        }
                    }
                    activity.setContentView(composeView)
                }
                shadowOf(Looper.getMainLooper()).idle()
            }
            assertTrue("composition completed at pane count $paneCount", composed)
        }
    }

    @Test
    fun `tasks agenda composes at pane counts 1 to 3`() {
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
