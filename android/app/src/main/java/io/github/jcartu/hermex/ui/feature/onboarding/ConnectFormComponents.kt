package io.github.jcartu.hermex.ui.feature.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.jcartu.hermex.net.CustomHeader

/**
 * Port of `CustomHeadersEditor.Style`: both variants follow the user's theme;
 * onboarding retains its lighter strokes and alpha hierarchy.
 */
enum class ConnectFormStyle {
    ONBOARDING,
    SETTINGS;

    val primaryText: Color
        @Composable get() = if (this == ONBOARDING) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onSurface

    val secondaryText: Color
        @Composable get() = if (this == ONBOARDING) MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f) else MaterialTheme.colorScheme.onSurfaceVariant

    val fieldStroke: Color
        @Composable get() = if (this == ONBOARDING) MaterialTheme.colorScheme.onBackground.copy(alpha = 0.1f) else MaterialTheme.colorScheme.outlineVariant

    val accent: Color
        @Composable get() = if (this == ONBOARDING) OnboardingAccent else MaterialTheme.colorScheme.primary

    val placeholder: Color
        @Composable get() = if (this == ONBOARDING) MaterialTheme.colorScheme.onBackground.copy(alpha = 0.38f) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
}

/**
 * Port of `ConnectionModePicker` (`OnboardingComponents.swift`, #900): the
 * connect form's connection modes as three choice rows, shared by onboarding
 * and Add Server. Each row says what its mode means and wraps at any width;
 * the selected one has a checkmark. Choosing changes only the form's help,
 * placeholder and header rows.
 */
@Composable
fun ConnectionModePicker(
    selection: ConnectionMode,
    onSelect: (ConnectionMode) -> Unit,
    style: ConnectFormStyle,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Column(modifier.fillMaxWidth()) {
        ConnectionMode.entries.forEachIndexed { index, mode ->
            if (index > 0) {
                Box(Modifier.fillMaxWidth().height(0.5.dp).background(style.fieldStroke))
            }
            val isSelected = selection == mode
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { selected = isSelected }
                    .clickable(enabled = enabled) { onSelect(mode) }
                    .padding(vertical = 10.dp),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        mode.title,
                        color = style.primaryText,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(mode.subtitle, color = style.secondaryText, style = MaterialTheme.typography.bodySmall)
                }
                Icon(
                    Icons.Filled.Check,
                    contentDescription = null,
                    tint = if (isSelected) style.accent else Color.Transparent,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

/**
 * Port of `Shared/CustomHeadersEditor.swift`: name/value rows with add and
 * remove; values are masked until revealed (they may be secrets). Edits flow
 * back as the whole list; the owner decides when to persist (#255).
 */
@Composable
fun CustomHeadersEditor(
    headers: List<CustomHeader>,
    onChange: (List<CustomHeader>) -> Unit,
    style: ConnectFormStyle,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (headers.isEmpty()) {
            Text(
                "No custom headers.",
                color = style.secondaryText,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        headers.forEachIndexed { index, header ->
            HeaderRow(
                header = header,
                style = style,
                enabled = enabled,
                onNameChange = { name -> onChange(headers.toMutableList().also { it[index] = header.copy(name = name) }) },
                onValueChange = { value -> onChange(headers.toMutableList().also { it[index] = header.copy(value = value) }) },
                onRemove = { onChange(headers.toMutableList().also { it.removeAt(index) }) },
            )
        }
        TextButton(onClick = { onChange(headers + CustomHeader()) }, enabled = enabled) {
            Icon(Icons.Filled.Add, contentDescription = null, tint = style.accent, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Add header", color = style.accent)
        }
    }
}

@Composable
private fun HeaderRow(
    header: CustomHeader,
    style: ConnectFormStyle,
    enabled: Boolean,
    onNameChange: (String) -> Unit,
    onValueChange: (String) -> Unit,
    onRemove: () -> Unit,
) {
    var revealed by rememberSaveable { mutableStateOf(false) }
    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, style.fieldStroke, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        ConnectTextField(
            value = header.name,
            onValueChange = onNameChange,
            placeholder = "Header name",
            style = style,
            enabled = enabled,
            keyboardType = KeyboardType.Ascii,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            ConnectTextField(
                value = header.value,
                onValueChange = onValueChange,
                placeholder = "Value",
                style = style,
                enabled = enabled,
                secure = !revealed,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { revealed = !revealed }) {
                Icon(
                    if (revealed) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                    contentDescription = if (revealed) "Hide value" else "Show value",
                    tint = style.secondaryText,
                    modifier = Modifier.size(18.dp),
                )
            }
            IconButton(onClick = onRemove, enabled = enabled) {
                Icon(Icons.Filled.Delete, contentDescription = "Remove header", tint = style.secondaryText, modifier = Modifier.size(18.dp))
            }
        }
    }
}

/** The connect form's single-line field: a plain text field with a placeholder, in either style. */
@Composable
fun ConnectTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    style: ConnectFormStyle,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    secure: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    keyboardOptions: KeyboardOptions = KeyboardOptions(keyboardType = keyboardType, autoCorrectEnabled = false),
    keyboardActions: androidx.compose.foundation.text.KeyboardActions = androidx.compose.foundation.text.KeyboardActions.Default,
) {
    Box(modifier) {
        if (value.isEmpty()) {
            Text(placeholder, color = style.placeholder, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            singleLine = true,
            textStyle = TextStyle(color = style.primaryText, fontSize = 15.sp, fontWeight = FontWeight.Medium),
            cursorBrush = SolidColor(style.accent),
            visualTransformation = if (secure) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
