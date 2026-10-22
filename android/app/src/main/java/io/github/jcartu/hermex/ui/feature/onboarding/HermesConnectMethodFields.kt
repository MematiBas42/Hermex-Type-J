package io.github.jcartu.hermex.ui.feature.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.jcartu.hermex.net.hermes.HermesSignInMethod
import io.github.jcartu.hermex.ui.feature.hermes.connect.OpenDashboardWarning

/** Port of `OnboardingConnectPage` / `AddServerView`'s detected Hermes sign-in fields (#900). */
@Composable
fun HermesConnectMethodFields(
    state: OnboardingUiState,
    style: ConnectFormStyle,
    onUsernameChange: (String) -> Unit,
    onUseSavedSignIn: (AuthManager.SavedHermesSignIn) -> Unit,
) {
    if (!state.showsHermesSignIn) return
    when (state.signInMethod) {
        HermesSignInMethod.PASSWORD -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Username", color = style.secondaryText, style = MaterialTheme.typography.labelMedium)
            ConnectTextField(state.username, onUsernameChange, "Dashboard username", style,
                enabled = !state.isConnectionLocked, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
            state.savedSignIns.forEach { saved ->
                TextButton(onClick = { onUseSavedSignIn(saved) }, enabled = !state.isConnectionLocked) {
                    Text("Use the sign-in saved on ${saved.serverName}")
                }
            }
        }
        HermesSignInMethod.BROWSER -> Text("This server signs in through your browser.", color = style.secondaryText)
        HermesSignInMethod.OPEN -> OpenDashboardWarning()
    }
}
