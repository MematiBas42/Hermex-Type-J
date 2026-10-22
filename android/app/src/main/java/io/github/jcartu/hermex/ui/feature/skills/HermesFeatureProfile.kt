package io.github.jcartu.hermex.ui.feature.skills

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.jcartu.hermex.di.AppContainer
import io.github.jcartu.hermex.model.ServerAccount
import io.github.jcartu.hermex.net.hermes.BotClient
import io.github.jcartu.hermex.net.hermes.HermesConnection
import io.github.jcartu.hermex.ui.components.EmptyState
import io.github.jcartu.hermex.ui.components.ErrorState
import io.github.jcartu.hermex.ui.components.LoadingState
import io.github.jcartu.hermex.ui.feature.hermes.sessions.HermesProfilePreference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Shared resolver for Profile-scoped Hermes destinations (Skills, Memory, and Usage). */
data class HermesFeatureProfileState(
    val profile: String? = null,
    val isLoading: Boolean = true,
    val error: String? = null,
)

class HermesFeatureProfileViewModel(
    private val http: HermesConnection,
) : ViewModel() {
    private val mutableState = MutableStateFlow(HermesFeatureProfileState())
    val state: StateFlow<HermesFeatureProfileState> = mutableState.asStateFlow()

    fun resolve(preferred: String?) {
        if (!preferred.isNullOrBlank()) {
            mutableState.value = HermesFeatureProfileState(profile = preferred, isLoading = false)
            return
        }
        viewModelScope.launch {
            mutableState.value = HermesFeatureProfileState(isLoading = true)
            val client = BotClient(http)
            try {
                client.connect()
                mutableState.value = HermesFeatureProfileState(
                    profile = client.currentProfile(),
                    isLoading = false,
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                mutableState.value = HermesFeatureProfileState(
                    isLoading = false,
                    error = error.message ?: "Could not load the active Profile.",
                )
            } finally {
                client.close()
            }
        }
    }
}

/** Resolves the shared connection and selected/active Profile before rendering one feature. */
@Composable
fun HermesProfileFeatureHost(
    container: AppContainer,
    account: ServerAccount,
    modifier: Modifier = Modifier,
    content: @Composable (profile: String, connection: HermesConnection) -> Unit,
) {
    val context = LocalContext.current
    val saved = container.botConnectionStore.load(account.id)
    if (saved == null) {
        EmptyState(
            title = "Sign in to Hermes",
            message = "Update this server's Hermes sign-in to continue.",
            modifier = modifier,
        )
        return
    }
    val connection = container.hermesConnections.connection(saved, account.id)
    val preferred by HermesProfilePreference.observe(context, account.id).collectAsStateWithLifecycle()
    val resolver: HermesFeatureProfileViewModel = viewModel(
        key = "hermes-feature-profile:${account.id}:${saved.id}",
        factory = viewModelFactory { initializer { HermesFeatureProfileViewModel(connection) } },
    )
    val state by resolver.state.collectAsStateWithLifecycle()
    LaunchedEffect(preferred, connection) { resolver.resolve(preferred) }
    val profile = state.profile
    when {
        state.isLoading -> LoadingState(modifier = modifier, label = "Loading Profile…")
        state.error != null -> ErrorState(message = state.error.orEmpty(), modifier = modifier) {
            resolver.resolve(preferred)
        }
        profile != null -> content(profile, connection)
    }
}
