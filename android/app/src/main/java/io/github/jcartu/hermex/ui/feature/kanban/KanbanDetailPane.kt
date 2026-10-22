package io.github.jcartu.hermex.ui.feature.kanban

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.m3.Markdown
import io.github.jcartu.hermex.model.KanbanCard
import io.github.jcartu.hermex.model.KanbanCardDetailEnvelope
import io.github.jcartu.hermex.model.KanbanStatus
import io.github.jcartu.hermex.ui.components.EmptyState
import io.github.jcartu.hermex.ui.components.LoadingState
import io.github.jcartu.hermex.ui.theme.CodeTextStyle

private enum class KanbanDetailSection { OVERVIEW, ACTIVITY, LINKS, RUNS }

@Composable
internal fun KanbanDetailPane(
    state: KanbanUiState,
    onBack: (() -> Unit)?,
    onEdit: (KanbanCard) -> Unit,
    onSetStatus: (String, String) -> Unit,
    onBlock: (String, String?) -> Unit,
    onUnblock: (String) -> Unit,
    onComment: (String, String) -> Unit,
    onAddDependency: (String, String) -> Unit,
    onRemoveDependency: (String, String) -> Unit,
    onLoadLog: (String, String?) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val card = state.selectedCard
    if (state.isLoadingDetail && card == null) return LoadingState(modifier, "Loading card")
    if (card == null) return EmptyState(
        "Select a card",
        modifier = modifier,
        message = "Card details, activity and worker output stay visible here.",
    )

    var section by remember(card.cardId) { mutableStateOf(KanbanDetailSection.OVERVIEW) }
    var showStatus by remember { mutableStateOf(false) }
    var showBlock by remember { mutableStateOf(false) }
    var showDependency by remember { mutableStateOf(false) }
    var comment by remember(card.cardId) { mutableStateOf("") }
    val detail = state.detail

    Column(modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            onBack?.let { IconButton(onClick = it) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } }
            Column(Modifier.weight(1f)) {
                Text(card.title ?: "Untitled card", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(card.cardId.orEmpty(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onRefresh) { Icon(Icons.Filled.Refresh, "Refresh card") }
            IconButton(onClick = { onEdit(card) }, enabled = state.canMutate) { Icon(Icons.Filled.Edit, "Edit card") }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            AssistChip(
                onClick = { showStatus = true }, enabled = state.canMutate,
                label = { Text(card.status?.rawValue ?: "Unknown") },
            )
            card.assignee?.let { AssistChip(onClick = {}, enabled = false, label = { Text(it) }) }
            AssistChip(onClick = {}, enabled = false, label = { Text("P${card.priority ?: 0}") })
            val normalizedStatus = card.status?.normalized
            if (normalizedStatus == "blocked") {
                AssistChip(
                    onClick = { onUnblock(card.cardId.orEmpty()) },
                    enabled = state.canMutate,
                    label = { Text("Unblock") },
                    leadingIcon = { Icon(Icons.Filled.LockOpen, null) },
                )
            } else if (state.backend.offersBlock(normalizedStatus)) {
                AssistChip(
                    onClick = { showBlock = true },
                    enabled = state.canMutate,
                    label = { Text("Block") },
                    leadingIcon = { Icon(Icons.Filled.Block, null) },
                )
            }
            DropdownMenu(showStatus, { showStatus = false }) {
                val moveStatuses = if (state.backend == KanbanBackend.HERMES) {
                    state.backend.createStatuses
                } else {
                    state.configuration?.columns.orEmpty().filterNot { it.equals("running", true) }
                }
                moveStatuses.filterNot { it == normalizedStatus }.forEach { status ->
                    DropdownMenuItem(
                        text = { Text(status) },
                        onClick = { showStatus = false; onSetStatus(card.cardId.orEmpty(), status) },
                    )
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            KanbanDetailSection.entries.forEach { item ->
                androidx.compose.material3.FilterChip(
                    selected = section == item,
                    onClick = { section = item },
                    label = { Text(item.name.lowercase().replaceFirstChar(Char::uppercase)) },
                )
            }
        }
        HorizontalDivider()

        when (section) {
            KanbanDetailSection.OVERVIEW -> OverviewSection(card, detail, comment, { comment = it }) {
                val text = comment.trim(); if (text.isNotEmpty()) { onComment(card.cardId.orEmpty(), text); comment = "" }
            }
            KanbanDetailSection.ACTIVITY -> ActivitySection(detail)
            KanbanDetailSection.LINKS -> LinksSection(card, detail, state.canMutate, { showDependency = true }, onRemoveDependency)
            KanbanDetailSection.RUNS -> RunsSection(card, detail, state, onLoadLog)
        }
    }

    if (showBlock) {
        var reason by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showBlock = false }, title = { Text("Block card") },
            text = { OutlinedTextField(reason, { reason = it }, label = { Text("Reason (optional)") }) },
            confirmButton = { TextButton(onClick = { showBlock = false; onBlock(card.cardId.orEmpty(), reason.trim().takeIf(String::isNotEmpty)) }) { Text("Block") } },
            dismissButton = { TextButton(onClick = { showBlock = false }) { Text("Cancel") } },
        )
    }

    if (showDependency) {
        var prerequisite by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showDependency = false }, title = { Text("Add prerequisite") },
            text = { OutlinedTextField(prerequisite, { prerequisite = it }, label = { Text("Card ID") }, singleLine = true) },
            confirmButton = {
                TextButton(
                    enabled = prerequisite.trim().isNotEmpty(),
                    onClick = { showDependency = false; onAddDependency(prerequisite.trim(), card.cardId.orEmpty()) },
                ) { Text("Add") }
            },
            dismissButton = { TextButton(onClick = { showDependency = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun OverviewSection(
    card: KanbanCard,
    detail: KanbanCardDetailEnvelope?,
    comment: String,
    onCommentChange: (String) -> Unit,
    onSubmitComment: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        card.body?.takeIf(String::isNotBlank)?.let { Markdown(it) }
        Metadata("Tenant", card.tenant)
        Metadata("Workspace", card.workspacePath ?: card.workspaceKind)
        Metadata("Skills", card.skills?.joinToString())
        Metadata("Max runtime", card.maxRuntimeSeconds?.let { "$it seconds" })
        Metadata("Comments", detail?.comments?.size?.toString() ?: card.commentCount?.toString())
        Text("Add comment", fontWeight = FontWeight.SemiBold)
        OutlinedTextField(comment, onCommentChange, modifier = Modifier.fillMaxWidth(), minLines = 2)
        Button(onClick = onSubmitComment, enabled = comment.trim().isNotEmpty()) { Text("Post comment") }
        detail?.comments.orEmpty().forEach { item ->
            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                Column(Modifier.padding(12.dp)) {
                    Text(item.author ?: "Unknown", fontWeight = FontWeight.SemiBold)
                    Text(item.body.orEmpty())
                    item.createdAt?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
                }
            }
        }
    }
}

@Composable
private fun ActivitySection(detail: KanbanCardDetailEnvelope?) {
    val events = detail?.events.orEmpty()
    if (events.isEmpty()) return EmptyState("No activity", message = "Operational events will appear here.")
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        events.forEach { event ->
            Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column(Modifier.padding(12.dp)) {
                    Text(event.kind ?: "Event", fontWeight = FontWeight.SemiBold)
                    event.payload?.summary?.let { Text(it) }
                    event.payload?.reason?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    event.createdAt?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
                }
            }
        }
    }
}

@Composable
private fun LinksSection(
    card: KanbanCard,
    detail: KanbanCardDetailEnvelope?,
    canMutate: Boolean,
    onAdd: () -> Unit,
    onRemove: (String, String) -> Unit,
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(onClick = onAdd, enabled = canMutate) { Icon(Icons.Filled.Add, null); Text("Add prerequisite") }
        Text("Prerequisites", fontWeight = FontWeight.Bold)
        detail?.links?.prerequisites.orEmpty().forEach { id ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Link, null); Text(id, Modifier.weight(1f).padding(start = 8.dp))
                TextButton(onClick = { onRemove(id, card.cardId.orEmpty()) }, enabled = canMutate) { Text("Remove") }
            }
        }
        Text("Dependents", fontWeight = FontWeight.Bold)
        detail?.links?.dependents.orEmpty().forEach { id -> Text(id) }
    }
}

@Composable
private fun RunsSection(
    card: KanbanCard,
    detail: KanbanCardDetailEnvelope?,
    state: KanbanUiState,
    onLoadLog: (String, String?) -> Unit,
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        detail?.runs.orEmpty().forEach { run ->
            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column(Modifier.padding(12.dp)) {
                    Text(run.status ?: run.outcome ?: "Run", fontWeight = FontWeight.Bold)
                    run.summary?.let { Text(it) }; run.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    if (state.capabilities.workerLogs) {
                        TextButton(onClick = { onLoadLog(card.cardId.orEmpty(), run.runId) }) { Text("Load worker log") }
                    }
                }
            }
        }
        state.workerLog?.let { log ->
            Text("Worker log", fontWeight = FontWeight.Bold)
            SelectionContainer {
                Text(log.content.orEmpty().ifEmpty { "No worker output." }, style = CodeTextStyle, fontFamily = FontFamily.Monospace)
            }
            if (log.truncated == true) Text("Tail truncated", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun Metadata(label: String, value: String?) {
    if (value.isNullOrBlank()) return
    Row(Modifier.fillMaxWidth()) {
        Text(label, modifier = Modifier.weight(0.35f), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, modifier = Modifier.weight(0.65f))
    }
}
