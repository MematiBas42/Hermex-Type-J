package io.github.jcartu.hermex.ui.feature.movetohermes

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import io.github.jcartu.hermex.HermexApplication
import io.github.jcartu.hermex.model.ServerAccount
import io.github.jcartu.hermex.model.ServerKind
import io.github.jcartu.hermex.ui.adaptive.LocalPaneCount
import io.github.jcartu.hermex.ui.theme.HermexTheme
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = HermexApplication::class)
class MoveToHermesComposeTest {
    private val source = ServerAccount("https://webui.example", "https://webui.example", displayName = "Legacy")
    private val destination = ServerAccount(
        "https://hermes.example",
        "https://hermes.example",
        displayName = "Hermes",
        kind = ServerKind.HERMES,
        createdAt = 2.0,
    )
    private val scan = MoveScanResult(
        listOf(MoveScanEntry(MoveSourceChat("session-a", profile = "default"), MoveChatLocation.Missing)),
    )
    private val record = MoveRecord(
        sourceServerId = source.id,
        destinationServerId = destination.id,
        destinationCreatedAt = destination.createdAt,
        profileMappings = listOf(MoveProfileMapping("default", "default")),
        hermesProfiles = listOf("default"),
        items = listOf(
            MoveItemProgress(
                MoveSourceChat("session-a"),
                destinationProfile = "default",
                copyState = MoveCopyState.PENDING,
            ),
        ),
        createdAtEpochMillis = 1L,
    )

    @Test
    fun `wizard steps and banner compose at pane counts one two and three`() {
        val states = listOf(
            MoveToHermesUiState(stage = MoveWizardStage.CHOOSE_HOST, source = source, hostChoices = listOf(destination)),
            MoveToHermesUiState(stage = MoveWizardStage.CHECKING, source = source, destination = destination, checkedChats = 1, totalChats = 3),
            MoveToHermesUiState(
                stage = MoveWizardStage.REVIEW,
                source = source,
                destination = destination,
                scan = scan,
                catalog = HermesMoveProfileCatalog(listOf("default"), "default"),
                profileChoices = listOf(MoveProfileChoice("default", 1, true)),
                mappings = listOf(MoveProfileMapping("default", "default")),
            ),
            MoveToHermesUiState(stage = MoveWizardStage.MOVING, source = source, destination = destination, record = record),
            MoveToHermesUiState(stage = MoveWizardStage.PAUSED, source = source, destination = destination, record = record),
            MoveToHermesUiState(
                stage = MoveWizardStage.FINISHED,
                source = source,
                destination = destination,
                record = record.copy(stage = MoveStage.FINISHED, report = MoveReportBuilder.build(record)),
            ),
        )
        for (paneCount in 1..3) {
            for (state in states) {
                compose(paneCount) {
                    MoveToHermesWizardContent(
                        state = state,
                        onChooseHost = {},
                        onAddHost = {},
                        onMapProfile = { _, _, _ -> },
                        onMove = {},
                        onRetry = {},
                        onResume = {},
                        onFinishEarly = {},
                        onCopyAgain = {},
                        onDone = {},
                        onMoveNext = {},
                        onRemove = {},
                        onClose = {},
                    )
                }
            }
            compose(paneCount) {
                MoveToHermesBannerCard(
                    kind = MoveBannerKind.PAUSED,
                    sourceName = "Legacy",
                    destinationName = "Hermes",
                    inHermes = 1,
                    total = 3,
                    onPrimary = {},
                    onDetails = {},
                    onCopyAgain = {},
                    onRemove = {},
                )
            }
        }
    }

    private fun compose(paneCount: Int, content: @Composable () -> Unit) {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.setContent {
            HermexTheme(darkTheme = true) {
                CompositionLocalProvider(LocalPaneCount provides paneCount) { content() }
            }
        }
        shadowOf(Looper.getMainLooper()).idle()
        assertNotNull(activity.window.decorView)
        activity.finish()
    }
}
