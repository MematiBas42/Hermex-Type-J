package io.github.jcartu.hermex.ui.feature.kanban

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.jcartu.hermex.model.KanbanBoard
import io.github.jcartu.hermex.model.KanbanBulkActionRequest
import io.github.jcartu.hermex.model.KanbanBulkActionType
import io.github.jcartu.hermex.model.KanbanCard
import io.github.jcartu.hermex.model.KanbanCreateBoardRequest
import io.github.jcartu.hermex.model.KanbanCreateCardRequest
import io.github.jcartu.hermex.model.KanbanEditBoardRequest
import io.github.jcartu.hermex.model.KanbanEditCardRequest
import io.github.jcartu.hermex.model.KanbanStatus
import java.util.UUID

@Composable
internal fun KanbanBoardEditorDialog(
    board: KanbanBoard?,
    onDismiss: () -> Unit,
    onCreate: (KanbanCreateBoardRequest) -> Unit,
    onEdit: (KanbanEditBoardRequest) -> Unit,
) {
    var slug by remember(board) { mutableStateOf(board?.slug.orEmpty()) }
    var name by remember(board) { mutableStateOf(board?.name.orEmpty()) }
    var description by remember(board) { mutableStateOf(board?.description.orEmpty()) }
    var icon by remember(board) { mutableStateOf(board?.icon ?: "J") }
    var color by remember(board) { mutableStateOf(board?.color ?: "#FFC629") }
    val normalizedSlug = slug.trim().lowercase().replace(Regex("[^a-z0-9-]+"), "-").trim('-')
    val valid = normalizedSlug.isNotEmpty() && name.trim().isNotEmpty() && Regex("#[0-9A-Fa-f]{6}").matches(color.trim())

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (board == null) "New board" else "Edit board") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (board == null) OutlinedTextField(slug, { slug = it }, label = { Text("Slug") }, singleLine = true)
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                OutlinedTextField(description, { description = it }, label = { Text("Description") }, minLines = 2)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(icon, { icon = it.take(4) }, label = { Text("Icon") }, modifier = Modifier.weight(1f), singleLine = true)
                    OutlinedTextField(color, { color = it }, label = { Text("Color") }, modifier = Modifier.weight(2f), singleLine = true)
                }
                if (slug != normalizedSlug && board == null && normalizedSlug.isNotEmpty()) {
                    Text("Will be saved as $normalizedSlug", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    if (board == null) {
                        onCreate(KanbanCreateBoardRequest(normalizedSlug, name.trim(), description.trim(), icon.trim(), color.trim()))
                    } else {
                        onEdit(KanbanEditBoardRequest(board.slug.orEmpty(), name.trim(), description.trim(), icon.trim(), color.trim()))
                    }
                    onDismiss()
                },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
internal fun KanbanCardEditorDialog(
    board: String,
    columns: List<String>,
    assignees: List<String>,
    card: KanbanCard?,
    onDismiss: () -> Unit,
    onCreate: (KanbanCreateCardRequest) -> Unit,
    onEdit: (KanbanEditCardRequest) -> Unit,
) {
    var title by remember(card) { mutableStateOf(card?.title.orEmpty()) }
    var body by remember(card) { mutableStateOf(card?.body.orEmpty()) }
    var status by remember(card, columns) { mutableStateOf(card?.status?.rawValue ?: columns.firstOrNull() ?: "triage") }
    var priority by remember(card) { mutableIntStateOf(card?.priority ?: 3) }
    var assignee by remember(card) { mutableStateOf(card?.assignee.orEmpty()) }
    var tenant by remember(card) { mutableStateOf(card?.tenant.orEmpty()) }
    var workspacePath by remember(card) { mutableStateOf(card?.workspacePath.orEmpty()) }
    var skills by remember(card) { mutableStateOf(card?.skills.orEmpty().joinToString(", ")) }
    var runtime by remember(card) { mutableStateOf(card?.maxRuntimeSeconds?.toString().orEmpty()) }
    val valid = title.trim().isNotEmpty() && status.trim().isNotEmpty() && priority in 0..9

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (card == null) "New card" else "Edit card") },
        text = {
            LazyColumn(
                modifier = Modifier.heightIn(max = 620.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item { OutlinedTextField(title, { title = it }, label = { Text("Title") }, modifier = Modifier.fillMaxWidth(), singleLine = true) }
                item { OutlinedTextField(body, { body = it }, label = { Text("Instructions") }, modifier = Modifier.fillMaxWidth(), minLines = 4) }
                item {
                    Text("Status", fontWeight = FontWeight.SemiBold)
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        columns.take(4).forEach { option ->
                            FilterChip(selected = status == option, onClick = { status = option }, label = { Text(option) })
                        }
                    }
                }
                item {
                    OutlinedTextField(priority.toString(), { priority = it.toIntOrNull()?.coerceIn(0, 9) ?: priority }, label = { Text("Priority 0–9") }, singleLine = true)
                }
                item {
                    OutlinedTextField(assignee, { assignee = it }, label = { Text("Assignee/profile") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    if (assignees.isNotEmpty()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            assignees.take(4).forEach { option -> FilterChip(assignee == option, { assignee = option }, { Text(option) }) }
                        }
                    }
                }
                item { OutlinedTextField(tenant, { tenant = it }, label = { Text("Tenant") }, modifier = Modifier.fillMaxWidth(), singleLine = true) }
                item { OutlinedTextField(workspacePath, { workspacePath = it }, label = { Text("Workspace path") }, modifier = Modifier.fillMaxWidth(), singleLine = true) }
                item { OutlinedTextField(skills, { skills = it }, label = { Text("Skills, comma-separated") }, modifier = Modifier.fillMaxWidth(), singleLine = true) }
                item { OutlinedTextField(runtime, { runtime = it.filter(Char::isDigit) }, label = { Text("Max runtime seconds") }, modifier = Modifier.fillMaxWidth(), singleLine = true) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    val skillList = skills.split(',').map(String::trim).filter(String::isNotEmpty).takeIf(List<String>::isNotEmpty)
                    if (card == null) {
                        onCreate(
                            KanbanCreateCardRequest(
                                board = board,
                                title = title.trim(),
                                body = body.trim().takeIf(String::isNotEmpty),
                                status = status,
                                priority = priority,
                                assignee = assignee.trim().takeIf(String::isNotEmpty),
                                tenant = tenant.trim().takeIf(String::isNotEmpty),
                                workspaceKind = if (workspacePath.isBlank()) "workspace" else "path",
                                workspacePath = workspacePath.trim().takeIf(String::isNotEmpty),
                                skills = skillList,
                                maxRuntimeSeconds = runtime.toIntOrNull(),
                                idempotencyKey = UUID.randomUUID().toString(),
                            ),
                        )
                    } else {
                        onEdit(
                            KanbanEditCardRequest(
                                cardId = card.cardId.orEmpty(), board = board, title = title.trim(), body = body.trim(),
                                tenant = tenant.trim().takeIf(String::isNotEmpty), priority = priority,
                                assignee = assignee.trim().takeIf(String::isNotEmpty), status = status,
                            ),
                        )
                    }
                    onDismiss()
                },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
internal fun KanbanFilterDialog(
    state: KanbanUiState,
    onDismiss: () -> Unit,
    onApply: (KanbanFilters) -> Unit,
) {
    var tenant by remember(state.filters) { mutableStateOf(state.filters.tenant) }
    var assignee by remember(state.filters) { mutableStateOf(state.filters.assignee) }
    var includeArchived by remember(state.filters) { mutableStateOf(state.filters.includeArchived) }
    var onlyMine by remember(state.filters) { mutableStateOf(state.filters.onlyMine) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Board filters") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Tenant", fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(tenant == null, { tenant = null }, { Text("All") })
                    state.tenants.take(4).forEach { option -> FilterChip(tenant == option, { tenant = option }, { Text(option) }) }
                }
                Text("Assignee", fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(assignee == null, { assignee = null }, { Text("All") })
                    state.assignees.take(4).forEach { option -> FilterChip(assignee == option, { assignee = option }, { Text(option) }) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(includeArchived, { includeArchived = it }); Text("Include archived")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(onlyMine, { onlyMine = it }); Text("Only mine")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onApply(KanbanFilters(tenant, assignee, includeArchived, onlyMine)); onDismiss() }) { Text("Apply") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
internal fun KanbanBulkActionDialog(
    state: KanbanUiState,
    onDismiss: () -> Unit,
    onApply: (KanbanBulkActionRequest) -> Unit,
) {
    var action by remember { mutableStateOf(KanbanBulkActionType.CHANGE_STATUS) }
    var value by remember { mutableStateOf(state.configuration?.columns?.firstOrNull().orEmpty()) }
    val board = state.selectedBoardSlug.orEmpty()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Bulk action · ${state.selectedCardIds.size} cards") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                KanbanBulkActionType.entries.forEach { option ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(action == option, { action = option })
                        Text(option.name.lowercase().replace('_', ' ').replaceFirstChar(Char::uppercase))
                    }
                }
                if (action != KanbanBulkActionType.ARCHIVE) {
                    OutlinedTextField(value, { value = it }, label = { Text("Value") }, modifier = Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = board.isNotBlank() && state.selectedCardIds.isNotEmpty() && (action == KanbanBulkActionType.ARCHIVE || value.isNotBlank()),
                onClick = { onApply(KanbanBulkActionRequest(board, state.selectedCardIds.toList(), action, value)); onDismiss() },
            ) { Text("Apply") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
