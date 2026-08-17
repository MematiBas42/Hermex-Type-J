package io.github.jcartu.hermex.ui.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jcartu.hermex.model.ProviderSummary
import io.github.jcartu.hermex.ui.components.EmptyState
import io.github.jcartu.hermex.ui.components.ErrorState
import io.github.jcartu.hermex.ui.components.LoadingState

/**
 * Read-only provider status screen, port of `ProvidersView.swift` (#26): which
 * providers the server knows about, whether each has a credential (and where
 * it came from), which one is active, and each provider's model catalog.
 * Deliberately carries no write affordances.
 */
@Composable
internal fun ProvidersPane(
    viewModel: ProvidersViewModel,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    // Expansion keyed by the provider's stable id, so refreshes that reorder
    // the list keep the right rows expanded; survives pane-count changes.
    // (Stored as a List: the default saveable saver requires Bundle-supported types.)
    var expandedKeys by rememberSaveable { mutableStateOf(listOf<String>()) }

    Column(modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
            IconButton(onClick = onClose) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text(
                "Providers",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }

        when {
            uiState.isLoading && uiState.providers.isEmpty() ->
                LoadingState(label = "Loading providers")
            uiState.errorMessage != null && uiState.providers.isEmpty() -> ErrorState(
                message = uiState.errorMessage.orEmpty(),
                onRetry = viewModel::load,
            )
            uiState.providers.isEmpty() -> EmptyState(
                title = "No providers",
                message = "The server did not report any model providers.",
            )
            else -> LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                val providers = uiState.providers
                items(
                    count = providers.size,
                    key = { index -> providers[index].id ?: "provider-$index" },
                ) { index ->
                    val provider = providers[index]
                    val expansionKey = provider.id ?: "provider-$index"
                    ProviderRow(
                        provider = provider,
                        isActive = viewModel.isActive(provider),
                        isExpanded = expansionKey in expandedKeys,
                        onToggleExpanded = {
                            expandedKeys = if (expansionKey in expandedKeys) {
                                expandedKeys - expansionKey
                            } else {
                                expandedKeys + expansionKey
                            }
                        },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun ProviderRow(
    provider: ProviderSummary,
    isActive: Boolean,
    isExpanded: Boolean,
    onToggleExpanded: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        ProvidersViewModel.displayName(provider),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (isActive) {
                        SettingsStatusPill(label = "Active", healthy = true)
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val hasKey = provider.hasKey == true
                    Text(
                        if (hasKey) "Key configured" else "No key",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    ProvidersViewModel.keySourceBadge(provider)?.let { badge ->
                        Text(
                            badge,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .background(
                                    MaterialTheme.colorScheme.surfaceContainerHigh,
                                    CircleShape,
                                )
                                .padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    }
                }

                ProvidersViewModel.authErrorText(provider)?.let { authError ->
                    Text(
                        authError,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            val modelCount = ProvidersViewModel.modelCount(provider)
            if (modelCount > 0) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable(onClick = onToggleExpanded),
                ) {
                    Text(
                        "$modelCount models",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(2.dp))
                    Icon(
                        if (isExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = if (isExpanded) "Collapse models" else "Expand models",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }

        if (isExpanded) {
            val models = provider.models.orEmpty()
            Column(Modifier.padding(top = 8.dp, start = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                models.forEach { model ->
                    val title = model.label?.takeIf { it.isNotBlank() }
                        ?: model.id?.takeIf { it.isNotBlank() }
                    if (title != null) {
                        Text(
                            title,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                ProvidersViewModel.truncatedModelInfo(provider)?.let { (shown, total) ->
                    Text(
                        "Showing $shown of $total models",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
