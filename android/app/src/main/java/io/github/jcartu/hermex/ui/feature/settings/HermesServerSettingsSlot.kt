package io.github.jcartu.hermex.ui.feature.settings

import androidx.compose.runtime.Composable
import io.github.jcartu.hermex.model.ServerAccount

// =============================================================================
// WAVE 2 SLOT — Hermes server settings (Contract C, `HermesServerSettingsSlot`).
//
// `SettingsServerPage` renders this composable instead of the webui sections for
// every `ServerKind.HERMES` account, and makes NO webui request for it. Wave 2
// (HermesOnboardingConnect) replaces the body of this function with the port of
// `SettingsServerPage.swift` `hermesSections(server:)`: the saved sign-in
// (`BotConnectionView`), its headers, the release saved at that sign-in, the
// host's update standing (`HermesUpdateCallout`, #1075) and Archived Sessions.
// Keep the signature; the page passes the account only.
// =============================================================================

/** The Hermes server's page sections (Wave 1: what this device saved for it). */
@Composable
fun HermesServerSettingsSlot(account: ServerAccount) {
    SettingsCard(title = "Server") {
        SettingsInfoRow(title = "URL", value = account.urlString)
        SettingsDivider()
        SettingsInfoRow(title = "Kind", value = "Hermes")
        account.serverVersion?.let { version ->
            SettingsDivider()
            SettingsInfoRow(title = "Version", value = version)
        }
        SettingsFootnote("A Hermes host signs in from its own sign-in form; its saved sign-in, headers and release show here.")
    }
}

// ============================== END WAVE 2 SLOT ==============================
