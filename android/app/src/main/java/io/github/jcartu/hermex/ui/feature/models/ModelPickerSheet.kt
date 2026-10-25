package io.github.jcartu.hermex.ui.feature.models

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.jcartu.hermex.HermexApplication
import io.github.jcartu.hermex.model.ModelCatalogGroup
import io.github.jcartu.hermex.model.ModelCatalogOption
import io.github.jcartu.hermex.ui.components.ErrorState
import io.github.jcartu.hermex.ui.components.LoadingState
import io.github.jcartu.hermex.ui.theme.Gold
/** Optional first row for surfaces where an unset/default model is a valid choice. */
data class ModelPickerClearAction(
    val title: String,
    val isSelected: Boolean,
    val action: () -> Unit,
)


/**
 * Model picker — port of `ComposerModelPickerSheet`
 * (ChatComposerSelectorSheets.swift): custom-model entry, search, provider
 * groups as disclosure sections, star favorites, saved-custom delete, plus the
 * pinned Favorites/Recent sections the iOS composer menu surfaces. Selecting a
 * model records it as a recent (`ModelRecentsStore`).
 *
 * Public API (stable — chat/settings agents call this):
 * ```
 * ModelPickerSheet(
 *     onSelect = { option -> /* apply the model */ },
 *     onDismiss = { showsPicker = false },
 *     currentModel = state.currentModel,
 *     currentModelProvider = state.currentModelProvider, // optional
 * )
 * ```
 * The sheet dismisses itself after a selection ([onSelect] then [onDismiss]).
 * Presentation is adaptive: bottom sheet on compact, hinge-aware card dialog
 * on medium/expanded/trifold (see [PickerSheetScaffold]).
 */
@Composable
fun ModelPickerSheet(
    onSelect: (ModelCatalogOption) -> Unit,
    onDismiss: () -> Unit,
    currentModel: String?,
    currentModelProvider: String? = null,
    clearAction: ModelPickerClearAction? = null,
    initialGroups: List<ModelCatalogGroup>? = null,
) {
    val context = LocalContext.current
    val container = (context.applicationContext as HermexApplication).container
    val viewModel: ModelPickerViewModel = viewModel(
        key = "model-picker:$currentModel:$currentModelProvider:${initialGroups?.size ?: 0}",
        factory = viewModelFactory {
            initializer {
                ModelPickerViewModel(
                    api = container.api,
                    favoritesStore = ModelPickerStores.favorites(context),
                    recentsStore = ModelPickerStores.recents(context),
                    initialModelID = currentModel,
                    initialProviderID = currentModelProvider,
                    writeScope = container.appScope,
                    initialGroups = initialGroups,
                )
            }
        },
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    PickerSheetScaffold(title = "Choose Model", onDismiss = onDismiss) {
        ModelPickerContent(
            state = state,
            onSearchTextChange = viewModel::updateSearchText,
            onCustomModelIDChange = viewModel::updateCustomModelID,
            onCustomProviderIDChange = viewModel::updateCustomProviderID,
            onGroupExpandedChange = viewModel::setGroupExpanded,
            onToggleFavorite = viewModel::toggleFavorite,
            onDeleteSavedCustom = viewModel::deleteSavedCustom,
            clearAction = clearAction?.let { clear ->
                clear.copy(
                    action = {
                        clear.action()
                        onDismiss()
                    },
                )
            },
            onRetry = viewModel::loadCatalog,
            onSelect = { option ->
                viewModel.select(option)
                onSelect(option)
                onDismiss()
            },
        )
    }
}

/** Stateless picker body — separated so it can be embedded and previewed without a ViewModel. */
@Composable
internal fun ModelPickerContent(
    state: ModelPickerUiState,
    onSearchTextChange: (String) -> Unit,
    onCustomModelIDChange: (String) -> Unit,
    onCustomProviderIDChange: (String) -> Unit,
    onGroupExpandedChange: (String, Boolean) -> Unit,
    onToggleFavorite: (ModelCatalogOption) -> Unit,
    onDeleteSavedCustom: (ModelCatalogOption) -> Unit,
    onRetry: () -> Unit,
    onSelect: (ModelCatalogOption) -> Unit,
    clearAction: ModelPickerClearAction? = null,
) {
    Column(Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = state.searchText,
            onValueChange = onSearchTextChange,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            placeholder = { Text("Search models") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
        )

        when {
            state.isLoading && state.modelGroups.isEmpty() ->
                LoadingState(label = "Loading models...")

            state.errorMessage != null && state.modelGroups.isEmpty() ->
                ErrorState(message = state.errorMessage, onRetry = onRetry)

            else -> ModelPickerList(
                state = state,
                onCustomModelIDChange = onCustomModelIDChange,
                onCustomProviderIDChange = onCustomProviderIDChange,
                onGroupExpandedChange = onGroupExpandedChange,
                onToggleFavorite = onToggleFavorite,
                onDeleteSavedCustom = onDeleteSavedCustom,
                clearAction = clearAction,
                onSelect = onSelect,
            )
        }
    }
}

@Composable
private fun ModelPickerList(
    state: ModelPickerUiState,
    onCustomModelIDChange: (String) -> Unit,
    onCustomProviderIDChange: (String) -> Unit,
    onGroupExpandedChange: (String, Boolean) -> Unit,
    onToggleFavorite: (ModelCatalogOption) -> Unit,
    onDeleteSavedCustom: (ModelCatalogOption) -> Unit,
    onSelect: (ModelCatalogOption) -> Unit,
    clearAction: ModelPickerClearAction?,
) {
    val displayGroups = state.displayGroups
    val listState = rememberLazyListState()

    LazyColumn(state = listState, modifier = Modifier.fillMaxWidth()) {
        if (clearAction != null && state.searchText.isBlank()) {
            item(key = "clear-selection") {
                ModelPickerClearRow(clearAction)
            }
        }

        item(key = "custom-entry") {
            CustomModelEntry(
                state = state,
                onCustomModelIDChange = onCustomModelIDChange,
                onCustomProviderIDChange = onCustomProviderIDChange,
                onToggleFavorite = onToggleFavorite,
                onSelect = onSelect,
            )
        }

        displayGroups.forEach { group ->
            item(key = "header-${group.id}") {
                GroupHeader(
                    name = group.name,
                    count = group.models.size,
                    expanded = state.isExpanded(group.id),
                    onToggle = { onGroupExpandedChange(group.id, !state.isExpanded(group.id)) },
                )
            }
            if (state.isExpanded(group.id)) {
                items(
                    count = group.models.size,
                    key = { index ->
                        val option = group.models[index]
                        "${group.id}:${option.id}:${option.providerID.orEmpty()}"
                    },
                ) { index ->
                    ModelOptionRow(
                        option = group.models[index],
                        isSelected = state.isSelected(group.models[index]),
                        isFavorite = state.isFavorite(group.models[index]),
                        allowsDelete = group.allowsDelete,
                        onSelect = onSelect,
                        onToggleFavorite = onToggleFavorite,
                        onDelete = onDeleteSavedCustom,
                    )
                }
            }
        }

        if (displayGroups.isEmpty() && state.searchText.isNotBlank()) {
            item(key = "no-results") {
                Text(
                    "No models match \"${state.searchText.trim()}\"",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                )
            }
        }

        item(key = "bottom-spacer") { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun ModelPickerClearRow(clearAction: ModelPickerClearAction) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = clearAction.action)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = clearAction.title,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (clearAction.isSelected) {
            Icon(
                imageVector = Icons.Filled.CheckCircle,
                contentDescription = "Selected",
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/** Port of the Swift `customModelEntry` (exact model id + provider id + Use Custom / star). */
@Composable
private fun CustomModelEntry(
    state: ModelPickerUiState,
    onCustomModelIDChange: (String) -> Unit,
    onCustomProviderIDChange: (String) -> Unit,
    onToggleFavorite: (ModelCatalogOption) -> Unit,
    onSelect: (ModelCatalogOption) -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            "Custom Model",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        OutlinedTextField(
            value = state.customModelID,
            onValueChange = onCustomModelIDChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Exact model ID") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
        )

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = state.customProviderID,
                onValueChange = onCustomProviderIDChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text("Provider ID") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
            )

            if (state.providerChoices.isNotEmpty()) {
                Box {
                    var menuOpen by remember { mutableStateOf(false) }
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.ArrowDropDown, contentDescription = "Choose provider ID")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        state.providerChoices.forEach { provider ->
                            DropdownMenuItem(
                                text = { Text(provider.name) },
                                onClick = {
                                    onCustomProviderIDChange(provider.id)
                                    menuOpen = false
                                },
                            )
                        }
                    }
                }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val customOption = state.customOption
            FilledTonalButton(
                onClick = { customOption?.let(onSelect) },
                enabled = customOption != null,
            ) {
                Icon(Icons.Filled.AddCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Use Custom")
            }

            IconButton(
                onClick = { customOption?.let(onToggleFavorite) },
                enabled = customOption != null,
            ) {
                Icon(
                    if (state.isCustomOptionFavorite) Icons.Filled.Star else Icons.Outlined.StarBorder,
                    contentDescription = if (state.isCustomOptionFavorite) {
                        "Remove custom model from favorites"
                    } else {
                        "Add custom model to favorites"
                    },
                    tint = if (state.isCustomOptionFavorite) Gold else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        HorizontalDivider()
    }
}

/** Disclosure-group header: name, model count badge, rotating chevron. */
@Composable
private fun GroupHeader(
    name: String,
    count: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .animateContentSize(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            name,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            "$count",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.weight(1f))
        Icon(
            Icons.Filled.ExpandMore,
            contentDescription = if (expanded) "Collapse $name" else "Expand $name",
            modifier = Modifier.rotate(if (expanded) 0f else -90f),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Port of the Swift `modelOptionRow`: select button + star + optional delete. */
@Composable
private fun ModelOptionRow(
    option: ModelCatalogOption,
    isSelected: Boolean,
    isFavorite: Boolean,
    allowsDelete: Boolean,
    onSelect: (ModelCatalogOption) -> Unit,
    onToggleFavorite: (ModelCatalogOption) -> Unit,
    onDelete: (ModelCatalogOption) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onSelect(option) }
            .padding(start = 24.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            if (isSelected) Icons.Filled.CheckCircle else Icons.Outlined.Circle,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
        )
        ProviderGlyph(
            providerId = option.providerID,
            modifier = Modifier.size(22.dp),
            contentDescription = option.providerID?.let { "$it provider" },
        )

        Column(Modifier.weight(1f)) {
            Text(
                option.displayName,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (option.id != option.displayName) {
                Text(
                    option.id,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.MiddleEllipsis,
                )
            }
            val providerID = option.providerID
            if (!providerID.isNullOrEmpty()) {
                Text(
                    providerID,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 1,
                    overflow = TextOverflow.MiddleEllipsis,
                )
            }
        }

        IconButton(onClick = { onToggleFavorite(option) }) {
            Icon(
                if (isFavorite) Icons.Filled.Star else Icons.Outlined.StarBorder,
                contentDescription = if (isFavorite) {
                    "Remove ${option.displayName} from favorites"
                } else {
                    "Add ${option.displayName} to favorites"
                },
                tint = if (isFavorite) Gold else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (allowsDelete) {
            IconButton(onClick = { onDelete(option) }) {
                Icon(
                    Icons.Outlined.Delete,
                    contentDescription = "Delete saved custom model ${option.displayName}",
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}
