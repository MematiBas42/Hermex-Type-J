package io.github.jcartu.hermex.ui.feature.hermes.home

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import io.github.jcartu.hermex.model.ServerAccount
import io.github.jcartu.hermex.ui.feature.sessions.SessionsHeader
import io.github.jcartu.hermex.ui.feature.sessions.sessionAvatarInitials
import io.github.jcartu.hermex.ui.feature.settings.AddServerDialog
import io.github.jcartu.hermex.ui.feature.settings.HeaderLogoColor
import io.github.jcartu.hermex.ui.feature.settings.ServerSwitcherMenu

/** The home owns Settings navigation; a Bots list keeps its own search sheet and list state. */
val LocalHermesHomeSettings = staticCompositionLocalOf<() -> Unit> { {} }

/** Port of Features/Hermes/HermesHome.swift + HermesSessionListView's SessionsHeader. */
@Composable
fun HermesHomeHeader(
    account: ServerAccount,
    onOpenSearch: () -> Unit,
    modifier: Modifier = Modifier,
    isSearchExpanded: Boolean = false,
    searchText: String = "",
    onSearchTextChange: (String) -> Unit = {},
    onCloseSearch: () -> Unit = {},
) {
    var switches by remember { mutableStateOf(false) }
    var addsServer by remember { mutableStateOf(false) }
    val accent = HeaderLogoColor.color(account.headerLogoColorHex)
    SessionsHeader(
        logoColor = accent,
        avatarInitials = sessionAvatarInitials(account.displayName, account.initials, account.urlString.substringAfter("://").substringBefore("/")),
        avatarColor = accent,
        avatarForeground = if (HeaderLogoColor.prefersDarkForeground(account.headerLogoColorHex)) Color.Black else Color.White,
        isSearchExpanded = isSearchExpanded,
        searchText = searchText,
        onSearchTextChange = onSearchTextChange,
        onOpenSearch = onOpenSearch,
        onCloseSearch = onCloseSearch,
        onOpenSettings = LocalHermesHomeSettings.current,
        onSwitchServer = { switches = true },
        modifier = modifier,
        avatarMenu = { ServerSwitcherMenu(switches, { switches = false }, { switches = false; addsServer = true }) },
    )
    if (addsServer) AddServerDialog(onDismiss = { addsServer = false }, onAdded = { addsServer = false })
}
