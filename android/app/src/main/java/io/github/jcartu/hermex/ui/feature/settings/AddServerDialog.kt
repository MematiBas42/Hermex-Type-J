package io.github.jcartu.hermex.ui.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.jcartu.hermex.HermexApplication
import io.github.jcartu.hermex.model.ServerAccount
import io.github.jcartu.hermex.ui.feature.onboarding.ConnectFormEntry
import io.github.jcartu.hermex.ui.feature.onboarding.ConnectFormStyle
import io.github.jcartu.hermex.ui.feature.onboarding.ConnectTextField
import io.github.jcartu.hermex.ui.feature.onboarding.ConnectionMode
import io.github.jcartu.hermex.ui.feature.onboarding.ConnectionModePicker
import io.github.jcartu.hermex.ui.feature.onboarding.CustomHeadersEditor
import io.github.jcartu.hermex.ui.feature.onboarding.OnboardingViewModel
import java.net.URI
import androidx.compose.material3.AlertDialog
import io.github.jcartu.hermex.ui.feature.onboarding.ConnectFormMoveSource
import io.github.jcartu.hermex.ui.feature.onboarding.HermesConnectMethodFields
import io.github.jcartu.hermex.ui.feature.hermes.connect.rememberHermesBrowserSignInPresenter
import io.github.jcartu.hermex.ui.adaptive.HingeAwarePaneRow
import io.github.jcartu.hermex.ui.adaptive.LocalPaneCount

/**
 * Port of `AddServerView.swift`: onboarding's connect form (`OnboardingViewModel`,
 * #900) in a full-window dialog, plus the new server's identity. A webui address
 * goes through `AuthManager.addServer`, which never disturbs the active server on
 * failure (#17). Presented from Settings and from the avatar's long-press
 * switcher (#283). Fills the window at every pane count; the form column is
 * capped so it never stretches full-bleed on a tablet or the unfolded Trifold.
 */
@Composable
fun AddServerDialog(onDismiss: () -> Unit, onAdded: (ServerAccount) -> Unit = {}, moveSource: ConnectFormMoveSource? = null) {
    val context = LocalContext.current
    val container = remember(context) { (context.applicationContext as HermexApplication).container }
    // The form lives exactly as long as the dialog: a fresh store per open, cleared on dismiss,
    // so a previous add's result or typed address never greets the next one.
    val formOwner = remember {
        object : androidx.lifecycle.ViewModelStoreOwner {
            override val viewModelStore = androidx.lifecycle.ViewModelStore()
        }
    }
    androidx.compose.runtime.DisposableEffect(formOwner) {
        onDispose { formOwner.viewModelStore.clear() }
    }
    val form: OnboardingViewModel = viewModel(
        viewModelStoreOwner = formOwner,
        factory = viewModelFactory {
            initializer { OnboardingViewModel(authManager = container.authManager, entry = ConnectFormEntry.ADD_SERVER,
                hermesHttpClient = container.okHttpClient, moveSource = moveSource) }
        },
    )
    val state by form.uiState.collectAsStateWithLifecycle()
    var displayName by rememberSaveable { mutableStateOf("") }
    var initials by rememberSaveable { mutableStateOf("") }
    var colorHex by rememberSaveable { mutableStateOf(HeaderLogoColor.defaultHex) }

    val canSubmit = state.canSubmit && !state.isWorking && !state.isConnectionLocked
    val browser = rememberHermesBrowserSignInPresenter()
    val submit = { if (canSubmit) form.connect(browser = browser) }
    var confirmReplace by rememberSaveable { mutableStateOf(false) }

    // Port of `applyIdentity(to:)`: the registry seeds the new server from the
    // previous active server's defaults; the chosen values override them.
    LaunchedEffect(state.addedAccount) {
        val added = state.addedAccount ?: return@LaunchedEffect
        val host = runCatching { URI(added.urlString).host }.getOrNull().orEmpty()
        val finalName = displayName.trim().ifEmpty { added.displayName.ifEmpty { host } }
        val finalInitials = ServerIdentity.displayInitials(
            displayName = finalName,
            storedInitials = initials.ifEmpty { added.initials },
            fallbackFullName = host.ifEmpty { finalName },
        )
        container.authManager.updateServerIdentity(added, finalName, finalInitials,
            if (displayName.isEmpty() && initials.isEmpty() && colorHex == HeaderLogoColor.defaultHex) added.headerLogoColorHex else colorHex)
        onAdded(container.authManager.servers.value.firstOrNull { it.id == added.id } ?: added)
        onDismiss()
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .systemBarsPadding(),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
            ) {
                IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = "Cancel") }
                Text(
                    "Add Server",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                if (state.isWorking) {
                    CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.padding(end = 12.dp).widthIn(max = 18.dp))
                }
                TextButton(onClick = submit, enabled = canSubmit) { Text(state.submitTitle) }
            }

            HingeAwarePaneRow(paneCount = LocalPaneCount.current) { pane, paneModifier ->
              if (pane == 0) Box(paneModifier, contentAlignment = Alignment.TopCenter) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                    modifier = Modifier
                        .widthIn(max = 520.dp)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp)
                        .padding(top = 12.dp, bottom = 36.dp),
                ) {
                    SettingsCard(title = "Network") {
                        ConnectionModePicker(
                            selection = state.connectionMode,
                            onSelect = form::setConnectionMode,
                            style = ConnectFormStyle.SETTINGS,
                            enabled = !state.isConnectionLocked,
                        )
                        SettingsFootnote(state.connectionMode.help)
                    }

                    SettingsCard(title = "Server") {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                            Text("URL", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            ConnectTextField(
                                value = state.serverUrl,
                                onValueChange = form::onServerUrlChange,
                                placeholder = state.connectionMode.placeholder,
                                style = ConnectFormStyle.SETTINGS,
                                enabled = !state.isConnectionLocked,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go, autoCorrectEnabled = false),
                                keyboardActions = KeyboardActions(onGo = { submit() }),
                            )
                            // The trailing mark keeps a URL ending in a neutral character, such as an
                            // IPv6 literal's "]", in one left-to-right run inside right-to-left text.
                            state.addressPreview?.let { preview ->
                                Text(
                                    "Will connect to $preview\u200E",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            HermesConnectMethodFields(state, ConnectFormStyle.SETTINGS, form::onUsernameChange, form::useSavedSignIn)
                            if (state.showsPasswordField) {
                                SettingsDivider()
                                Text("Password", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                ConnectTextField(
                                    value = state.password,
                                    onValueChange = form::onPasswordChange,
                                    placeholder = "Server password",
                                    style = ConnectFormStyle.SETTINGS,
                                    enabled = !state.isConnectionLocked,
                                    secure = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Go, autoCorrectEnabled = false),
                                    keyboardActions = KeyboardActions(onGo = { submit() }),
                                )
                            }
                        }
                    }

                    // What the last Add found, under the address it is about.
                    when {
                        state.isWorking -> SettingsFootnote("Checking server…")
                        state.showsPasswordField && state.errorMessage == null ->
                            SettingsFootnote("This server requires a password.")
                    }
                    state.errorMessage?.let { message ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                            Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                    }
                    if (state.offersWebuiReplace) TextButton(onClick = { confirmReplace = true }) { Text("Replace webui server") }
                    state.headerRejection?.let { Text(it, color = MaterialTheme.colorScheme.error) }

                    SettingsCard(title = "Connection Headers") {
                        if (state.offersMoveSourceHeaders) TextButton(onClick = form::useMoveSourceHeaders) {
                            Text("Use ${state.moveSource?.name}'s Headers")
                        }
                        if (state.connectionMode == ConnectionMode.CLOUDFLARE_TUNNEL) {
                            SettingsFootnote("Cloudflare Access: paste your service token’s Client ID and Client Secret as the values. Leave both empty if Access is off.")
                        }
                        CustomHeadersEditor(
                            headers = state.customHeaders,
                            onChange = form::onCustomHeadersChange,
                            style = ConnectFormStyle.SETTINGS,
                            enabled = !state.isConnectionLocked,
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                    }

                    SettingsCard(title = "Identity") {
                        ServerIdentityEditor(
                            displayName = displayName,
                            initials = initials,
                            colorHex = colorHex,
                            fallbackName = state.addressPreview?.let { runCatching { URI(it).host }.getOrNull() }.orEmpty(),
                            onDisplayNameChange = { displayName = it },
                            onInitialsChange = { initials = it },
                            onColorHexChange = { colorHex = it },
                        )
                        SettingsFootnote("Hermes hosts are detected first. Existing webui servers are marked Legacy.")
                    }
                }
            }
              else Column(paneModifier.padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                  Text(if (pane == 1) "Connect directly to Hermes" else "Your sign-in stays private", style = MaterialTheme.typography.titleLarge)
                  Text(if (pane == 1) state.connectionMode.help else "Sign-in details and connection headers are encrypted on this device and sent only to the host you select.")
              }
            }
        }
    }
    if (confirmReplace) AlertDialog(onDismissRequest = { confirmReplace = false }, title = { Text("Replace webui server?") },
        text = { Text("Removes the saved webui sign-in, drafts and cache on this device. Its name, initials and color are kept for Hermes.") },
        confirmButton = { TextButton(onClick = { confirmReplace = false; form.connect(replacingWebuiServer = true, browser = browser) }) { Text("Replace") } },
        dismissButton = { TextButton(onClick = { confirmReplace = false }) { Text("Cancel") } })
}
