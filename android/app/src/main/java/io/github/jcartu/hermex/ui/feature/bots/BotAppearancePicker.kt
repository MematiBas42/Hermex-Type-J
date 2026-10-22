package io.github.jcartu.hermex.ui.feature.bots

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.graphics.toColorInt
import io.github.jcartu.hermex.model.ModelCatalogGroup
import io.github.jcartu.hermex.model.ModelCatalogOption
import io.github.jcartu.hermex.model.favoriteKey
import io.github.jcartu.hermex.model.hermes.BotAvatarColor
import io.github.jcartu.hermex.model.hermes.BotAvatarExpression
import io.github.jcartu.hermex.model.hermes.BotAvatarShape
import io.github.jcartu.hermex.model.hermes.BotProfileAppearance
import io.github.jcartu.hermex.ui.feature.models.ModelPickerClearAction
import io.github.jcartu.hermex.ui.feature.models.ModelPickerContent
import io.github.jcartu.hermex.ui.feature.models.ModelPickerUiState
import io.github.jcartu.hermex.ui.feature.models.PickerSheetScaffold

/**
 * Static shape, colour and rest-expression controls shared by create and edit.
 * Tiles use [BotAvatarMarkView], while each screen owns the one animated hero.
 */
@Composable
fun BotAppearancePicker(
    name: String,
    appearance: BotProfileAppearance,
    enabled: Boolean,
    onShape: (BotAvatarShape) -> Unit,
    onColor: (String) -> Unit,
    onExpression: (BotAvatarExpression) -> Unit,
    modifier: Modifier = Modifier,
    onReset: (() -> Unit)? = null,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.padding(vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            BotAvatarShape.entries.chunked(4).forEach { shapes ->
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    shapes.forEach { shape ->
                        val selected = appearance.shape == shape.rawValue
                        val preview = appearance.copy(shape = shape.rawValue, custom = true, imageKind = "shape")
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .selectable(
                                    selected = selected,
                                    enabled = enabled,
                                    role = Role.RadioButton,
                                    onClick = { onShape(shape) },
                                )
                                .semantics { contentDescription = shape.localizedName },
                            shape = RoundedCornerShape(12.dp),
                            color = if (selected) {
                                MaterialTheme.colorScheme.secondaryContainer
                            } else {
                                Color.Transparent
                            },
                        ) {
                            Box(Modifier.padding(vertical = 7.dp), contentAlignment = Alignment.Center) {
                                BotAvatarMarkView(
                                    name = name,
                                    appearance = preview,
                                    size = 42.dp,
                                    modifier = Modifier,
                                )
                            }
                        }
                    }
                    repeat(4 - shapes.size) { Spacer(Modifier.weight(1f)) }
                }
            }

            BotAvatarColor.palette.chunked(6).forEach { colors ->
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    colors.forEach { color ->
                        val selected = appearance.color.equals(color.hex, ignoreCase = true)
                        val swatch = if (color.hex == BotAvatarColor.ADAPTIVE_HEX) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            Color(color.hex.toColorInt())
                        }
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .selectable(
                                    selected = selected,
                                    enabled = enabled,
                                    role = Role.RadioButton,
                                    onClick = { onColor(color.hex) },
                                )
                                .semantics { contentDescription = "Avatar color ${color.name}" },
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(
                                Modifier
                                    .size(if (selected) 38.dp else 30.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (selected) MaterialTheme.colorScheme.outline else Color.Transparent,
                                    )
                                    .padding(if (selected) 4.dp else 0.dp)
                                    .clip(CircleShape)
                                    .background(swatch),
                            )
                        }
                    }
                    repeat(6 - colors.size) { Spacer(Modifier.weight(1f)) }
                }
            }

            HorizontalDivider()
            ExpressionMenu(
                name = name,
                appearance = appearance,
                enabled = enabled,
                onExpression = onExpression,
                modifier = Modifier.padding(horizontal = 14.dp),
            )
            if (onReset != null) {
                OutlinedButton(
                    onClick = onReset,
                    enabled = enabled,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                ) {
                    Icon(Icons.Outlined.Refresh, contentDescription = null)
                    Text("Reset to default", Modifier.padding(start = 8.dp))
                }
            }
        }
    }
}

@Composable
private fun ExpressionMenu(
    name: String,
    appearance: BotProfileAppearance,
    enabled: Boolean,
    onExpression: (BotAvatarExpression) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = BotAvatarExpression.resolve(appearance.expression)
    Box(modifier.fillMaxWidth()) {
        OutlinedButton(
            onClick = { expanded = true },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        ) {
            BotAvatarMarkView(
                name = name,
                appearance = appearance,
                size = 32.dp,
                modifier = Modifier,
            )
            Text("Expression", Modifier.padding(start = 10.dp))
            Spacer(Modifier.weight(1f))
            Text(selected.localizedName, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            BotAvatarExpression.entries.forEach { expression ->
                DropdownMenuItem(
                    text = { Text(expression.localizedName) },
                    leadingIcon = {
                        BotAvatarMarkView(
                            name = name,
                            appearance = appearance.copy(expression = expression.rawValue),
                            size = 34.dp,
                            modifier = Modifier,
                        )
                    },
                    onClick = {
                        onExpression(expression)
                        expanded = false
                    },
                )
            }
        }
    }
}

/**
 * Direct-Hermes adapter around the existing stateless model picker. The editor supplies the
 * inventory returned by `model.options`; this wrapper performs no webui request.
 */
@Composable
internal fun BotModelPickerSheet(
    modelGroups: List<ModelCatalogGroup>,
    selected: ModelCatalogOption?,
    allowsHostDefault: Boolean,
    onSelect: (ModelCatalogOption?) -> Unit,
    onDismiss: () -> Unit,
) {
    var picker by remember(modelGroups, selected?.id, selected?.providerID) {
        mutableStateOf(
            ModelPickerUiState(
                modelGroups = modelGroups,
                selectedModelID = selected?.id,
                selectedModelProviderID = selected?.providerID,
            ),
        )
    }
    PickerSheetScaffold(title = "Choose Model", onDismiss = onDismiss) {
        ModelPickerContent(
            state = picker,
            onSearchTextChange = { value ->
                picker = picker.copy(
                    searchText = value,
                    expansion = picker.expansion.updatingSearchText(value),
                )
            },
            onCustomModelIDChange = { picker = picker.copy(customModelID = it) },
            onCustomProviderIDChange = { picker = picker.copy(customProviderID = it) },
            onGroupExpandedChange = { id, expanded ->
                picker = picker.copy(expansion = picker.expansion.settingExpanded(expanded, id))
            },
            onToggleFavorite = { option ->
                val key = option.favoriteKey
                picker = picker.copy(
                    favoriteKeys = if (key in picker.favoriteKeys) picker.favoriteKeys - key else listOf(key) + picker.favoriteKeys,
                )
            },
            onDeleteSavedCustom = { option ->
                val key = option.favoriteKey
                picker = picker.copy(
                    favoriteKeys = picker.favoriteKeys - key,
                    recentKeys = picker.recentKeys - key,
                )
            },
            onRetry = onDismiss,
            clearAction = if (allowsHostDefault) {
                ModelPickerClearAction(
                    title = "Host default",
                    isSelected = selected == null,
                    action = {
                        onSelect(null)
                        onDismiss()
                    },
                )
            } else {
                null
            },
            onSelect = { option ->
                onSelect(option)
                onDismiss()
            },
        )
    }
}
