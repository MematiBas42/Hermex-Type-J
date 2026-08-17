package io.github.jcartu.hermex.ui.feature.tasks

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.jcartu.hermex.model.CronDeliverPicker
import io.github.jcartu.hermex.model.CronDeliveryOption
import io.github.jcartu.hermex.model.CronJobEditorDraft

/**
 * Create/edit form for a cron job. Port of Swift `CronJobEditorSheet`
 * (Features/Tasks/TasksView.swift): Task (name/prompt/schedule), Delivery
 * (server-listed picker with custom-value fallback to free text, toast
 * toggle), Configuration (skills/model/provider/profile), and a footer message
 * that shows the server error (red) or the draft validation hint (secondary).
 *
 * The sheet stays open while [isSaving]; the caller dismisses it when the save
 * succeeds, mirroring the iOS `if await onSave(draft) { dismiss() }`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CronJobEditorSheet(
    title: String,
    initialDraft: CronJobEditorDraft,
    saveTitle: String,
    isSaving: Boolean,
    errorMessage: String?,
    deliveryOptions: List<CronDeliveryOption>?,
    onDismiss: () -> Unit,
    onSave: (CronJobEditorDraft) -> Unit,
) {
    var draft by remember { mutableStateOf(initialDraft.copy()) }
    // The draft's deliver value when the editor opened; keeps an unknown or
    // legacy value selectable even after picking a server option.
    val initialDeliver = remember { initialDraft.deliver }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val formMessage = errorMessage ?: draft.validationMessage
    val messageColor = if (errorMessage == null) {
        MaterialTheme.colorScheme.onSurfaceVariant
    } else {
        MaterialTheme.colorScheme.error
    }

    ModalBottomSheet(
        onDismissRequest = { if (!isSaving) onDismiss() },
        sheetState = sheetState,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onDismiss, enabled = !isSaving) { Text("Cancel") }
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                )
                Button(
                    onClick = { onSave(draft) },
                    enabled = !isSaving && draft.validationMessage == null,
                ) {
                    if (isSaving) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Text(saveTitle)
                    }
                }
            }

            EditorSectionLabel("Task")
            OutlinedTextField(
                value = draft.name,
                onValueChange = { draft = draft.copy(name = it) },
                label = { Text("Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            OutlinedTextField(
                value = draft.prompt,
                onValueChange = { draft = draft.copy(prompt = it) },
                label = { Text("Prompt") },
                minLines = 3,
                maxLines = 8,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            OutlinedTextField(
                value = draft.schedule,
                onValueChange = { draft = draft.copy(schedule = it) },
                label = { Text("Schedule") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )

            EditorSectionLabel("Delivery")
            val pickerOptions = CronDeliverPicker.options(
                serverOptions = deliveryOptions,
                currentValue = draft.deliver,
                initialValue = initialDeliver,
            )
            if (pickerOptions != null) {
                var pickerExpanded by remember { mutableStateOf(false) }
                val currentOption = pickerOptions.firstOrNull { it.value == draft.deliver.trim() }
                Box(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    OutlinedButton(
                        onClick = { pickerExpanded = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            currentOption?.let { option ->
                                if (option.isCustom) "${option.label} (custom)" else option.label
                            } ?: draft.deliver,
                            maxLines = 1,
                            modifier = Modifier.weight(1f),
                        )
                        Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
                    }
                    DropdownMenu(
                        expanded = pickerExpanded,
                        onDismissRequest = { pickerExpanded = false },
                    ) {
                        pickerOptions.forEach { option ->
                            DropdownMenuItem(
                                text = {
                                    Text(if (option.isCustom) "${option.label} (custom)" else option.label)
                                },
                                onClick = {
                                    draft = draft.copy(deliver = option.value)
                                    pickerExpanded = false
                                },
                            )
                        }
                    }
                }
            } else {
                OutlinedTextField(
                    value = draft.deliver,
                    onValueChange = { draft = draft.copy(deliver = it) },
                    label = { Text("Deliver") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Toast Notifications", style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.weight(1f))
                Switch(
                    checked = draft.toastNotifications,
                    onCheckedChange = { draft = draft.copy(toastNotifications = it) },
                )
            }

            EditorSectionLabel("Configuration")
            OutlinedTextField(
                value = draft.skillsText,
                onValueChange = { draft = draft.copy(skillsText = it) },
                label = { Text("Skills") },
                minLines = 1,
                maxLines = 4,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            OutlinedTextField(
                value = draft.model,
                onValueChange = { draft = draft.copy(model = it) },
                label = { Text("Model") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            OutlinedTextField(
                value = draft.provider,
                onValueChange = { draft = draft.copy(provider = it) },
                label = { Text("Provider") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            OutlinedTextField(
                value = draft.profile,
                onValueChange = { draft = draft.copy(profile = it) },
                label = { Text("Profile") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )

            if (formMessage != null) {
                Text(
                    formMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = messageColor,
                    modifier = Modifier.padding(top = 16.dp),
                )
            }
        }
    }
}

@Composable
private fun EditorSectionLabel(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 20.dp),
    )
}
