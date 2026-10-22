package io.github.jcartu.hermex.ui.feature.tasks

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.jcartu.hermex.model.ModelCatalogGroup
import io.github.jcartu.hermex.model.ModelCatalogOption
import io.github.jcartu.hermex.model.ProfileSummary
import io.github.jcartu.hermex.model.firstMatchingSelection
import io.github.jcartu.hermex.ui.components.EmptyState
import io.github.jcartu.hermex.ui.components.ErrorState
import io.github.jcartu.hermex.ui.components.LoadingState
import io.github.jcartu.hermex.ui.feature.models.PickerSheetScaffold

/**
 * Resolves what the Task editor's Model row shows for a draft.
 * Port of `Features/Tasks/CronJobConfigurationPickers.swift` `CronJobModelSelection`.
 */
object CronJobModelSelection {
    /**
     * The option the row renders, or `null` for "Server default".
     *
     * A model the catalog no longer offers resolves to itself rather than to
     * nothing, so a task configured last month against a since-removed model
     * keeps showing which model that was instead of quietly reading as
     * unconfigured.
     */
    fun resolve(
        modelID: String,
        providerID: String,
        groups: List<ModelCatalogGroup>,
    ): ModelCatalogOption? {
        val resolvedID = nonEmpty(modelID) ?: return null
        val resolvedProvider = nonEmpty(providerID)

        return groups
            .flatMap { it.slashAutocompleteModels }
            .firstMatchingSelection(modelID = resolvedID, providerID = resolvedProvider)
            ?: ModelCatalogOption(id = resolvedID, displayName = resolvedID, providerID = resolvedProvider)
    }

    fun nonEmpty(value: String): String? = value.trim().takeIf { it.isNotEmpty() }
}

/**
 * The Task editor's Model row: the current model and provider, or "Server
 * default" when the draft leaves both blank.
 */
@Composable
fun CronJobModelRow(
    selection: ModelCatalogOption?,
    action: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        Modifier
            .then(modifier)
            .fillMaxWidth()
            .clickable(onClick = action)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Model",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Column(horizontalAlignment = Alignment.End) {
            Text(
                selection?.displayName ?: "Server default",
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
            )
            selection?.providerID?.let { provider ->
                Text(
                    provider,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
        Spacer(Modifier.width(6.dp))
        CronJobPickerChevron()
    }
}

/**
 * The Task editor's Profile row, plus the inline load failure and retry that
 * replace it when `/api/profiles` will not load. Free text is not an option
 * here: upstream 400s an unknown profile name. A locked row only names the
 * Profile: a Hermes host can't move an existing Task to another (#1040).
 */
@Composable
fun CronJobProfileRow(
    profileName: String,
    profiles: List<ProfileSummary>,
    errorMessage: String?,
    isLoading: Boolean,
    isLocked: Boolean,
    action: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val title = profileTitle(profileName, profiles)
    Column(Modifier.then(modifier).fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(enabled = !isLocked, onClick = action)
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Profile",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
            )
            if (!isLocked) {
                Spacer(Modifier.width(6.dp))
                CronJobPickerChevron()
            }
        }

        // The saved value keeps showing above; only the list is missing.
        if (errorMessage != null && !isLoading) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    errorMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onRetry) { Text("Try Again") }
            }
        }
    }
}

private fun profileTitle(profileName: String, profiles: List<ProfileSummary>): String {
    val name = CronJobModelSelection.nonEmpty(profileName) ?: return "Server default"
    return profiles.firstOrNull { it.normalizedName == name }?.displayName ?: name
}

/** Trailing affordance on a row that opens a sheet rather than pushing. */
@Composable
fun CronJobPickerChevron() {
    Icon(
        Icons.Filled.UnfoldMore,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.outline,
        modifier = Modifier.width(16.dp),
    )
}

/**
 * The Task editor's profile picker. Presentation follows Settings > Default
 * Profile (body rows, filled selection pill), but this one only writes to the
 * editor's draft — it never switches the server's active profile.
 * Port of `CronJobProfilePickerSheet`.
 */
@Composable
fun CronJobProfilePickerSheet(
    profiles: List<ProfileSummary>,
    selectedProfileName: String,
    isLoading: Boolean,
    errorMessage: String?,
    onRetry: () -> Unit,
    onSelect: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var searchText by rememberSaveable { mutableStateOf("") }
    val query = searchText.trim()

    val listed = listedProfiles(profiles, selectedProfileName = selectedProfileName, query = query)
    val selectedName = CronJobModelSelection.nonEmpty(selectedProfileName)

    PickerSheetScaffold(title = "Task Profile", onDismiss = onDismiss) {
        if (query.isEmpty()) {
            ServerDefaultRow(
                selected = selectedName == null,
                onSelect = {
                    onSelect(null)
                    onDismiss()
                },
            )
        }

        if (profiles.isEmpty() && isLoading) {
            LoadingState(label = "Loading profiles...")
        } else if (profiles.isEmpty() && errorMessage != null) {
            ErrorState(message = errorMessage, retryLabel = "Try Again", onRetry = onRetry)
        } else if (listed.isEmpty() && query.isNotEmpty()) {
            EmptyState(title = "No Results", message = "No profiles match \"$query\".")
        } else if (profiles.isEmpty()) {
            EmptyState(title = "No profiles available", message = "")
        } else {
            LazyColumn {
                items(listed.size) { index ->
                    val profile = listed[index]
                    val name = profile.normalizedName
                    ProfileRow(
                        profile = profile,
                        selected = name != null && name == selectedName,
                        enabled = name != null,
                        onSelect = {
                            name?.let {
                                onSelect(it)
                                onDismiss()
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ServerDefaultRow(selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Server default", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        if (selected) {
            Icon(
                Icons.Filled.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
    HorizontalDivider()
}

@Composable
private fun ProfileRow(
    profile: ProfileSummary,
    selected: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onSelect)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                profile.displayName,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
            )
            details(profile)?.let { detail ->
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
        if (selected) {
            Icon(
                Icons.Filled.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/**
 * The server's list, plus a row for a saved profile the server no longer
 * lists, so an existing job's selection is always visible and always checked.
 */
internal fun listedProfiles(
    profiles: List<ProfileSummary>,
    selectedProfileName: String,
    query: String,
): List<ProfileSummary> {
    val name = CronJobModelSelection.nonEmpty(selectedProfileName)
    val withSelection = if (name != null && profiles.none { it.normalizedName == name }) {
        listOf(ProfileSummary(name = name)) + profiles
    } else {
        profiles
    }
    return filteredProfiles(withSelection, query)
}

/** Port of `DefaultProfilePickerView.filteredProfiles`. */
internal fun filteredProfiles(profiles: List<ProfileSummary>, query: String): List<ProfileSummary> {
    if (query.isEmpty()) return profiles
    return profiles.filter { profile ->
        profile.displayName.contains(query, ignoreCase = true) ||
            (profile.normalizedName?.contains(query, ignoreCase = true) ?: false) ||
            (profile.model?.contains(query, ignoreCase = true) ?: false) ||
            (profile.provider?.contains(query, ignoreCase = true) ?: false)
    }
}

/** The profile's own model and provider, so the inheritance the footer describes is visible. */
internal fun details(profile: ProfileSummary): String? {
    val details = mutableListOf<String>()
    CronJobModelSelection.nonEmpty(profile.model.orEmpty())?.let(details::add)
    CronJobModelSelection.nonEmpty(profile.provider.orEmpty())?.let(details::add)
    if (details.isEmpty()) return null
    return details.joinToString(" - ")
}
