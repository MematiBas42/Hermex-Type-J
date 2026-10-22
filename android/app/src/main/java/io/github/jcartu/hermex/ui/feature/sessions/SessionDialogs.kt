package io.github.jcartu.hermex.ui.feature.sessions

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.jcartu.hermex.model.ProjectSummary
import io.github.jcartu.hermex.model.SessionSummary
import io.github.jcartu.hermex.ui.theme.parseHexColor

/** Port of `ProjectColorOption` from `ProjectCreationSheet.swift`. */
data class ProjectColorOption(val name: String, val hex: String) {
    val id: String get() = hex
}

/** Port of `ProjectCreationPalette` from `ProjectCreationSheet.swift`. */
object ProjectCreationPalette {
    val approvedColors: List<ProjectColorOption> = listOf(
        ProjectColorOption("Sky", "#7cb9ff"),
        ProjectColorOption("Gold", "#f5c542"),
        ProjectColorOption("Red", "#e94560"),
        ProjectColorOption("Green", "#50c878"),
        ProjectColorOption("Violet", "#c084fc"),
        ProjectColorOption("Orange", "#fb923c"),
        ProjectColorOption("Cyan", "#67e8f9"),
        ProjectColorOption("Pink", "#f472b6"),
    )

    fun defaultColor(existingProjectCount: Int): ProjectColorOption =
        approvedColors[existingProjectCount % approvedColors.size]
}

/** Port of `SessionRenameSheet.swift` as an M3 dialog. */
@Composable
fun RenameSessionDialog(
    initialTitle: String,
    isSaving: Boolean,
    onCancel: () -> Unit,
    onSave: (String) -> Unit,
    errorMessage: String? = null,
) {
    var title by rememberSaveable(initialTitle) { mutableStateOf(initialTitle) }
    val trimmedTitle = title.trim()

    AlertDialog(
        onDismissRequest = { if (!isSaving) onCancel() },
        title = { Text("Rename Session") },
        text = {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("Session title") },
                singleLine = true,
                enabled = !isSaving,
                isError = errorMessage != null,
                supportingText = errorMessage?.let { message -> { Text(message) } },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(trimmedTitle) },
                enabled = trimmedTitle.isNotEmpty() && !isSaving,
            ) {
                if (isSaving) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text("Save")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel, enabled = !isSaving) { Text("Cancel") }
        },
    )
}

/**
 * Branch ("Duplicate") dialog. The list-level branch keeps the full history —
 * `keep_count` is omitted from the request, per `SessionMutator.duplicate` in
 * the Swift sources; only the new title is editable.
 */
@Composable
fun BranchSessionDialog(
    initialTitle: String,
    isBusy: Boolean,
    onCancel: () -> Unit,
    onBranch: (String) -> Unit,
) {
    var title by rememberSaveable(initialTitle) { mutableStateOf(initialTitle) }
    val trimmedTitle = title.trim()

    AlertDialog(
        onDismissRequest = { if (!isBusy) onCancel() },
        title = { Text("Duplicate Session") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Branches a copy of this session with its full conversation history.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("New session title") },
                    singleLine = true,
                    enabled = !isBusy,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onBranch(trimmedTitle) },
                enabled = trimmedTitle.isNotEmpty() && !isBusy,
            ) {
                if (isBusy) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text("Duplicate")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel, enabled = !isBusy) { Text("Cancel") }
        },
    )
}

/** Port of the "Delete Session?" alert from `SessionListActionConfirmations.swift`. */
@Composable
fun ConfirmDeleteSessionDialog(onCancel: () -> Unit, onDelete: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Delete Session?") },
        text = {
            Text(
                "This removes the session from the Hermes server. " +
                    "Use this only on a session you no longer need.",
            )
        },
        confirmButton = {
            TextButton(onClick = onDelete) {
                Text("Delete", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

/** Port of the "Delete Project?" alert from `SessionListActionConfirmations.swift`. */
@Composable
fun ConfirmDeleteProjectDialog(onCancel: () -> Unit, onDelete: () -> Unit, message: String = "Sessions in this project will be moved to No project. The sessions themselves will not be deleted.") {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Delete Project?") },
        text = {
            Text(message)
        },
        confirmButton = {
            TextButton(onClick = onDelete) {
                Text("Delete", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

/** Port of the "Session Action Failed" alert. */
@Composable
fun ActionErrorDialog(message: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Session Action Failed") },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
    )
}

/** Port of `ProjectFormSheet` (create + rename) as an M3 dialog with color chips. */
@Composable
fun ProjectFormDialog(
    title: String,
    initialName: String,
    initialColorHex: String,
    isSaving: Boolean,
    saveLabel: String,
    onCancel: () -> Unit,
    onSave: (name: String, colorHex: String) -> Unit,
    errorMessage: String? = null,
    extraFields: @Composable () -> Unit = {},
    canSave: Boolean = true,
) {
    var name by rememberSaveable(initialName) { mutableStateOf(initialName) }
    var colorHex by rememberSaveable(initialColorHex) { mutableStateOf(initialColorHex) }
    val trimmedName = name.trim()

    AlertDialog(
        onDismissRequest = { if (!isSaving) onCancel() },
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Project name") },
                    singleLine = true,
                    enabled = !isSaving,
                    modifier = Modifier.fillMaxWidth(),
                )
                extraFields()
                errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ProjectCreationPalette.approvedColors.forEach { option ->
                        val color = parseHexColor(option.hex) ?: MaterialTheme.colorScheme.primary
                        val isSelected = option.hex.equals(colorHex, ignoreCase = true)
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .background(color, CircleShape)
                                .border(
                                    width = if (isSelected) 2.dp else 0.dp,
                                    color = if (isSelected) {
                                        MaterialTheme.colorScheme.onSurface
                                    } else {
                                        color
                                    },
                                    shape = CircleShape,
                                )
                                .clickable(enabled = !isSaving) { colorHex = option.hex },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (isSelected) {
                                Icon(
                                    imageVector = Icons.Filled.Check,
                                    contentDescription = option.name,
                                    tint = MaterialTheme.colorScheme.surface,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(trimmedName, colorHex) },
                enabled = trimmedName.isNotEmpty() && !isSaving && canSave,
            ) {
                if (isSaving) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text(saveLabel)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel, enabled = !isSaving) { Text("Cancel") }
        },
    )
}

/** Port of `SessionProjectMoveMenu` as a bottom sheet: projects + create project. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoveToProjectSheet(
    session: SessionSummary,
    projects: List<ProjectSummary>,
    isMovingSession: Boolean,
    isCreatingProject: Boolean,
    isLoadingProjects: Boolean,
    onMove: (projectId: String?) -> Unit,
    onCreateProject: () -> Unit,
    onRefreshProjects: () -> Unit,
    onDismiss: () -> Unit,
    offersNoProject: Boolean = true,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(bottom = 24.dp)) {
            Text(
                text = "Move to Project",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )

            if (offersNoProject) {
            MoveSheetRow(
                label = "No project",
                icon = {
                    Icon(
                        imageVector = if (session.projectId == null) Icons.Filled.Check else Icons.Filled.Inbox,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                enabled = !isMovingSession && session.projectId != null,
                onClick = { onMove(null) },
            )
            }

            if (projects.isNotEmpty()) {
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                projects.forEach { project ->
                    val projectId = project.projectId
                    val isSelected = session.projectId == projectId
                    val projectName = project.name?.takeIf { it.isNotEmpty() } ?: "Untitled Project"
                    MoveSheetRow(
                        label = projectName,
                        icon = {
                            if (isSelected) {
                                Icon(Icons.Filled.Check, contentDescription = "Current project")
                            } else {
                                val dotColor = project.color?.let(::parseHexColor)
                                if (dotColor != null) {
                                    Box(Modifier.size(14.dp).background(dotColor, CircleShape))
                                } else {
                                    Icon(
                                        imageVector = Icons.Filled.Folder,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        },
                        enabled = !isMovingSession && projectId != null && !isSelected,
                        onClick = { onMove(projectId) },
                    )
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 4.dp))

            MoveSheetRow(
                label = "New Project",
                icon = {
                    Icon(
                        imageVector = Icons.Filled.CreateNewFolder,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                enabled = !isCreatingProject && !isMovingSession,
                onClick = onCreateProject,
            )

            if (projects.isEmpty()) {
                MoveSheetRow(
                    label = "Refresh Projects",
                    icon = {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    enabled = !isLoadingProjects,
                    onClick = onRefreshProjects,
                )
            }
        }
    }
}

@Composable
private fun MoveSheetRow(
    label: String,
    icon: @Composable () -> Unit,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(28.dp), contentAlignment = Alignment.CenterStart) { icon() }
        Spacer(Modifier.width(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (enabled) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
