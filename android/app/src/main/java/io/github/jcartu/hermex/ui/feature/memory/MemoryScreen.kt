package io.github.jcartu.hermex.ui.feature.memory

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.FolderSpecial
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.mikepenz.markdown.m3.Markdown
import io.github.jcartu.hermex.HermexApplication
import io.github.jcartu.hermex.model.MemorySection
import io.github.jcartu.hermex.ui.components.ErrorState
import io.github.jcartu.hermex.ui.components.LoadingState
import io.github.jcartu.hermex.ui.feature.workspace.relativeAgo

/**
 * Shared Memory screen. A direct Hermes Profile can edit its notes, user profile, and soul;
 * the legacy webui adapter preserves the fork's read-only surface.
 */
@Composable
fun MemoryRoute() {
    val app = LocalContext.current.applicationContext as HermexApplication
    MemoryRoute(client = WebuiMemoryDataClient(app.container.api))
}

@Composable
fun MemoryRoute(
    client: MemoryDataClient,
    profile: String? = null,
) {
    val viewModel: MemoryViewModel = viewModel(
        key = "memory:${profile.orEmpty()}",
        factory = viewModelFactory { initializer { MemoryViewModel(client) } },
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var largerType by rememberSaveable(key = "memoryLargerType") { mutableStateOf(false) }
    var editingSection by rememberSaveable { mutableStateOf<MemorySection?>(null) }

    LaunchedEffect(Unit) { viewModel.loadIfNeeded() }

    Box(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxSize()
                .widthIn(max = 720.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (profile == null) "Memory" else "Memory · $profile",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                IconToggleButton(checked = largerType, onCheckedChange = { largerType = it }) {
                    Icon(
                        Icons.Filled.FormatSize,
                        contentDescription = if (largerType) "Use standard type size" else "Use larger type size",
                        tint = if (largerType) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                IconButton(onClick = viewModel::load, enabled = !state.isLoading) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Refresh memory")
                }
            }

            when {
                state.isLoading && !state.hasLoaded -> LoadingState(label = "Loading memory...")
                state.errorMessage != null && !state.hasLoaded ->
                    ErrorState(
                        message = state.errorMessage ?: "Something went wrong.",
                        retryLabel = "Try Again",
                        onRetry = viewModel::load,
                    )
                !state.hasLoaded -> LoadingState(label = "Loading memory...")
                else -> {
                    val density = LocalDensity.current
                    val readingDensity = if (largerType) {
                        Density(density.density, density.fontScale * 1.2f)
                    } else {
                        density
                    }
                    CompositionLocalProvider(LocalDensity provides readingDensity) {
                        MemoryContent(
                            state = state,
                            onEdit = {
                                viewModel.clearActionError()
                                editingSection = it
                            },
                        )
                    }
                }
            }
        }
    }

    editingSection?.let { section ->
        MemoryEditDialog(
            section = section,
            initialContent = state.content(section),
            state = state,
            onDismiss = { if (!state.isSaving && !state.isReloading) editingSection = null },
            onSave = { draft, loaded ->
                viewModel.save(section, draft, loaded) { editingSection = null }
            },
            onReload = viewModel::reloadSection,
        )
    }
}

@Composable
private fun MemoryContent(
    state: MemoryUiState,
    onEdit: (MemorySection) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        state.visibleSections.forEach { section ->
            MemorySectionCard(
                icon = section.icon,
                title = section.title,
                modifiedAt = state.modifiedAt(section),
                readOnly = section in state.readOnlySections,
                onEdit = if (state.features.canEdit && section !in state.readOnlySections) {
                    { onEdit(section) }
                } else {
                    null
                },
            ) {
                val content = state.content(section)
                if (content.isBlank()) {
                    Text(
                        section.emptyMessage,
                        style = MaterialTheme.typography.bodyMedium,
                        fontStyle = FontStyle.Italic,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Markdown(content, modifier = Modifier.fillMaxWidth())
                }
            }
        }

        if (state.showsProjectContext) {
            MemorySectionCard(
                icon = Icons.Filled.FolderSpecial,
                title = "Project Context",
                modifiedAt = state.projectContextMtime,
                readOnly = true,
                onEdit = null,
            ) {
                Markdown(state.projectContextText.orEmpty(), modifier = Modifier.fillMaxWidth())
                val detail = state.projectContextDetail
                if (detail != null || state.isProjectContextShadowed) {
                    Spacer(Modifier.height(8.dp))
                }
                detail?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (state.isProjectContextShadowed) {
                    Text(
                        "A workspace-local file is overriding the global project context.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun MemoryEditDialog(
    section: MemorySection,
    initialContent: String,
    state: MemoryUiState,
    onDismiss: () -> Unit,
    onSave: (draft: String, loaded: String) -> Unit,
    onReload: (MemorySection, (String) -> Unit) -> Unit,
) {
    var draft by remember(section) { mutableStateOf(initialContent) }
    var loaded by remember(section) { mutableStateOf(initialContent) }
    val limit = state.characterLimits[section]
    val count = limit?.let { MemoryCharacterCount(draft, it) }
    val busy = state.isSaving || state.isReloading

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = if (state.conflictedSection == section) {
            { Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error) }
        } else {
            null
        },
        title = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Edit ${section.title}")
                count?.let {
                    Text(
                        if (it.isOver) "${it.count} / ${it.limit} · ${it.overBy} over" else "${it.count} / ${it.limit}",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (it.isOver) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (state.conflictedSection == section) {
                    Text(
                        "${section.title} changed since you opened it. Your draft is kept. Reload shows the host's text and drops this draft.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Button(
                        onClick = {
                            onReload(section) { text ->
                                draft = text
                                loaded = text
                            }
                        },
                        enabled = !busy,
                    ) {
                        Text("Reload")
                    }
                }
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.fillMaxWidth().height(280.dp),
                    label = { Text(section.title) },
                    textStyle = MaterialTheme.typography.bodyMedium,
                    enabled = !busy,
                )
                if (state.features.editsApplyNextSession) {
                    Text(
                        "Edits apply from the agent's next session.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                state.actionErrorMessage?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(draft, loaded) },
                enabled = !busy && count?.isOver != true,
            ) {
                Text(if (state.isSaving) "Saving…" else "Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) {
                Text("Cancel")
            }
        },
    )
}

@Composable
private fun MemorySectionCard(
    icon: ImageVector,
    title: String,
    modifiedAt: Double?,
    readOnly: Boolean,
    onEdit: (() -> Unit)?,
    content: @Composable () -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                modifiedAt?.let {
                    Text(
                        "Modified ${relativeAgo(it)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (readOnly) {
                    Icon(
                        Icons.Filled.Lock,
                        contentDescription = "Read-only",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp),
                    )
                } else if (onEdit != null) {
                    IconButton(onClick = onEdit, enabled = true) {
                        Icon(Icons.Filled.Edit, contentDescription = "Edit $title")
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

/** Section metadata mirroring the MemorySection extension in MemoryView.swift. */
private val MemorySection.title: String
    get() = when (this) {
        MemorySection.MEMORY -> "My Notes"
        MemorySection.USER -> "User Profile"
        MemorySection.SOUL -> "Agent Soul"
    }

private val MemorySection.emptyMessage: String
    get() = when (this) {
        MemorySection.MEMORY -> "No notes yet."
        MemorySection.USER -> "No profile yet."
        MemorySection.SOUL -> "No soul defined yet."
    }

private val MemorySection.icon: ImageVector
    get() = when (this) {
        MemorySection.MEMORY -> Icons.Filled.Psychology
        MemorySection.USER -> Icons.Filled.Person
        MemorySection.SOUL -> Icons.Filled.AutoAwesome
    }
