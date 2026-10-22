package io.github.jcartu.hermex.ui.feature.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Textsms
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.jcartu.hermex.HermexApplication
import io.github.jcartu.hermex.di.AppContainer
import io.github.jcartu.hermex.model.ServerAccount
import io.github.jcartu.hermex.model.ServerKind
import io.github.jcartu.hermex.ui.adaptive.HingeAwarePaneRow
import io.github.jcartu.hermex.ui.adaptive.LocalPaneCount
import io.github.jcartu.hermex.ui.components.EmptyState

/**
 * Settings (`SettingsView.swift`, #1252/#1255): opens on a short index — the
 * configured servers (active marked, webui ones Legacy, #1239), then
 * Appearance, Composer, Chat, Notifications, General, About. Each server's
 * settings live on its own page. Adaptive: one pane pushes a page over the
 * index with back; two panes show index | page with the boundary on the hinge;
 * three panes add a detail pane (Providers / Connection Headers) for a server page.
 */
@Composable
fun SettingsRoute(onOpenArchived: () -> Unit = {}) {
    val context = LocalContext.current
    val container = remember(context) { (context.applicationContext as HermexApplication).container }
    val viewModel: SettingsViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                SettingsViewModel(
                    settingsRepository = container.settings,
                    authManager = container.authManager,
                    appLock = container.appLock,
                )
            }
        },
    )
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    SettingsScreen(container, viewModel, uiState, onOpenArchived)
}

/** The route's body, with its state hoisted so a test can compose it at any pane count. */
@Composable
internal fun SettingsScreen(
    container: AppContainer,
    viewModel: SettingsViewModel,
    uiState: SettingsUiState,
    onOpenArchived: () -> Unit,
) {
    val paneCount = LocalPaneCount.current
    val page = uiState.page
    val detail = uiState.detail

    when {
        paneCount <= 1 && detail != null && page is SettingsPage.Server -> {
            BackHandler(onBack = viewModel::closeDetail)
            SettingsDetailPane(container, viewModel, page, detail, Modifier.fillMaxSize())
        }
        paneCount <= 1 && page != null -> {
            BackHandler(onBack = viewModel::closePage)
            SettingsPagePane(container, viewModel, uiState, page, onOpenArchived, showsBack = true, modifier = Modifier.fillMaxSize())
        }
        paneCount <= 1 -> SettingsIndex(viewModel, uiState, Modifier.fillMaxSize())
        paneCount == 2 -> HingeAwarePaneRow(paneCount = 2) { index, paneModifier ->
            when (index) {
                0 -> SettingsIndex(viewModel, uiState, paneModifier)
                else -> when {
                    detail != null && page is SettingsPage.Server -> {
                        BackHandler(onBack = viewModel::closeDetail)
                        SettingsDetailPane(container, viewModel, page, detail, paneModifier)
                    }
                    page != null -> SettingsPagePane(container, viewModel, uiState, page, onOpenArchived, showsBack = false, modifier = paneModifier)
                    else -> EmptyState(title = "Settings", message = "Choose a server or a page.", modifier = paneModifier)
                }
            }
        }
        else -> HingeAwarePaneRow(paneCount = 3) { index, paneModifier ->
            when (index) {
                0 -> SettingsIndex(viewModel, uiState, paneModifier)
                1 -> if (page != null) {
                    SettingsPagePane(container, viewModel, uiState, page, onOpenArchived, showsBack = false, modifier = paneModifier)
                } else {
                    EmptyState(title = "Settings", message = "Choose a server or a page.", modifier = paneModifier)
                }
                else -> if (detail != null && page is SettingsPage.Server) {
                    SettingsDetailPane(container, viewModel, page, detail, paneModifier)
                } else {
                    EmptyState(
                        title = "Nothing selected",
                        message = "Open Providers or Connection Headers on a server page to inspect them here.",
                        modifier = paneModifier,
                    )
                }
            }
        }
    }

    if (uiState.isAddingServer) {
        AddServerDialog(
            onDismiss = viewModel::dismissAddServer,
            onAdded = { added -> viewModel.openPage(SettingsPage.Server(added.id)) },
        )
    }
}

// MARK: - Index

private data class IndexEntry(val page: SettingsPage, val title: String, val icon: ImageVector)

private val appEntries = listOf(
    IndexEntry(SettingsPage.Appearance, "Appearance", Icons.Filled.Palette),
    IndexEntry(SettingsPage.Composer, "Composer", Icons.Filled.Keyboard),
    IndexEntry(SettingsPage.Chat, "Chat", Icons.Filled.Textsms),
    IndexEntry(SettingsPage.Notifications, "Notifications", Icons.Filled.Notifications),
    IndexEntry(SettingsPage.General, "General", Icons.Filled.Settings),
)

@Composable
private fun SettingsIndex(viewModel: SettingsViewModel, uiState: SettingsUiState, modifier: Modifier = Modifier) {
    val listState = rememberSaveable(saver = LazyListState.Saver, key = "settingsIndex") { LazyListState() }
    Box(modifier, contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            state = listState,
            verticalArrangement = Arrangement.spacedBy(18.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 18.dp),
            modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth(),
        ) {
            item("servers") {
                SettingsCard(title = "Servers") {
                    uiState.servers.forEachIndexed { index, account ->
                        if (index > 0) SettingsDivider()
                        ServerRow(
                            account = account,
                            isActive = account.id == uiState.activeServerId,
                            isSelected = uiState.page == SettingsPage.Server(account.id),
                            onClick = { viewModel.openPage(SettingsPage.Server(account.id)) },
                        )
                    }
                    if (uiState.servers.isNotEmpty()) SettingsDivider()
                    SettingsAccessoryRow(title = "Add Server", icon = Icons.Filled.AddCircleOutline, onClick = viewModel::openAddServer)
                }
            }
            item("hermex") {
                SettingsCard(title = "Hermex") {
                    appEntries.forEachIndexed { index, entry ->
                        if (index > 0) SettingsDivider()
                        val value = when (entry.page) {
                            SettingsPage.Appearance -> uiState.settings.theme.title
                            SettingsPage.Notifications -> if (uiState.settings.backgroundRunNotificationsEnabled) "Alerts on" else "Alerts off"
                            else -> null
                        }
                        IndexRow(entry.title, entry.icon, value, isSelected = uiState.page == entry.page) {
                            viewModel.openPage(entry.page)
                        }
                    }
                    SettingsFootnote("Applies on this device to every server.")
                }
            }
            item("about") {
                SettingsCard(title = "About") {
                    IndexRow("About Hermex-Type-J", Icons.Filled.Info, null, isSelected = uiState.page == SettingsPage.About) {
                        viewModel.openPage(SettingsPage.About)
                    }
                }
            }
        }
    }
}

@Composable
private fun IndexRow(title: String, icon: ImageVector, value: String?, isSelected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.background(
            if (isSelected) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f) else MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        SettingsAccessoryRow(title = title, icon = icon, value = value, onClick = onClick)
    }
}

/** A server on the index (`SettingsServerRow`): avatar, name, then its kind and host; the active one carries a checkmark. */
@Composable
private fun ServerRow(account: ServerAccount, isActive: Boolean, isSelected: Boolean, onClick: () -> Unit) {
    val name = ServerIdentity.displayName(account)
    val detail = (if (account.kind == ServerKind.HERMES) "Hermes" else "webui") + " · " + ServerIdentity.hostFallback(account)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .background(if (isSelected) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f) else MaterialTheme.colorScheme.surfaceContainer)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
    ) {
        ServerAvatarBadge(initials = ServerIdentity.previewInitials(account), colorHex = account.headerLogoColorHex)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (account.kind == ServerKind.WEBUI) {
                    // webui servers are legacy (#1239): they keep working; new features go to Hermes.
                    Text(
                        "Legacy",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.12f), CircleShape)
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
            }
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (isActive) {
            Icon(Icons.Filled.Check, contentDescription = "Active server", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
        }
    }
}

// MARK: - Pages

@Composable
private fun SettingsPagePane(
    container: AppContainer,
    viewModel: SettingsViewModel,
    uiState: SettingsUiState,
    page: SettingsPage,
    onOpenArchived: () -> Unit,
    showsBack: Boolean,
    modifier: Modifier = Modifier,
) {
    val title = when (page) {
        is SettingsPage.Server -> uiState.servers.firstOrNull { it.id == page.id }?.let(ServerIdentity::displayName) ?: "Server"
        SettingsPage.Appearance -> "Appearance"
        SettingsPage.Composer -> "Composer"
        SettingsPage.Chat -> "Chat"
        SettingsPage.Notifications -> "Notifications"
        SettingsPage.General -> "General"
        SettingsPage.About -> "About Hermex-Type-J"
    }
    Column(modifier) {
        PageHeader(title, showsBack, onBack = viewModel::closePage)
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(
                verticalArrangement = Arrangement.spacedBy(18.dp),
                modifier = Modifier
                    .widthIn(max = 480.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 18.dp),
            ) {
                when (page) {
                    is SettingsPage.Server -> ServerPage(container, viewModel, uiState, page.id, onOpenArchived)
                    SettingsPage.Appearance -> AppearancePage(viewModel, uiState)
                    SettingsPage.Composer -> ComposerPage(viewModel, uiState)
                    SettingsPage.Chat -> ChatPage(viewModel, uiState)
                    SettingsPage.Notifications -> NotificationsPage(viewModel, uiState, container.settings)
                    SettingsPage.General -> GeneralPage(viewModel, uiState)
                    SettingsPage.About -> AboutPage()
                }
            }
        }
    }
}

@Composable
private fun ServerPage(
    container: AppContainer,
    viewModel: SettingsViewModel,
    uiState: SettingsUiState,
    serverId: String,
    onOpenArchived: () -> Unit,
) {
    val page: ServerPageViewModel = viewModel(
        key = "settingsServer:$serverId",
        factory = viewModelFactory {
            initializer {
                ServerPageViewModel(
                    accountId = serverId,
                    api = container.api,
                    authManager = container.authManager,
                    cacheStore = container.cacheStore,
                )
            }
        },
    )
    val state by page.uiState.collectAsStateWithLifecycle()
    SettingsServerPageContent(page, state, viewModel, uiState, onOpenArchived)
}

@Composable
private fun SettingsDetailPane(
    container: AppContainer,
    viewModel: SettingsViewModel,
    page: SettingsPage.Server,
    detail: SettingsDetail,
    modifier: Modifier = Modifier,
) {
    when (detail) {
        SettingsDetail.PROVIDERS -> {
            val providers: ProvidersViewModel = viewModel(
                key = "settingsProviders:${page.id}",
                factory = viewModelFactory { initializer { ProvidersViewModel(container.api) } },
            )
            LaunchedEffect(providers) { providers.load() }
            ProvidersPane(viewModel = providers, onClose = viewModel::closeDetail, modifier = modifier)
        }
        SettingsDetail.CONNECTION_HEADERS -> {
            val serverUrl = container.authManager.servers.value.firstOrNull { it.id == page.id }?.urlString ?: page.id
            val headers: CustomHeadersViewModel = viewModel(
                key = "settingsHeaders:${page.id}",
                factory = viewModelFactory { initializer { CustomHeadersViewModel(container.customHeaders, serverUrl) } },
            )
            val state by headers.uiState.collectAsStateWithLifecycle()
            Box(modifier) {
                CustomHeadersDialog(
                    state = state,
                    validate = headers::validate,
                    onSave = headers::save,
                    onDismiss = {
                        headers.consumeSaved()
                        viewModel.closeDetail()
                    },
                )
            }
        }
    }
}

@Composable
private fun PageHeader(title: String, showsBack: Boolean, onBack: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
    ) {
        if (showsBack) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to Settings") }
        } else {
            Spacer(Modifier.width(12.dp))
        }
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
