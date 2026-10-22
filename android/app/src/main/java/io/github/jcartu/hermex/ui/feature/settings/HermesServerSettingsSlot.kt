package io.github.jcartu.hermex.ui.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Key
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.jcartu.hermex.HermexApplication
import io.github.jcartu.hermex.model.ServerAccount
import io.github.jcartu.hermex.net.hermes.HermesStatusProbe
import io.github.jcartu.hermex.ui.feature.hermes.connect.HermesConnectionForm
import io.github.jcartu.hermex.ui.feature.hermes.connect.HermesConnectionFormState
import io.github.jcartu.hermex.ui.feature.hermes.connect.HermesHostStatusCard
import io.github.jcartu.hermex.ui.feature.hermes.connect.OpenDashboardWarning

/**
 * Port of `SettingsServerPage.swift.hermesSections`: the surrounding server page already
 * supplies Identity, Sign Out and Remove. These sections use only the direct Hermes sign-in,
 * its public status, companion capabilities and retained update model (#1083).
 */
@Composable
fun HermesServerSettingsSlot(account: ServerAccount) {
    val context = LocalContext.current
    val container = remember(context) { (context.applicationContext as HermexApplication).container }
    val model: HermesServerSettingsViewModel = viewModel(key = "hermesSettings:${account.id}", factory = viewModelFactory {
        initializer { HermesServerSettingsViewModel(account, container.botConnectionStore, container.hermesConnections,
            container.authManager, HermesStatusProbe(container.okHttpClient)) }
    })
    val state by model.state.collectAsStateWithLifecycle()
    val updates = remember(account.id) { container.hermesUpdates(account.id) }
    val updateState by updates.state.collectAsStateWithLifecycle()
    val authState by container.authManager.state.collectAsStateWithLifecycle()
    var connectionOpen by rememberSaveable(account.id) { mutableStateOf(false) }
    LaunchedEffect(model, authState) { model.load() }
    LaunchedEffect(updates, state.signedIn) { if (state.signedIn) updates.appear() }
    DisposableEffect(updates) { onDispose { updates.leaveSettings() } }
    HermesServerSettingsContent(account, state, updateState, { connectionOpen = true }, model::load,
        updates::checkNow, updates::apply, updates::checkAgain)
    if (connectionOpen) Dialog(onDismissRequest = { connectionOpen = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).systemBarsPadding()) {
            HermesConnectionForm(container, account, onSaved = model::load, onDone = { connectionOpen = false })
        }
    }
}

/** Hoisted settings body, composed inside the existing hinge-aligned server page at pane counts 1/2/3. */
@Composable
internal fun HermesServerSettingsContent(
    account: ServerAccount,
    state: HermesServerSettingsState,
    update: HermesUpdateUiState,
    openConnection: () -> Unit,
    reload: () -> Unit,
    checkUpdate: () -> Unit,
    applyUpdate: () -> Unit,
    checkAgain: () -> Unit,
) {
    SettingsCard("Server") {
        SettingsInfoRow("URL", account.urlString)
        SettingsDivider()
        SettingsAccessoryRow("Hermes connection", Icons.Filled.Key, state.saved?.username, openConnection)
        (state.saved?.hermesVersion ?: account.serverVersion)?.let { SettingsInfoRow("Version", it) }
        if (!state.isActive) SettingsInfoRow("Status", "Inactive")
        if (state.saved?.hasNoCredentials == true) OpenDashboardWarning()
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 8.dp)) }
    }
    if (state.isActive) {
        HermesHostStatusCard(HermesConnectionFormState(true, account.urlString, saved = state.saved, hostStatus = state.hostStatus), reload)
        SettingsCard("Companion") {
            SettingsInfoRow("hermex-push companion", when (state.companionInstalled) {
                true -> "installed"; false -> "not installed"; null -> if (state.signedIn) "not checked" else "sign in to check"
            })
            state.companionVersion?.let { SettingsInfoRow("Version", it) }
            SettingsFootnote("The companion adds Git Fetch, Pull, New branch and Usage limits. Android does not use its APNs notification pairing.")
        }
        if (state.signedIn) HermesUpdateCallout(update, account.serverVersion, checkUpdate, applyUpdate, checkAgain)
    }
}

/** Port of `HermesUpdateCallout.swift`: host-authored guidance, explicit destructive confirmation, no invented success. */
@Composable
internal fun HermesUpdateCallout(
    state: HermesUpdateUiState,
    version: String?,
    check: () -> Unit,
    apply: () -> Unit,
    checkAgain: () -> Unit,
) {
    var confirming by rememberSaveable { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val card = updateCard(state, version)
    SettingsCard("Update Hermes") {
        Text(card.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(vertical = 8.dp))
        card.message?.let { SettingsFootnote(it) }
        card.command?.let { command ->
            SelectionContainer { Text(command, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(vertical = 8.dp)) }
            TextButton(onClick = { clipboard.setText(AnnotatedString(command)) }) { Text("Copy command") }
        }
        card.action?.let { action ->
            SettingsButtonRow(text = if (state.isChecking || state.isWorking) "Checking…" else when (action) {
                UpdateAction.CHECK -> "Check for updates"
                UpdateAction.UPDATE -> "Update…"
                UpdateAction.RETRY -> "Try again…"
                UpdateAction.CHECK_AGAIN -> "Check again"
            }, enabled = !state.isChecking && !state.isWorking, onClick = {
                when (action) {
                    UpdateAction.CHECK -> check()
                    UpdateAction.UPDATE, UpdateAction.RETRY -> confirming = true
                    UpdateAction.CHECK_AGAIN -> checkAgain()
                }
            })
        }
    }
    if (confirming) AlertDialog(onDismissRequest = { confirming = false }, title = { Text("Update Hermes?") },
        text = { Text("Running turns on this host stop and Hermes restarts. Hermex reconnects when it’s back. This can’t be undone.") },
        confirmButton = { TextButton(onClick = { confirming = false; apply() }) { Text("Update", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { confirming = false }) { Text("Cancel") } })
}

private enum class UpdateAction { CHECK, UPDATE, RETRY, CHECK_AGAIN }
private data class UpdateCard(val title: String, val message: String? = null, val command: String? = null, val action: UpdateAction? = null)
private fun updateCard(ui: HermesUpdateUiState, version: String?): UpdateCard {
    val updating = UpdateCard("Updating…", ui.check?.behind?.takeIf { it > 0 }?.let { "Hermes is installing $it commits on your host." }
        ?: "Hermes is installing the update on your host.")
    val noSummary = "Hermes didn’t say what went wrong. Its update log on the host has the details."
    when (val run = ui.run) {
        HermesUpdateUiState.Run.Starting -> return updating
        is HermesUpdateUiState.Run.Refused -> return UpdateCard("Update available", run.message, run.command)
        is HermesUpdateUiState.Run.CouldNotStart -> return UpdateCard("Update failed", run.message, action = UpdateAction.RETRY)
        is HermesUpdateUiState.Run.Following -> return when (val state = run.machine.state) {
            HermesUpdateMachine.State.Applying -> updating
            HermesUpdateMachine.State.Recovering -> UpdateCard("Restarting Hermes…", "Waiting for Hermes to come back")
            is HermesUpdateMachine.State.Done -> UpdateCard("Updated to ${state.version ?: version.orEmpty()}", "Hermes is back and Hermex reconnected.")
            is HermesUpdateMachine.State.Partial -> UpdateCard("Partly updated", state.summary ?: noSummary, action = UpdateAction.CHECK)
            is HermesUpdateMachine.State.Failed -> UpdateCard("Update failed", state.summary ?: noSummary, action = UpdateAction.RETRY)
            is HermesUpdateMachine.State.NeedsDashboardRestart -> UpdateCard("Restart the dashboard on the host",
                state.running?.let { "The update finished, but Hermes still runs $it. Run this on your host, then check again." }
                    ?: "The update finished, but Hermes hasn’t answered for 2 minutes. Run this on your host, then check again.",
                "hermes dashboard", UpdateAction.CHECK_AGAIN)
            HermesUpdateMachine.State.StillRunning -> UpdateCard("Updating…", "Hermes is still updating after 10 minutes. Check again later.", action = UpdateAction.CHECK_AGAIN)
        }
        null -> Unit
    }
    ui.checkError?.let { return UpdateCard("Couldn't check for updates", it, action = UpdateAction.CHECK) }
    val check = ui.check ?: return UpdateCard(if (ui.isChecking) "Checking…" else "Updates", action = UpdateAction.CHECK)
    if (check.updateAvailable) {
        val behind = check.behind?.takeIf { it > 0 }?.let { "$it commits behind." } ?: "A newer Hermes is available."
        return if (check.canApply) UpdateCard("Update available", behind, action = UpdateAction.UPDATE)
        else UpdateCard("Update available", "$behind This install updates on your host, not from Hermex. Run:", check.hostCommand)
    }
    if (check.behind == 0) return UpdateCard("Up to date", "Hermes ${version ?: check.currentVersion.orEmpty()} is current.", action = UpdateAction.CHECK)
    return if (!check.canApply) UpdateCard("Update Hermes on your host", check.message, check.hostCommand, UpdateAction.CHECK)
    else UpdateCard("Couldn't check for updates", check.message, action = UpdateAction.CHECK)
}
