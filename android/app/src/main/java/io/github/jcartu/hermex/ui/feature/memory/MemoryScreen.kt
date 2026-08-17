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
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
 * Port of `Features/Memory/MemoryView.swift` (read-only): the three memory
 * sections (notes, user profile, agent soul) plus the read-only project
 * context. Reading-optimized adaptive layout: one centered text column capped
 * at 720dp at every pane count, with a larger-type toggle.
 */
@Composable
fun MemoryRoute() {
    val app = LocalContext.current.applicationContext as HermexApplication
    val viewModel: MemoryViewModel = viewModel(
        factory = viewModelFactory { initializer { MemoryViewModel(app.container.api) } },
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var largerType by rememberSaveable(key = "memoryLargerType") { mutableStateOf(false) }

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
                    "Memory",
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
                IconButton(onClick = { viewModel.load() }, enabled = !state.isLoading) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Refresh memory")
                }
            }

            when {
                state.isLoading && !state.hasLoaded -> LoadingState(label = "Loading memory...")
                state.errorMessage != null && !state.hasLoaded ->
                    ErrorState(
                        message = state.errorMessage ?: "Something went wrong.",
                        retryLabel = "Try Again",
                        onRetry = { viewModel.load() },
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
                        MemoryContent(state)
                    }
                }
            }
        }
    }
}

@Composable
private fun MemoryContent(state: MemoryUiState) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        MemorySection.entries.forEach { section ->
            MemorySectionCard(
                icon = section.icon,
                title = section.title,
                modifiedAt = state.modifiedAt(section),
                trailingIcon = null,
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
                trailingIcon = Icons.Filled.Lock,
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
private fun MemorySectionCard(
    icon: ImageVector,
    title: String,
    modifiedAt: Double?,
    trailingIcon: ImageVector?,
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
                trailingIcon?.let {
                    Icon(
                        it,
                        contentDescription = "Read-only",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp),
                    )
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
