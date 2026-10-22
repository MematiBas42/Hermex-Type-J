package io.github.jcartu.hermex.ui.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.jcartu.hermex.core.prefs.BotConnectionRecords
import io.github.jcartu.hermex.model.ServerAccount
import io.github.jcartu.hermex.model.hermes.BotConnection
import io.github.jcartu.hermex.model.hermes.BotConnectionAdvice
import io.github.jcartu.hermex.model.hermes.BotFailure
import io.github.jcartu.hermex.model.hermes.BotJSON
import io.github.jcartu.hermex.net.hermes.HermesAddress
import io.github.jcartu.hermex.net.hermes.HermesConnections
import io.github.jcartu.hermex.net.hermes.HermesHeaders
import io.github.jcartu.hermex.net.hermes.HermesREST
import io.github.jcartu.hermex.net.hermes.HermesStatusProbe
import io.github.jcartu.hermex.ui.feature.hermes.connect.HermesHostStatus
import io.github.jcartu.hermex.ui.feature.onboarding.AuthManager
import io.github.jcartu.hermex.ui.feature.onboarding.AuthState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Port of `SettingsServerPage.hermesSections` and `BotConnectionView.statusSection`. */
data class HermesServerSettingsState(
    val saved: BotConnection? = null,
    val isActive: Boolean = false,
    val signedIn: Boolean = false,
    val hostStatus: HermesHostStatus? = null,
    val companionInstalled: Boolean? = null,
    val companionVersion: String? = null,
    val error: String? = null,
)

/** Direct-Hermes Settings, with no HermesApi/webui dependency. */
class HermesServerSettingsViewModel(
    private val account: ServerAccount,
    private val records: BotConnectionRecords,
    private val connections: HermesConnections,
    private val auth: AuthManager,
    private val probe: HermesStatusProbe,
) : ViewModel() {
    private val _state = MutableStateFlow(HermesServerSettingsState())
    val state = _state.asStateFlow()
    private var loading = false

    fun load() {
        if (loading) return
        val signedIn = (auth.state.value as? AuthState.LoggedIn)?.account?.id == account.id
        val active = auth.activeServerID == account.id
        val saved = try { records.load(account.id) } catch (_: Exception) {
            _state.update { it.copy(error = "Could not read saved sign-in details.") }; return
        }
        _state.update { it.copy(saved = saved, isActive = active, signedIn = signedIn) }
        if (!active) return
        val address = saved?.address ?: HermesAddress.url(account.urlString) ?: return
        loading = true
        viewModelScope.launch {
            try {
                _state.update { it.copy(hostStatus = HermesHostStatus.Checking, error = null) }
                val result = probe.check(address, saved?.let(HermesHeaders::saved) ?: HermesHeaders.NONE)
                _state.update { it.copy(hostStatus = result.fold({ status -> HermesHostStatus.Reachable(status) }, { error -> HermesHostStatus.Unreachable(error) })) }
                // Rejected saved credentials must not be tried by opening Settings from the root form.
                if (saved != null && signedIn && (auth.state.value as? AuthState.LoggedIn)?.account?.id == account.id) {
                    val reply = connections.connection(saved, account.id).reply(HermesREST.PluginCapabilities)
                    when {
                        reply.status == 404 -> _state.update { it.copy(companionInstalled = false, companionVersion = null) }
                        reply.status in 200..299 -> {
                            val json = BotJSON.parse(reply.body)
                            if (!json.isObject) throw BotFailure.Unsupported
                            _state.update { it.copy(companionInstalled = true, companionVersion = json["plugin_version"].text) }
                        }
                        else -> throw BotFailure.Rejected(reply.status)
                    }
                }
            } catch (error: CancellationException) { throw error
            } catch (error: Exception) { _state.update { it.copy(error = BotConnectionAdvice.message(error, address)) }
            } finally { loading = false }
        }
    }
}
