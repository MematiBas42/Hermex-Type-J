package io.github.jcartu.hermex.ui.feature.bots

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import io.github.jcartu.hermex.HermexApplication
import io.github.jcartu.hermex.model.ModelCatalogGroup
import io.github.jcartu.hermex.model.ModelCatalogOption
import io.github.jcartu.hermex.model.hermes.BotConnection
import io.github.jcartu.hermex.model.hermes.BotJSON
import io.github.jcartu.hermex.model.hermes.BotProfile
import io.github.jcartu.hermex.model.hermes.BotProfileAppearance
import io.github.jcartu.hermex.model.hermes.BotProfileCapability
import io.github.jcartu.hermex.ui.adaptive.LocalPaneCount
import io.github.jcartu.hermex.ui.theme.HermexTheme
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The pure editor body must compose without a transport at phone, dual-pane and trifold widths. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = HermexApplication::class)
class BotEditorComposeTest {
    @Test
    fun `loaded editor content composes at pane counts one two and three`() {
        val profile = BotProfile.fromJsonOrNull(
            BotJSON.objectOf(
                "name" to BotJSON.string("research"),
                "display_name" to BotJSON.string("Research"),
                "ui_meta" to BotJSON.objectOf(
                    "hermes-bots" to BotJSON.objectOf(
                        "title" to BotJSON.string("Researcher"),
                        "shape" to BotJSON.string("blob"),
                        "color" to BotJSON.string("#38bdf8"),
                        "custom" to BotJSON.bool(true),
                    ),
                ),
                "has_avatar" to BotJSON.bool(false),
            ),
        )!!
        val appearance = BotProfileAppearance.of(profile)
        val draft = BotProfileEditor.Draft(
            appearance = appearance,
            description = "Find and verify sources",
            instructions = "Cite primary sources.",
            model = ModelCatalogOption("model-a", "Model A", "provider"),
            skills = listOf(BotProfileCapability("research", "Research", enabled = true)),
            toolsets = listOf(BotProfileCapability("web", "Web", "4 tools", enabled = true)),
            mcpServers = listOf(BotProfileCapability("linear", "Linear", "http", enabled = false)),
        )
        val state = BotProfileEditor.State(
            profile = profile,
            loadState = BotProfileEditor.LoadState.Loaded,
            draft = draft,
            baseline = draft.copy(description = "Old description"),
            modelGroups = listOf(
                ModelCatalogGroup(
                    id = "provider",
                    name = "Provider",
                    providerID = "provider",
                    models = listOf(ModelCatalogOption("model-a", "Model A", "provider")),
                ),
            ),
            outcomes = mapOf(BotProfileEditor.Field.INSTRUCTIONS to BotProfileEditor.Outcome.Saved),
        )
        val connection = BotConnection(
            id = "connection-one",
            name = "Hermes Mac",
            address = "https://mac.example".toHttpUrl(),
            username = "u",
            password = "p",
        )

        for (paneCount in 1..3) {
            val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
            activity.setContent {
                HermexTheme(darkTheme = true) {
                    CompositionLocalProvider(LocalPaneCount provides paneCount) {
                        BotProfileEditorContent(state = state, connection = connection)
                    }
                }
            }
            shadowOf(Looper.getMainLooper()).idle()
            assertNotNull(activity.window.decorView)
            activity.finish()
        }
    }
}
