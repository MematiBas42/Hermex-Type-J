package io.github.jcartu.hermex.ui.feature.skills

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.mikepenz.markdown.m3.Markdown
import io.github.jcartu.hermex.HermexApplication
import io.github.jcartu.hermex.model.SkillSummary
import io.github.jcartu.hermex.ui.adaptive.HingeAwarePaneRow
import io.github.jcartu.hermex.ui.adaptive.LocalPaneCount
import io.github.jcartu.hermex.ui.components.EmptyState
import io.github.jcartu.hermex.ui.components.ErrorState
import io.github.jcartu.hermex.ui.components.LoadingState

private const val MAX_PANE_CONTENT_WIDTH_DP = 520

/**
 * Skills catalog. Port of `Features/Skills/SkillsView.swift`: category
 * sections with counts, search across names/descriptions/categories/tags,
 * enable/disable toggles, markdown skill content, and linked-file viewing.
 *
 * Adaptive: 1 pane → grid with full-screen detail + back and a bottom-sheet
 * file viewer; 2 panes → list | detail with the sheet viewer; 3 panes →
 * list | detail | file-viewer pane, boundaries on the hinges via
 * [HingeAwarePaneRow]. Wide panes flow the skill cards into a grid
 * (adaptive 280dp cells).
 */
@Composable
fun SkillsRoute() {
    val container = (LocalContext.current.applicationContext as HermexApplication).container
    SkillsRoute(client = WebuiSkillsDataClient(container.api))
}

/** Kind-neutral Skills route; direct Hermes supplies [HermesSkillsDataClient] and its Profile. */
@Composable
fun SkillsRoute(
    client: SkillsDataClient,
    profile: String? = null,
) {
    val skillsViewModel: SkillsViewModel = viewModel(
        key = "skills:${profile.orEmpty()}",
        factory = viewModelFactory { initializer { SkillsViewModel(client) } },
    )
    val state by skillsViewModel.uiState.collectAsStateWithLifecycle()
    val paneCount = LocalPaneCount.current

    var selectedSkillName by rememberSaveable { mutableStateOf<String?>(null) }
    val selectedSkill = state.skills.firstOrNull { it.name == selectedSkillName }

    val detailViewModel: SkillDetailViewModel? = selectedSkillName?.let { name ->
        viewModel<SkillDetailViewModel>(
            key = "skillDetail:${profile.orEmpty()}:$name",
            factory = viewModelFactory {
                initializer { SkillDetailViewModel(client, name) }
            },
        )
    }
    val detailState = detailViewModel?.uiState?.collectAsStateWithLifecycle()?.value

    val listPane: @Composable (Modifier) -> Unit = { paneModifier ->
        SkillsListPane(
            state = state,
            profile = profile,
            selectedSkillName = if (paneCount == 1) null else selectedSkillName,
            onSelect = { skill -> skill.name?.let { selectedSkillName = it } },
            onToggle = skillsViewModel::setSkill,
            onSearchTextChange = skillsViewModel::setSearchText,
            onRefresh = skillsViewModel::load,
            modifier = paneModifier,
        )
    }

    val detailPane: @Composable (Modifier, (() -> Unit)?) -> Unit = { paneModifier, onBack ->
        if (selectedSkillName != null && detailViewModel != null && detailState != null) {
            SkillDetailPane(
                title = selectedSkill?.let(SkillsViewModel.Companion::displayName)
                    ?: selectedSkillName.orEmpty(),
                state = detailState,
                onRefresh = detailViewModel::load,
                onOpenFile = detailViewModel::openFile,
                onBack = onBack,
                modifier = paneModifier,
            )
        } else {
            EmptyState(
                title = "Select a skill",
                message = "Choose a skill to read its instructions.",
                modifier = paneModifier,
            )
        }
    }

    if (paneCount == 1) {
        if (selectedSkillName != null) {
            BackHandler { selectedSkillName = null }
            detailPane(Modifier) { selectedSkillName = null }
        } else {
            listPane(Modifier)
        }
    } else {
        HingeAwarePaneRow(paneCount = paneCount) { index, paneModifier ->
            when (index) {
                0 -> listPane(paneModifier)
                1 -> detailPane(paneModifier, null)
                else -> if (detailState?.selectedFile != null) {
                    SkillFileViewerPane(
                        fileName = detailState.selectedFile,
                        content = detailState.fileContent,
                        isLoading = detailState.isLoadingFile,
                        isBinary = detailState.fileIsBinary,
                        isTruncated = detailState.fileIsTruncated,
                        onClose = { detailViewModel.closeFile() },
                        modifier = paneModifier,
                    )
                } else {
                    EmptyState(
                        title = "Linked files",
                        message = "Select a linked file to preview it here.",
                        modifier = paneModifier,
                    )
                }
            }
        }
    }

    if (paneCount < 3 && detailState?.selectedFile != null && detailViewModel != null) {
        SkillFileViewerSheet(
            fileName = detailState.selectedFile,
            content = detailState.fileContent,
            isLoading = detailState.isLoadingFile,
            isBinary = detailState.fileIsBinary,
            isTruncated = detailState.fileIsTruncated,
            onClose = detailViewModel::closeFile,
        )
    }
}

// MARK: - List pane

@Composable
private fun SkillsListPane(
    profile: String?,
    state: SkillsUiState,
    selectedSkillName: String?,
    onSelect: (SkillSummary) -> Unit,
    onToggle: (SkillSummary, Boolean) -> Unit,
    onSearchTextChange: (String) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (profile == null) "Skills" else "Skills · $profile",
                style = MaterialTheme.typography.titleLarge,
            )
            Spacer(Modifier.weight(1f))
            if (state.isLoading) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                IconButton(onClick = onRefresh) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                }
            }
        }

        OutlinedTextField(
            value = state.searchText,
            onValueChange = onSearchTextChange,
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            singleLine = true,
            placeholder = { Text("Search skills...") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                if (state.searchText.isNotEmpty()) {
                    IconButton(onClick = { onSearchTextChange("") }) {
                        Icon(Icons.Filled.Close, contentDescription = "Clear search")
                    }
                }
            },
        )

        // Toggle failures surface here without hiding the loaded list.
        if (state.errorMessage != null && state.skills.isNotEmpty()) {
            Text(
                state.errorMessage,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }

        when {
            state.isLoading && state.skills.isEmpty() -> LoadingState(label = "Loading skills...")
            state.errorMessage != null && state.skills.isEmpty() -> ErrorState(
                message = state.errorMessage,
                retryLabel = "Try Again",
                onRetry = onRefresh,
            )
            state.skills.isEmpty() -> EmptyState(
                title = "No Skills",
                message = "Skills from the Hermes server will appear here.",
            )
            state.searchText.trim().isNotEmpty() && state.groups.isEmpty() -> EmptyState(
                title = "No Results",
                message = "No skills match \"${state.searchText.trim()}\".",
            )
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 280.dp),
                state = rememberLazyGridState(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                state.groups.forEach { group ->
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Text(
                            "${group.category.uppercase()} · ${group.skills.size}",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
                        )
                    }
                    items(group.skills.size) { index ->
                        val skill = group.skills[index]
                        SkillCard(
                            skill = skill,
                            selected = skill.name != null && skill.name == selectedSkillName,
                            isToggling = skill.name?.trim()?.let { it in state.togglingSkillNames } == true,
                            onToggle = { enabled -> onToggle(skill, enabled) },
                            onClick = { onSelect(skill) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SkillCard(
    skill: SkillSummary,
    selected: Boolean,
    isToggling: Boolean,
    onToggle: (Boolean) -> Unit,
    onClick: () -> Unit,
) {
    // Mirrors Swift `canToggle`: a toggle needs a disabled flag and a name.
    val canToggle = skill.disabled != null && !skill.name?.trim().isNullOrEmpty()
    val isDisabled = skill.disabled == true

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.surfaceContainerHigh
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
            Column(
                Modifier.weight(1f).alpha(if (isDisabled) 0.55f else 1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    SkillsViewModel.displayName(skill),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                )
                val description = skill.description?.trim()
                if (!description.isNullOrEmpty()) {
                    Text(
                        description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                    )
                }
                val tags = skill.tags.orEmpty().map { it.trim() }.filter { it.isNotEmpty() }
                if (isDisabled || tags.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (isDisabled) {
                            SkillChip("Disabled")
                        }
                        tags.take(3).forEach { SkillChip(it) }
                    }
                }
            }

            if (canToggle) {
                Spacer(Modifier.width(8.dp))
                Switch(
                    checked = !isDisabled,
                    onCheckedChange = onToggle,
                    enabled = !isToggling,
                )
            }
        }
    }
}

@Composable
private fun SkillChip(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        modifier = Modifier
            .background(MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape)
            .padding(horizontal = 7.dp, vertical = 3.dp),
    )
}


// MARK: - Detail pane

@Composable
private fun SkillDetailPane(
    title: String,
    state: SkillDetailUiState,
    onRefresh: () -> Unit,
    onOpenFile: (String) -> Unit,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier
                .fillMaxSize()
                .widthIn(max = MAX_PANE_CONTENT_WIDTH_DP.dp),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (onBack != null) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = if (onBack == null) 12.dp else 0.dp),
                )
                if (!state.content.isNullOrEmpty()) {
                    IconButton(onClick = { copyToClipboard(context, title, state.content) }) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = "Copy skill content")
                    }
                }
                if (state.isLoading) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                } else {
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                    }
                }
            }

            when {
                state.isLoading && state.content == null -> LoadingState(label = "Loading skill...")
                state.errorMessage != null && state.content == null -> ErrorState(
                    message = state.errorMessage,
                    retryLabel = "Try Again",
                    onRetry = onRefresh,
                )
                state.content.isNullOrEmpty() && state.linkedFiles.isEmpty() -> EmptyState(
                    title = "No Content",
                    message = "This skill has no content.",
                )
                else -> Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp)
                        .padding(bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    if (!state.content.isNullOrEmpty()) {
                        Markdown(content = state.content)
                    }

                    if (state.hasLinkedFiles && state.linkedFiles.isNotEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                "Linked Files",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            state.linkedFiles.forEachIndexed { index, fileName ->
                                Surface(
                                    onClick = { onOpenFile(fileName) },
                                    color = Color.Transparent,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Row(
                                        Modifier.padding(vertical = 9.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Icon(
                                            Icons.Filled.Description,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(20.dp),
                                        )
                                        Spacer(Modifier.width(12.dp))
                                        Text(
                                            fileName,
                                            style = MaterialTheme.typography.bodyMedium,
                                            maxLines = 1,
                                            modifier = Modifier.weight(1f),
                                        )
                                    }
                                }
                                if (index < state.linkedFiles.size - 1) {
                                    HorizontalDivider(Modifier.padding(start = 32.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// MARK: - Linked file viewer

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SkillFileViewerSheet(
    fileName: String,
    content: String?,
    isLoading: Boolean,
    isBinary: Boolean,
    isTruncated: Boolean,
    onClose: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        SkillFileViewerContent(
            fileName = fileName,
            content = content,
            isLoading = isLoading,
            isBinary = isBinary,
            isTruncated = isTruncated,
            onClose = onClose,
        )
    }
}

@Composable
private fun SkillFileViewerPane(
    fileName: String,
    content: String?,
    isLoading: Boolean,
    isBinary: Boolean,
    isTruncated: Boolean,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.fillMaxSize().widthIn(max = MAX_PANE_CONTENT_WIDTH_DP.dp)) {
            SkillFileViewerContent(
                fileName = fileName,
                content = content,
                isLoading = isLoading,
                isBinary = isBinary,
                isTruncated = isTruncated,
                onClose = onClose,
            )
        }
    }
}

@Composable
private fun SkillFileViewerContent(
    fileName: String,
    content: String?,
    isLoading: Boolean,
    isBinary: Boolean,
    isTruncated: Boolean,
    onClose: () -> Unit,
) {
    val context = LocalContext.current

    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                fileName,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                modifier = Modifier.weight(1f).padding(start = 12.dp),
            )
            if (!content.isNullOrEmpty()) {
                IconButton(onClick = { copyToClipboard(context, fileName, content) }) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = "Copy file content")
                }
            }
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.Close, contentDescription = "Close")
            }
        }

        when {
            isLoading -> LoadingState(label = "Loading file...")
            isBinary -> EmptyState(
                title = "No Preview",
                message = "Preview is not available for this file type.",
            )
            content.isNullOrEmpty() -> EmptyState(
                title = "No Content",
                message = "This file appears to be empty.",
            )
            else -> Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 24.dp),
            ) {
                if (isTruncated) {
                    Text(
                        "Preview truncated",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                Markdown(content = content)
            }
        }
    }
}

private fun copyToClipboard(context: Context, label: String, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
}
