package io.github.jcartu.hermex.ui.feature.bots

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Widgets
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jcartu.hermex.model.ModelCatalogOption
import io.github.jcartu.hermex.model.hermes.BotAvatarExpression
import io.github.jcartu.hermex.model.hermes.BotAvatarShape
import io.github.jcartu.hermex.model.hermes.BotProfileCapability
import io.github.jcartu.hermex.ui.adaptive.HingeAwarePaneRow
import io.github.jcartu.hermex.ui.adaptive.LocalPaneCount
import io.github.jcartu.hermex.ui.components.ErrorState
import io.github.jcartu.hermex.ui.components.LoadingState
import io.github.jcartu.hermex.ui.components.SectionCard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/** Route-facing Profile editor. All connection and write ownership remains in [BotProfileEditor]. */
@Composable
fun BotProfileEditorScreen(
    editor: BotProfileEditor,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by editor.state.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var capability by remember { mutableStateOf<BotProfileEditor.Field?>(null) }
    var showsModels by remember { mutableStateOf(false) }
    var imageError by remember { mutableStateOf<String?>(null) }
    var cue by remember { mutableStateOf<BotFaceCue?>(null) }

    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        coroutineScope.launch {
            try {
                val bytes = context.readSelectedPhoto(uri)
                editor.selectAvatar(bytes)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                imageError = error.message ?: "Could not decode this image."
            }
        }
    }

    DisposableEffect(lifecycleOwner, editor) {
        fun resume() {
            if (editor.current.loadState is BotProfileEditor.LoadState.Idle) {
                editor.loadAsync()
            }
        }
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) resume()
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> resume()
                Lifecycle.Event.ON_STOP -> editor.suspendForBackground()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            editor.close()
        }
    }

    BackHandler {
        if (!state.isSaving) onDone()
    }

    BotProfileEditorContent(
        state = state,
        connection = editor.connection,
        actions = BotProfileEditorContentActions(
            onDone = onDone,
            onRetryLoad = { editor.loadAsync() },
            onSave = { editor.saveAsync() },
            onTitle = {
                editor.setTitle(it)
                cue = BotFaceCue(BotFaceBit.GLANCE_DOWN)
            },
            onDescription = editor::setDescription,
            onInstructions = editor::setInstructions,
            onShape = {
                editor.setShape(it)
                cue = BotFaceCue(BotFaceBit.HOP)
            },
            onColor = {
                editor.setColor(it)
                cue = BotFaceCue(BotFaceBit.WOBBLE)
            },
            onExpression = editor::setExpression,
            onResetAppearance = editor::resetAppearance,
            onChoosePhoto = {
                photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            },
            onRemovePhoto = editor::removeAvatar,
            onModel = { showsModels = true },
            onCapabilities = { capability = it },
            onReloadAppearance = { editor.reloadAppearanceAsync() },
        ),
        cue = cue,
        modifier = modifier,
    )

    capability?.let { field ->
        BotCapabilityDialog(
            title = field.title,
            values = state.capabilities(field),
            onToggle = { id, enabled -> editor.setEnabled(enabled, field, id) },
            onDismiss = { capability = null },
        )
    }
    if (showsModels) {
        BotModelPickerSheet(
            modelGroups = state.modelGroups,
            selected = state.draft.model,
            allowsHostDefault = false,
            onSelect = { option -> if (option != null) editor.setModel(option) },
            onDismiss = { showsModels = false },
        )
    }
    state.confirmation?.let { confirmation ->
        AlertDialog(
            onDismissRequest = editor::declineModelChange,
            title = { Text("Confirm this model change?") },
            text = { Text(confirmation.message) },
            confirmButton = {
                TextButton(onClick = { editor.confirmModelChangeAsync() }) { Text("Continue") }
            },
            dismissButton = {
                TextButton(onClick = editor::declineModelChange) { Text("Cancel") }
            },
        )
    }
    imageError?.let { message ->
        AlertDialog(
            onDismissRequest = { imageError = null },
            title = { Text("Could not decode this image.") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { imageError = null }) { Text("OK") } },
        )
    }
}

data class BotProfileEditorContentActions(
    val onDone: () -> Unit = {},
    val onRetryLoad: () -> Unit = {},
    val onSave: () -> Unit = {},
    val onTitle: (String) -> Unit = {},
    val onDescription: (String) -> Unit = {},
    val onInstructions: (String) -> Unit = {},
    val onShape: (BotAvatarShape) -> Unit = {},
    val onColor: (String) -> Unit = {},
    val onExpression: (BotAvatarExpression) -> Unit = {},
    val onResetAppearance: () -> Unit = {},
    val onChoosePhoto: () -> Unit = {},
    val onRemovePhoto: () -> Unit = {},
    val onModel: () -> Unit = {},
    val onCapabilities: (BotProfileEditor.Field) -> Unit = {},
    val onReloadAppearance: () -> Unit = {},
)

/** Pure editor UI: callers can compose a [BotProfileEditor.State] without a transport. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BotProfileEditorContent(
    state: BotProfileEditor.State,
    connection: io.github.jcartu.hermex.model.hermes.BotConnection,
    modifier: Modifier = Modifier,
    actions: BotProfileEditorContentActions = BotProfileEditorContentActions(),
    cue: BotFaceCue? = null,
) {
    val paneCount = LocalPaneCount.current.coerceIn(1, 3)
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(state.profile.name) },
                navigationIcon = {
                    IconButton(onClick = actions.onDone, enabled = !state.isSaving) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    TextButton(onClick = actions.onSave, enabled = state.canSave) {
                        Text("Save", fontWeight = FontWeight.SemiBold)
                    }
                },
            )
        },
    ) { padding ->
        when (val load = state.loadState) {
            BotProfileEditor.LoadState.Idle, BotProfileEditor.LoadState.Loading ->
                LoadingState(Modifier.padding(padding), "Loading profile...")
            is BotProfileEditor.LoadState.Failed ->
                ErrorState(load.message, Modifier.padding(padding), onRetry = actions.onRetryLoad)
            BotProfileEditor.LoadState.Loaded -> {
                if (paneCount == 1) {
                    EditorPane(Modifier.fillMaxSize().padding(padding)) {
                        EditorIdentity(state, connection.name, cue, actions)
                        EditorAppearance(state, actions)
                        EditorDetails(state, actions)
                        EditorResults(state, actions)
                        EditorCapabilities(state, actions)
                        EditorServer(connection)
                    }
                } else {
                    HingeAwarePaneRow(
                        paneCount = paneCount,
                        modifier = Modifier.fillMaxSize().padding(padding),
                    ) { index, paneModifier ->
                        Surface(paneModifier, color = MaterialTheme.colorScheme.background) {
                            EditorPane(Modifier.fillMaxSize()) {
                                when {
                                    index == 0 -> {
                                        EditorIdentity(state, connection.name, cue, actions)
                                        EditorAppearance(state, actions)
                                    }
                                    paneCount == 2 || index == 1 -> {
                                        EditorDetails(state, actions)
                                        if (paneCount == 2) {
                                            EditorResults(state, actions)
                                            EditorCapabilities(state, actions)
                                            EditorServer(connection)
                                        }
                                    }
                                    else -> {
                                        EditorResults(state, actions)
                                        EditorCapabilities(state, actions)
                                        EditorServer(connection)
                                    }
                                }
                            }
                        }
                    }
                }
                if (state.isSaving) {
                    Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                        Surface(shape = CircleShape, tonalElevation = 8.dp) {
                            CircularProgressIndicator(Modifier.padding(18.dp).size(28.dp), strokeWidth = 3.dp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EditorPane(modifier: Modifier, content: @Composable () -> Unit) {
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(PaddingValues(horizontal = 18.dp, vertical = 16.dp)),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        content()
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun EditorIdentity(
    state: BotProfileEditor.State,
    connectionName: String,
    cue: BotFaceCue?,
    actions: BotProfileEditorContentActions,
) {
    var menu by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box {
            if (state.avatar != null) {
                Photo(state.avatar, Modifier.size(104.dp))
            } else {
                BotInteractiveFaceView(
                    name = state.profile.id,
                    appearance = state.draft.appearance,
                    size = 104.dp,
                    cue = cue,
                    modifier = Modifier,
                )
            }
            Surface(
                onClick = { menu = true },
                enabled = !state.isSaving,
                modifier = Modifier.align(Alignment.BottomEnd).size(34.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                tonalElevation = 4.dp,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.CameraAlt, contentDescription = "Change Photo", Modifier.size(18.dp))
                }
            }
            DropdownMenu(expanded = menu && !state.isSaving, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text("Choose Photo") },
                    leadingIcon = { Icon(Icons.Outlined.CameraAlt, contentDescription = null) },
                    onClick = { menu = false; actions.onChoosePhoto() },
                    enabled = !state.isSaving,
                )
                if (state.avatar != null) {
                    DropdownMenuItem(
                        text = { Text("Remove Photo") },
                        leadingIcon = { Icon(Icons.Outlined.Delete, contentDescription = null) },
                        onClick = { menu = false; actions.onRemovePhoto() },
                        enabled = !state.isSaving,
                    )
                }
            }
        }
        Text(
            state.draft.appearance.title.ifBlank { state.profile.name },
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 10.dp),
        )
        if (state.draft.description.isNotEmpty()) {
            Text(
                state.draft.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        Text(
            "${state.profile.id} · $connectionName",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
    SectionCard("Identity") {
        OutlinedTextField(
            value = state.draft.appearance.title,
            onValueChange = actions.onTitle,
            enabled = !state.isSaving,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Display Name") },
            minLines = 1,
            maxLines = 3,
        )
    }
}

@Composable
private fun Photo(bitmap: Bitmap, modifier: Modifier = Modifier) {
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    Image(
        bitmap = image,
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = modifier.clip(RoundedCornerShape(22.dp)),
    )
}

@Composable
private fun EditorAppearance(state: BotProfileEditor.State, actions: BotProfileEditorContentActions) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Appearance", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        BotAppearancePicker(
            name = state.profile.id,
            appearance = state.draft.appearance,
            enabled = !state.isSaving,
            onShape = actions.onShape,
            onColor = actions.onColor,
            onExpression = actions.onExpression,
            onReset = actions.onResetAppearance,
        )
    }
}

@Composable
private fun EditorDetails(state: BotProfileEditor.State, actions: BotProfileEditorContentActions) {
    SectionCard("Description") {
        OutlinedTextField(
            value = state.draft.description,
            onValueChange = actions.onDescription,
            enabled = !state.isSaving,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Description") },
            minLines = 2,
            maxLines = 4,
        )
        OutlinedTextField(
            value = state.draft.instructions,
            onValueChange = actions.onInstructions,
            enabled = !state.isSaving,
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            label = { Text("Instructions") },
            minLines = 6,
            maxLines = 14,
        )
        Text(
            "Saving replaces this Profile’s complete instructions.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
    SectionCard("Model") {
        ModelChoiceRow(
            model = state.draft.model,
            enabled = !state.isSaving,
            emptyTitle = "—",
            onClick = actions.onModel,
        )
    }
}

@Composable
private fun EditorCapabilities(state: BotProfileEditor.State, actions: BotProfileEditorContentActions) {
    SectionCard("Capabilities") {
        CapabilityButton(FieldIcon.SKILLS, BotProfileEditor.Field.SKILLS, state.draft.skills, state.isSaving, actions)
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        CapabilityButton(FieldIcon.TOOLSETS, BotProfileEditor.Field.TOOLSETS, state.draft.toolsets, state.isSaving, actions)
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        CapabilityButton(FieldIcon.MCP, BotProfileEditor.Field.MCP_SERVERS, state.draft.mcpServers, state.isSaving, actions)
    }
}

private enum class FieldIcon { SKILLS, TOOLSETS, MCP }

@Composable
private fun CapabilityButton(
    icon: FieldIcon,
    field: BotProfileEditor.Field,
    values: List<BotProfileCapability>,
    saving: Boolean,
    actions: BotProfileEditorContentActions,
) {
    OutlinedButton(
        onClick = { actions.onCapabilities(field) },
        enabled = !saving,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(
            when (icon) {
                FieldIcon.SKILLS -> Icons.Outlined.Widgets
                FieldIcon.TOOLSETS -> Icons.Outlined.Settings
                FieldIcon.MCP -> Icons.Outlined.Storage
            },
            contentDescription = null,
        )
        Column(Modifier.weight(1f).padding(start = 10.dp), horizontalAlignment = Alignment.Start) {
            Text(field.title)
            Text(
                values.filter { it.enabled }.take(3).joinToString { it.name },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        Text("${values.count { it.enabled }}/${values.size}")
    }
}

@Composable
private fun EditorResults(state: BotProfileEditor.State, actions: BotProfileEditorContentActions) {
    if (state.outcomes.isEmpty()) return
    val needsAttention = state.outcomes.values.any { it !is BotProfileEditor.Outcome.Saved }
    SectionCard(if (needsAttention) "Needs Attention" else "Saved") {
        BotProfileEditor.Field.entries.forEach { field ->
            val outcome = state.outcomes[field] ?: return@forEach
            Text(field.title, fontWeight = FontWeight.SemiBold)
            val (message, color) = when (outcome) {
                BotProfileEditor.Outcome.Saved -> "Saved" to MaterialTheme.colorScheme.primary
                is BotProfileEditor.Outcome.Failed -> outcome.message to MaterialTheme.colorScheme.error
                BotProfileEditor.Outcome.Conflict ->
                    "This bot changed in Hermes Desktop. Reload its latest appearance before saving again." to
                        MaterialTheme.colorScheme.error
                BotProfileEditor.Outcome.ConfirmationRequired -> "Needs Attention" to MaterialTheme.colorScheme.tertiary
            }
            Text(message, style = MaterialTheme.typography.bodySmall, color = color)
            if (outcome is BotProfileEditor.Outcome.Conflict) {
                TextButton(onClick = actions.onReloadAppearance, enabled = !state.isSaving) {
                    Icon(Icons.Outlined.Refresh, contentDescription = null)
                    Text("Reload", Modifier.padding(start = 6.dp))
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun EditorServer(connection: io.github.jcartu.hermex.model.hermes.BotConnection) {
    SectionCard("Server") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Memory, contentDescription = null)
            Column(Modifier.padding(start = 10.dp)) {
                Text(connection.name)
                Text(
                    connection.address.host,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun BotCapabilityDialog(
    title: String,
    values: List<BotProfileCapability>,
    onToggle: (id: String, enabled: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.fillMaxWidth().widthIn(max = 480.dp).heightIn(max = 640.dp),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Column(
                Modifier.verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                values.forEach { value ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(value.name)
                            value.detail?.takeIf(String::isNotEmpty)?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Switch(
                            checked = value.enabled,
                            onCheckedChange = { enabled -> onToggle(value.id, enabled) },
                        )
                    }
                }
                Button(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text("Done") }
            }
        }
    }
}

private fun BotProfileEditor.State.capabilities(field: BotProfileEditor.Field): List<BotProfileCapability> = when (field) {
    BotProfileEditor.Field.SKILLS -> draft.skills
    BotProfileEditor.Field.TOOLSETS -> draft.toolsets
    BotProfileEditor.Field.MCP_SERVERS -> draft.mcpServers
    else -> emptyList()
}

private suspend fun Context.readSelectedPhoto(uri: Uri): ByteArray = withContext(Dispatchers.IO) {
    contentResolver.openInputStream(uri)?.use { input ->
        val output = ByteArrayOutputStream(minOf(BotProfileEditor.MAX_PHOTO_SOURCE_BYTES, 64 * 1024))
        val buffer = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > BotProfileEditor.MAX_PHOTO_SOURCE_BYTES) throw BotProfileEditorImageException()
            output.write(buffer, 0, read)
        }
        output.toByteArray()
    } ?: throw BotProfileEditorImageException()
}
