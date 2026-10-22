package io.github.jcartu.hermex.ui.feature.tasks

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jcartu.hermex.model.CronDeliverPicker
import io.github.jcartu.hermex.model.CronDeliveryOption
import io.github.jcartu.hermex.model.CronJobEditorDraft
import io.github.jcartu.hermex.ui.feature.models.ModelPickerClearAction
import io.github.jcartu.hermex.ui.feature.models.ModelPickerSheet
import kotlinx.coroutines.launch

private const val EDITOR_MAX_WIDTH_DP = 560

/**
 * The create and edit sheet for a scheduled task. Both entry points — the
 * Tasks toolbar's "New Task" and a task's own Edit action — present this same
 * sheet, so the Configuration section's model and profile pickers are loaded
 * and behave identically in each.
 *
 * On a Hermes host (#1040) the sheet has no toast setting, its sources follow
 * the Task's Profile, and an existing Task's Profile can't change
 * (`locksProfile`).
 * Port of `Features/Tasks/CronJobEditorSheet.swift`.
 */
@Composable
fun CronJobEditorSheet(
    title: String,
    initialDraft: CronJobEditorDraft,
    saveTitle: String,
    isSaving: Boolean,
    errorMessage: String?,
    deliveryOptions: List<CronDeliveryOption>?,
    client: CronDataClient,
    locksProfile: Boolean = false,
    onDismiss: () -> Unit,
    onSave: suspend (CronJobEditorDraft) -> Boolean,
) {
    val scope = rememberCoroutineScope()
    var draft by remember { mutableStateOf(initialDraft) }
    var isPresentingModelPicker by rememberSaveable { mutableStateOf(false) }
    var isPresentingProfilePicker by rememberSaveable { mutableStateOf(false) }
    var isPresentingSkillsPicker by rememberSaveable { mutableStateOf(false) }

    val isProfileScoped = client.cronFeatures.isProfileScoped
    val hasToastNotifications = client.cronFeatures.hasToastNotifications

    // The draft's deliver value when the editor opened; stable across re-inits.
    val initialDeliver = remember { initialDraft.deliver }
    val serverDeliveryOptions = deliveryOptions

    // The Configuration section's model catalog and profile list, loaded when
    // this sheet opens. On a server that scopes by Profile, one loader serves
    // one Profile and a new one is made when the Profile changes.
    val loaderProfile = if (isProfileScoped) draft.trimmedProfile else null
    val configuration: CronJobEditorConfigurationLoader = viewModel(
        key = "cron-editor-config:${loaderProfile.orEmpty()}",
        factory = viewModelFactory {
            initializer { CronJobEditorConfigurationLoader(client = client, profile = loaderProfile) }
        },
    )
    val configurationState by configuration.uiState.collectAsStateWithLifecycle()

    // The draft when the user picked another Profile, until that Profile's lists
    // load and the choices made for the old one are checked (#1040).
    var draftAtProfilePick by remember { mutableStateOf<CronJobEditorDraft?>(null) }
    var previousProfile by remember { mutableStateOf(draft.profile) }

    LaunchedEffect(draft.profile) {
        val changed = draft.profile != previousProfile
        previousProfile = draft.profile
        if (isProfileScoped && changed && draftAtProfilePick == null) {
            draftAtProfilePick = draft
        }
    }

    // A new loader is a new Profile's sources, so its loads start over. Once a
    // picked Profile's lists arrive, the earlier targets and skills it lacks go.
    LaunchedEffect(configuration) {
        configuration.load()
        val earlier = draftAtProfilePick ?: return@LaunchedEffect
        draftAtProfilePick = null
        val next = draft.copy()
        next.keepChoices(
            madeBefore = earlier,
            offeredTargets = configuration.uiState.value.deliveryOptions?.mapNotNull { it.value },
            offeredSkills = if (configuration.uiState.value.skillsErrorMessage == null) {
                configuration.uiState.value.skills.mapNotNull { it.name }
            } else {
                null
            },
        )
        draft = next
    }

    val deliverPickerOptions = CronDeliverPicker.options(
        serverOptions = configurationState.deliveryOptions ?: serverDeliveryOptions,
        currentValue = draft.deliver,
        initialValue = initialDeliver,
    )

    val modelSelection = CronJobModelSelection.resolve(
        modelID = draft.model,
        providerID = draft.provider,
        groups = configurationState.modelGroups,
    )

    val formMessage = errorMessage ?: draft.validationMessage
    val messageColor = if (errorMessage == null) {
        MaterialTheme.colorScheme.onSurfaceVariant
    } else {
        MaterialTheme.colorScheme.error
    }

    Dialog(onDismissRequest = { if (!isSaving) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(Modifier.fillMaxSize()) {
                // Header: Cancel | title | Save.
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onDismiss, enabled = !isSaving) { Text("Cancel") }
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = {
                            scope.launch {
                                if (onSave(draft)) onDismiss()
                            }
                        },
                        enabled = !isSaving && draft.validationMessage == null,
                    ) {
                        if (isSaving) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        } else {
                            Text(saveTitle)
                        }
                    }
                }

                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .widthIn(max = EDITOR_MAX_WIDTH_DP.dp)
                        .align(Alignment.CenterHorizontally)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    EditorSectionLabel("Task")
                    OutlinedTextField(
                        value = draft.name,
                        onValueChange = { draft = draft.copy(name = it) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("Name") },
                    )
                    OutlinedTextField(
                        value = draft.prompt,
                        onValueChange = { draft = draft.copy(prompt = it) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        minLines = 3,
                        maxLines = 8,
                        label = { Text("Prompt") },
                    )
                    OutlinedTextField(
                        value = draft.schedule,
                        onValueChange = { draft = draft.copy(schedule = it) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        singleLine = true,
                        label = { Text("Schedule") },
                    )

                    Spacer(Modifier.padding(top = 16.dp))
                    EditorSectionLabel("Delivery")
                    if (deliverPickerOptions != null) {
                        DeliverPicker(
                            options = deliverPickerOptions,
                            selected = draft.deliver,
                            onSelect = { draft = draft.copy(deliver = it) },
                        )
                    } else {
                        OutlinedTextField(
                            value = draft.deliver,
                            onValueChange = { draft = draft.copy(deliver = it) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text("Deliver") },
                        )
                    }

                    if (hasToastNotifications) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "Toast Notifications",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                            Switch(
                                checked = draft.toastNotifications,
                                onCheckedChange = { draft = draft.copy(toastNotifications = it) },
                            )
                        }
                    }

                    Spacer(Modifier.padding(top = 16.dp))
                    EditorSectionLabel("Configuration")
                    CronJobSkillsRow(
                        selection = draft.skills,
                        errorMessage = configurationState.skillsErrorMessage,
                        isLoading = configurationState.isLoadingSkills,
                        action = { isPresentingSkillsPicker = true },
                        onRetry = { scope.launch { configuration.loadSkills() } },
                    )
                    CronJobModelRow(selection = modelSelection, action = { isPresentingModelPicker = true })
                    CronJobProfileRow(
                        profileName = draft.profile,
                        profiles = configurationState.profiles,
                        errorMessage = configurationState.profilesErrorMessage,
                        isLoading = configurationState.isLoadingProfiles,
                        isLocked = locksProfile,
                        action = { isPresentingProfilePicker = true },
                        onRetry = { scope.launch { configuration.loadProfiles() } },
                    )
                    Text(
                        // States what upstream already does rather than
                        // reimplementing it: `_selected_profile_snapshot_updates`
                        // fills in the model from the profile only while the model
                        // is blank, so the app must not prefill it.
                        "Leaving the model on Server default lets the server use the selected profile's model.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 4.dp),
                    )

                    if (formMessage != null) {
                        HorizontalDivider(Modifier.padding(vertical = 12.dp))
                        Text(
                            formMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = messageColor,
                        )
                    }
                }
            }
        }
    }

    if (isPresentingModelPicker) {
        ModelPickerSheet(
            onSelect = { option ->
                val next = draft.copy()
                next.applyModelSelection(option)
                draft = next
            },
            onDismiss = { isPresentingModelPicker = false },
            currentModel = draft.trimmedModel,
            currentModelProvider = draft.trimmedProvider,
            clearAction = ModelPickerClearAction(
                title = "Server default",
                isSelected = draft.trimmedModel == null,
                action = {
                    val next = draft.copy()
                    next.applyModelSelection(null)
                    draft = next
                },
            ),
        )
    }

    if (isPresentingSkillsPicker) {
        CronJobSkillsPickerSheet(
            skills = configurationState.skills,
            selection = draft.skills,
            isLoading = configurationState.isLoadingSkills,
            errorMessage = configurationState.skillsErrorMessage,
            onRetry = { scope.launch { configuration.loadSkills() } },
            onToggle = { name ->
                val next = draft.copy()
                next.applySkillSelection(CronJobEditorDraft.togglingSkill(name, draft.skills))
                draft = next
            },
            onAddCustom = { name ->
                val next = draft.copy()
                next.applySkillSelection(CronJobEditorDraft.togglingSkill(name, draft.skills))
                draft = next
            },
            onClear = {
                val next = draft.copy()
                next.applySkillSelection(emptyList())
                draft = next
            },
            onDismiss = { isPresentingSkillsPicker = false },
        )
    }

    if (isPresentingProfilePicker) {
        CronJobProfilePickerSheet(
            profiles = configurationState.profiles,
            selectedProfileName = draft.profile,
            isLoading = configurationState.isLoadingProfiles,
            errorMessage = configurationState.profilesErrorMessage,
            onRetry = { scope.launch { configuration.loadProfiles() } },
            onSelect = { name ->
                val next = draft.copy()
                next.applyProfileSelection(name)
                draft = next
            },
            onDismiss = { isPresentingProfilePicker = false },
        )
    }
}

@Composable
private fun DeliverPicker(
    options: List<io.github.jcartu.hermex.model.CronDeliverPickerOption>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        TextButton(onClick = { expanded = true }) {
            Text(
                options.firstOrNull { it.value == selected }?.label?.let { label ->
                    if (options.firstOrNull { it.value == selected }?.isCustom == true) "$label (custom)" else label
                } ?: selected.ifEmpty { "Select..." },
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(if (option.isCustom) "${option.label} (custom)" else option.label) },
                    onClick = {
                        expanded = false
                        onSelect(option.value)
                    },
                )
            }
        }
    }
}

@Composable
private fun EditorSectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}
