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
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.jcartu.hermex.model.SkillSummary
import io.github.jcartu.hermex.ui.components.EmptyState
import io.github.jcartu.hermex.ui.components.ErrorState
import io.github.jcartu.hermex.ui.components.LoadingState
import io.github.jcartu.hermex.ui.feature.models.PickerSheetScaffold

/**
 * The Task editor's Skills row and its multi-select picker. Separate from the
 * model and profile pickers because it is the only one that commits a
 * collection: the sheet stays open as rows toggle, and each toggle writes
 * straight to the draft.
 * Port of `Features/Tasks/CronJobSkillsPicker.swift`.
 */
@Composable
fun CronJobSkillsRow(
    selection: List<String>,
    errorMessage: String?,
    isLoading: Boolean,
    action: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(Modifier.then(modifier).fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = action)
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Skills",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Text(
                skillsTitle(selection),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
            )
            Spacer(Modifier.width(6.dp))
            CronJobPickerChevron()
        }

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

/**
 * The selected names themselves rather than a count: three skills fit, and
 * "3 skills" would make the row's own value unreadable without opening it.
 */
private fun skillsTitle(selection: List<String>): String =
    if (selection.isEmpty()) "None" else selection.joinToString(", ")

/**
 * The Task editor's skills picker. Multi-select, so it stays open as rows are
 * toggled and commits each change straight to the draft.
 *
 * Unlike the profile picker this one keeps a free-text entry: upstream accepts
 * `skills` unvalidated, and without it a failed `/api/skills` would turn a
 * field that used to accept anything into a read-only one.
 */
@Composable
fun CronJobSkillsPickerSheet(
    skills: List<SkillSummary>,
    selection: List<String>,
    isLoading: Boolean,
    errorMessage: String?,
    onRetry: () -> Unit,
    onToggle: (String) -> Unit,
    onClear: () -> Unit,
    onAddCustom: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var searchText by rememberSaveable { mutableStateOf("") }
    var customSkillName by rememberSaveable { mutableStateOf("") }
    val query = searchText.trim()

    val listed = listedSkills(skills, selection = selection, query = query)

    PickerSheetScaffold(title = "Task Skills", onDismiss = onDismiss) {
        LazyColumn {
            item {
                OutlinedTextField(
                    value = searchText,
                    onValueChange = { searchText = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    singleLine = true,
                    placeholder = { Text("Search skills") },
                )
            }

            if (skills.isEmpty() && isLoading) {
                item { LoadingState(label = "Loading skills...") }
            } else if (skills.isEmpty() && errorMessage != null) {
                item { ErrorState(message = errorMessage, retryLabel = "Try Again", onRetry = onRetry) }
            } else if (listed.isEmpty() && query.isNotEmpty()) {
                item { EmptyState(title = "No Results", message = "No skills match \"$query\".") }
            } else if (skills.isEmpty()) {
                item { EmptyState(title = "No skills available", message = "") }
            } else {
                items(listed.size) { index ->
                    val skill = listed[index]
                    val name = skill.name
                    val selected = name != null && name in selection
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { name?.let(onToggle) }
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = selected,
                            onCheckedChange = null,
                            enabled = name != null,
                        )
                        Column(Modifier.weight(1f)) {
                            Text(
                                skill.name ?: "Unnamed Skill",
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                            )
                            skillDetails(skill)?.let { detail ->
                                Text(
                                    detail,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
            }
        }

        HorizontalDivider()
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = customSkillName,
                onValueChange = { customSkillName = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                placeholder = { Text("Custom skill name") },
            )
            IconButton(
                onClick = {
                    val name = customSkillName.trim()
                    if (name.isNotEmpty()) {
                        onAddCustom(name)
                        customSkillName = ""
                    }
                },
                enabled = customSkillName.trim().isNotEmpty(),
            ) {
                Icon(Icons.Filled.Check, contentDescription = "Add skill")
            }
        }
        if (selection.isNotEmpty()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onClear)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.Clear,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.width(18.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    "Clear all skills",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

/**
 * The server's list, plus a row for every selected skill it does not offer,
 * so a saved selection is always visible and always removable.
 */
internal fun listedSkills(
    skills: List<SkillSummary>,
    selection: List<String>,
    query: String,
): List<SkillSummary> {
    val withSelection = selection
        .filter { selected -> skills.none { it.name == selected } }
        .map { SkillSummary(name = it) } + skills
    return filteredSkills(withSelection, query)
}

/** A skill is findable by every string its row shows. */
internal fun filteredSkills(skills: List<SkillSummary>, query: String): List<SkillSummary> {
    if (query.isEmpty()) return skills
    return skills.filter { skill ->
        (skill.name?.contains(query, ignoreCase = true) ?: false) ||
            (skill.category?.contains(query, ignoreCase = true) ?: false) ||
            (skill.description?.contains(query, ignoreCase = true) ?: false)
    }
}

internal fun skillDetails(skill: SkillSummary): String? {
    val details = mutableListOf<String>()
    CronJobModelSelection.nonEmpty(skill.category.orEmpty())?.let(details::add)
    CronJobModelSelection.nonEmpty(skill.description.orEmpty())?.let(details::add)
    if (details.isEmpty()) return null
    return details.joinToString(" - ")
}
