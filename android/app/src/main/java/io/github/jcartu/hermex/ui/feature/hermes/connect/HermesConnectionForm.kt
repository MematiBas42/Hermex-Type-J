package io.github.jcartu.hermex.ui.feature.hermes.connect

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.jcartu.hermex.di.AppContainer
import io.github.jcartu.hermex.model.ServerAccount
import io.github.jcartu.hermex.net.CustomHeader
import io.github.jcartu.hermex.net.hermes.HermesSignInMethod
import io.github.jcartu.hermex.net.hermes.HermesStatus
import io.github.jcartu.hermex.net.hermes.HermesStatusProbeFailure
import io.github.jcartu.hermex.ui.adaptive.HingeAwarePaneRow
import io.github.jcartu.hermex.ui.adaptive.LocalPaneCount
import io.github.jcartu.hermex.ui.feature.onboarding.ConnectFormStyle
import io.github.jcartu.hermex.ui.feature.onboarding.ConnectTextField
import io.github.jcartu.hermex.ui.feature.onboarding.CustomHeadersEditor
import io.github.jcartu.hermex.ui.feature.settings.AddServerDialog
import io.github.jcartu.hermex.ui.feature.settings.ServerAvatarBadge
import io.github.jcartu.hermex.ui.feature.settings.ServerIdentity
import io.github.jcartu.hermex.ui.feature.settings.ServerSwitcherMenu
import io.github.jcartu.hermex.ui.feature.settings.SettingsCard
import io.github.jcartu.hermex.ui.feature.settings.SettingsDivider
import io.github.jcartu.hermex.ui.feature.settings.SettingsFootnote
import io.github.jcartu.hermex.ui.feature.settings.SettingsInfoRow
import io.github.jcartu.hermex.ui.feature.settings.SettingsRoute

/** Port of `BotConnectionView` (`Features/Bots/BotConnectionView.swift`), also the signed-out server root. */
@Composable
fun HermesConnectionForm(
    container: AppContainer,
    account: ServerAccount,
    focusesPassword: Boolean = false,
    isRoot: Boolean = false,
    error: String? = null,
    onSaved: () -> Unit = {},
    onDone: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val owner = remember(account.id) { object : androidx.lifecycle.ViewModelStoreOwner {
        override val viewModelStore = androidx.lifecycle.ViewModelStore()
    } }
    val model: HermesConnectionViewModel = viewModel(viewModelStoreOwner = owner, factory = viewModelFactory {
        initializer {
            HermesConnectionViewModel(account, container.botConnectionStore, container.authManager, container.okHttpClient, error,
                discard = { old ->
                    container.discardHermesConnectionData(account.id, old.id)
                })
        }
    })
    DisposableEffect(owner) { onDispose { owner.viewModelStore.clear() } }
    val state by model.state.collectAsStateWithLifecycle()
    val browser = rememberHermesBrowserSignInPresenter()
    LaunchedEffect(state.savedRevision, state.removed) {
        if (state.savedRevision > 0) { onSaved(); if (!isRoot) onDone() }
        if (state.removed) onDone()
    }
    HermesConnectionFormContent(state, focusesPassword, isRoot, model::setAddress, model::setName,
        model::setUsername, model::setPassword, model::setHeaders, { model.connect(browser) },
        { model.connect(browser, replacingHost = true) }, model::checkStatus, model::remove, onDone, modifier)
}

/** State-only screen for deterministic composition tests; controls never cross a physical hinge. */
@Composable
internal fun HermesConnectionFormContent(
    state: HermesConnectionFormState,
    focusesPassword: Boolean,
    isRoot: Boolean,
    setAddress: (String) -> Unit,
    setName: (String) -> Unit,
    setUsername: (String) -> Unit,
    setPassword: (String) -> Unit,
    setHeaders: (List<CustomHeader>) -> Unit,
    connect: () -> Unit,
    replace: () -> Unit,
    checkStatus: () -> Unit,
    remove: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focus = remember { FocusRequester() }
    var confirmRemove by rememberSaveable { mutableStateOf(false) }
    val count = LocalPaneCount.current
    LaunchedEffect(focusesPassword, state.signInMethod) {
        if (focusesPassword && state.signInMethod == HermesSignInMethod.PASSWORD) focus.requestFocus()
    }
    val fields: @Composable () -> Unit = {
        Text("Connect to your dashboard", style = MaterialTheme.typography.titleLarge)
        Text("Use your Hermes dashboard sign-in.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        SettingsCard("Sign-in") {
            Text("Hermes address", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
            if (state.isServerSignIn) SelectionContainer { Text(state.address, modifier = Modifier.padding(vertical = 12.dp)) }
            else ConnectTextField(state.address, setAddress, "Hermes address", ConnectFormStyle.SETTINGS, enabled = !state.isConnecting,
                keyboardType = KeyboardType.Uri, modifier = Modifier.padding(vertical = 12.dp))
            when (state.signInMethod) {
                HermesSignInMethod.PASSWORD -> {
                    SettingsDivider()
                    Text("Username", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 12.dp))
                    ConnectTextField(state.username, setUsername, "Username", ConnectFormStyle.SETTINGS,
                        enabled = !state.isConnecting, modifier = Modifier.padding(vertical = 12.dp))
                    Text("Password", style = MaterialTheme.typography.labelMedium)
                    ConnectTextField(state.password, setPassword, "Password", ConnectFormStyle.SETTINGS,
                        enabled = !state.isConnecting, secure = true, modifier = Modifier.padding(vertical = 12.dp).focusRequester(focus),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = { if (state.canConnect) connect() }))
                }
                HermesSignInMethod.BROWSER -> SettingsFootnote("This server signs in through your browser.")
                HermesSignInMethod.OPEN -> OpenDashboardWarning()
            }
            state.errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 8.dp)) }
        }
        SettingsCard("Connection Headers") {
            CustomHeadersEditor(state.headers, setHeaders, ConnectFormStyle.SETTINGS, enabled = !state.isConnecting,
                modifier = Modifier.padding(vertical = 8.dp))
            state.headerRejection?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            state.accessPairWarning?.let { SettingsFootnote(it) }
            SettingsFootnote("Sent with every request to this Hermes host, for a proxy such as Cloudflare Access.")
            if (state.signInMethod == HermesSignInMethod.BROWSER) SettingsFootnote("The browser cannot send these headers. A proxy may ask you to sign in separately in the browser.")
        }
        Button(onClick = connect, enabled = state.canConnect, modifier = Modifier.fillMaxWidth()) {
            Text(if (state.isConnecting) "Connecting…" else if (state.signInMethod == HermesSignInMethod.BROWSER) "Sign In with Browser" else "Connect")
        }
        if (state.offersHostReplacement) {
            TextButton(onClick = replace) { Text("Connect to this host instead", color = MaterialTheme.colorScheme.error) }
            SettingsFootnote("Connecting to this host instead clears this connection’s drafts and cached chats on this device.")
        }
        if (count == 1) HermesHostStatusCard(state, checkStatus)
        SettingsCard("Connection name · optional") {
            ConnectTextField(state.name, setName, "Name", ConnectFormStyle.SETTINGS, enabled = !state.isConnecting,
                modifier = Modifier.padding(vertical = 12.dp))
        }
        if (state.offersRemoval) TextButton(onClick = { confirmRemove = true }, enabled = !state.isConnecting) {
            Text("Remove Hermes connection…", color = MaterialTheme.colorScheme.error)
        }
    }
    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        if (!isRoot) Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Done") }
            Text("Hermes connection", style = MaterialTheme.typography.titleMedium)
        }
        if (count >= 2) HingeAwarePaneRow(paneCount = count) { index, pane ->
            if (index == 0) ConnectionColumn(pane, fields)
            else ConnectionColumn(pane) {
                if (index == 1) HermesHostStatusCard(state, checkStatus)
                else {
                    Text("Connection security", style = MaterialTheme.typography.titleMedium)
                    Text("Sign-in details and connection headers are encrypted on this device. They are sent only to this Hermes host.")
                    if (state.signInMethod == HermesSignInMethod.OPEN) OpenDashboardWarning()
                }
            }
        } else ConnectionColumn(Modifier.fillMaxSize(), fields)
    }
    if (confirmRemove) AlertDialog(onDismissRequest = { confirmRemove = false }, title = { Text("Remove this connection from Hermex?") },
        text = { Text("Saved sign-in details and this connection’s drafts will be deleted. Bots and their work remain on the host.") },
        confirmButton = { TextButton(onClick = { confirmRemove = false; remove() }) { Text("Remove Hermes connection") } },
        dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Cancel") } })
}

@Composable
private fun ConnectionColumn(modifier: Modifier, content: @Composable () -> Unit) {
    Box(modifier, contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 520.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) { content() }
    }
}

/** Port of `BotConnectionView.statusSection`: public reachability, release, install and gateway state. */
@Composable
internal fun HermesHostStatusCard(state: HermesConnectionFormState, checkStatus: () -> Unit) {
    val live = state.liveStatus
    SettingsCard("Status") {
        val title = when (val status = state.hostStatus) {
            null -> "Not checked"
            HermesHostStatus.Checking -> "Checking…"
            is HermesHostStatus.Reachable -> "Reachable"
            is HermesHostStatus.Unreachable -> when (val failure = status.failure) {
                is HermesStatusProbeFailure.Unreachable -> "Can’t reach this address"
                HermesStatusProbeFailure.Blocked -> "An access check blocked this request"
                is HermesStatusProbeFailure.Answered -> "The host answered ${failure.status}"
                else -> "This address isn’t a Hermes dashboard"
            }
        }
        SettingsInfoRow("Reachability", title)
        (live?.version ?: state.saved?.hermesVersion)?.let { SettingsInfoRow("Hermes", it) }
        (live?.installID ?: state.saved?.installID)?.let { SettingsInfoRow("Install ID", it) }
        if (live != null) {
            SettingsInfoRow("Messaging gateway", live.gatewayTitle())
            live.gatewayNote()?.let { SettingsFootnote(it) }
            val configured = live.platformsConfigured
            val connected = live.platformsConnected
            if (configured != null && configured > 0 && connected != null) SettingsInfoRow("Platforms", "$connected of $configured connected")
        }
        TextButton(onClick = checkStatus, enabled = state.hostStatus != HermesHostStatus.Checking) { Text("Check again") }
    }
}

/** Port of `BotHostStatus.gatewayTitle/gatewayNote` (`BotConnectionView.swift`). */
internal fun HermesStatus.gatewayTitle(): String {
    val stopped = gatewayRunning == false || gatewayRunning == null && gatewayState in listOf("stopped", "startup_failed")
    if (heartbeatStale != null && !stopped) return "Not responding"
    return when (gatewayState ?: gatewayRunning?.let { if (it) "running" else "stopped" }) {
        "starting" -> "Starting"; "running" -> "Running"; "draining" -> "Draining"
        "degraded" -> "Degraded"; "startup_failed" -> "Startup failed"; "stopped" -> "Stopped"; else -> "Unknown"
    }
}
internal fun HermesStatus.gatewayNote(): String? {
    val stopped = gatewayRunning == false || gatewayRunning == null && gatewayState in listOf("stopped", "startup_failed")
    if (stopped) return listOfNotNull(gatewayExitReason?.takeIf { it.isNotEmpty() }, "Scheduled Tasks won’t run until it starts.").joinToString("\n")
    return if (heartbeatStale != null) "Scheduled Tasks may not run until it responds." else null
}

/** Port of `OpenDashboardWarning` (#1314). */
@Composable
fun OpenDashboardWarning() {
    Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(Icons.Filled.Warning, null, tint = MaterialTheme.colorScheme.tertiary)
        Text(HermesSignInMethod.OPEN_WARNING, style = MaterialTheme.typography.bodySmall)
    }
}

/** Port of `ContentView.HermesServerSignIn`: locked address, focused password, avatar as the way out. */
@Composable
fun HermesSignInRoute(container: AppContainer, account: ServerAccount) {
    var settings by rememberSaveable(account.id) { mutableStateOf(false) }
    var switcher by remember { mutableStateOf(false) }
    var adding by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).systemBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Hermes connection", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            Box {
                ServerAvatarBadge(ServerIdentity.previewInitials(account), account.headerLogoColorHex,
                    modifier = Modifier.combinedClickable(onClick = { settings = true }, onLongClick = { switcher = true }))
                ServerSwitcherMenu(switcher, { switcher = false }, { adding = true })
            }
        }
        HermesConnectionForm(container, account, focusesPassword = true, isRoot = true, error = container.authManager.lastErrorMessage.value,
            modifier = Modifier.weight(1f))
    }
    if (settings) Dialog(onDismissRequest = { settings = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).systemBarsPadding()) {
            TextButton(onClick = { settings = false }) { Text("Done") }
            SettingsRoute()
        }
    }
    if (adding) AddServerDialog(onDismiss = { adding = false })
}
