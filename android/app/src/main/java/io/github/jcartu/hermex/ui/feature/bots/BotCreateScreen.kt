package io.github.jcartu.hermex.ui.feature.bots

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jcartu.hermex.model.ModelCatalogOption
import io.github.jcartu.hermex.model.hermes.BotAvatarExpression
import io.github.jcartu.hermex.model.hermes.BotAvatarShape
import io.github.jcartu.hermex.ui.adaptive.HingeAwarePaneRow
import io.github.jcartu.hermex.ui.adaptive.LocalPaneCount
import io.github.jcartu.hermex.ui.components.SectionCard

/** Route-facing create/duplicate surface. Dependencies stay in [BotCreator], so no network is read here. */
@Composable
fun BotCreateScreen(
    creator: BotCreator,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by creator.state.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    var showsModels by remember { mutableStateOf(false) }
    var cue by remember { mutableStateOf<BotFaceCue?>(null) }

    DisposableEffect(lifecycleOwner, creator) {
        fun resume() {
            val current = creator.current
            if (current.phase == BotCreator.Phase.EDITING && !current.holdsConnection && !current.isLoading) {
                creator.loadAsync()
            }
        }
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) resume()
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> resume()
                Lifecycle.Event.ON_STOP -> creator.close()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            creator.close()
        }
    }

    LaunchedEffect(state.phase, state.needsAttention) {
        if (state.phase == BotCreator.Phase.CREATED && !state.needsAttention) onDone()
    }
    BackHandler {
        if (state.phase != BotCreator.Phase.CREATING) onDone()
    }

    BotCreateContent(
        state = state,
        actions = BotCreateContentActions(
            onClose = onDone,
            onTitle = {
                creator.setTitle(it)
                cue = BotFaceCue(BotFaceBit.GLANCE_DOWN)
            },
            onRole = creator::setRole,
            onShape = {
                creator.setShape(it)
                cue = BotFaceCue(BotFaceBit.HOP)
            },
            onColor = {
                creator.setColor(it)
                cue = BotFaceCue(BotFaceBit.WOBBLE)
            },
            onExpression = creator::setExpression,
            onModel = { showsModels = true },
            onSharesCredentials = creator::setSharesCredentials,
            onInstructions = creator::setInstructions,
            onSkipsBundledSkills = creator::setSkipsBundledSkills,
            onPrimary = {
                if (state.phase == BotCreator.Phase.CREATED) onDone() else creator.createAsync()
            },
        ),
        cue = cue,
        modifier = modifier,
    )

    if (showsModels) {
        BotModelPickerSheet(
            modelGroups = state.modelGroups,
            selected = state.draft.model,
            allowsHostDefault = true,
            onSelect = creator::setModel,
            onDismiss = { showsModels = false },
        )
    }
}

data class BotCreateContentActions(
    val onClose: () -> Unit = {},
    val onTitle: (String) -> Unit = {},
    val onRole: (String) -> Unit = {},
    val onShape: (BotAvatarShape) -> Unit = {},
    val onColor: (String) -> Unit = {},
    val onExpression: (BotAvatarExpression) -> Unit = {},
    val onModel: () -> Unit = {},
    val onSharesCredentials: (Boolean) -> Unit = {},
    val onInstructions: (String) -> Unit = {},
    val onSkipsBundledSkills: (Boolean) -> Unit = {},
    val onPrimary: () -> Unit = {},
)

/** Pure, network-free create UI used by the pane-count compose test. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BotCreateContent(
    state: BotCreator.State,
    modifier: Modifier = Modifier,
    actions: BotCreateContentActions = BotCreateContentActions(),
    cue: BotFaceCue? = null,
) {
    val paneCount = LocalPaneCount.current.coerceIn(1, 3)
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(if (state.isDuplicate) "Duplicate Bot" else "New Bot") },
                navigationIcon = {
                    IconButton(
                        onClick = actions.onClose,
                        enabled = state.phase != BotCreator.Phase.CREATING,
                    ) {
                        Icon(Icons.Outlined.Close, contentDescription = "Close")
                    }
                },
            )
        },
    ) { padding ->
        if (paneCount == 1) {
            CreatePane(Modifier.fillMaxSize().padding(padding)) {
                CreateHero(state, cue)
                CreateIdentityAndSetup(state, actions)
                CreateAppearance(state, actions)
                CreateStartingPoint(state, actions)
                CreateResults(state)
                CreatePrimaryAction(state, actions.onPrimary)
            }
        } else {
            HingeAwarePaneRow(
                paneCount = paneCount,
                modifier = Modifier.fillMaxSize().padding(padding),
            ) { index, paneModifier ->
                Surface(paneModifier, color = MaterialTheme.colorScheme.background) {
                    CreatePane(Modifier.fillMaxSize()) {
                        when {
                            index == 0 -> {
                                CreateHero(state, cue)
                                CreateAppearance(state, actions)
                            }
                            paneCount == 2 || index == 1 -> {
                                CreateIdentityAndSetup(state, actions)
                                if (paneCount == 2) {
                                    CreateStartingPoint(state, actions)
                                    CreateResults(state)
                                    CreatePrimaryAction(state, actions.onPrimary)
                                }
                            }
                            else -> {
                                CreateStartingPoint(state, actions)
                                CreateResults(state)
                                CreatePrimaryAction(state, actions.onPrimary)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CreatePane(
    modifier: Modifier,
    content: @Composable () -> Unit,
) {
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
private fun CreateHero(state: BotCreator.State, cue: BotFaceCue?) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        BotInteractiveFaceView(
            name = state.name.ifEmpty { "bot" },
            appearance = state.draft.appearance,
            size = 148.dp,
            cue = cue,
            modifier = Modifier,
        )
        if (state.isLoading) {
            CircularProgressIndicator(Modifier.padding(top = 10.dp).size(20.dp), strokeWidth = 2.dp)
        }
        if (state.source != null) {
            Text(
                "Copies ${state.source.name}’s instructions, settings and skills. Its chat stays with the original.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}

@Composable
private fun CreateIdentityAndSetup(state: BotCreator.State, actions: BotCreateContentActions) {
    val editable = !state.hasStarted && state.phase == BotCreator.Phase.EDITING
    SectionCard("Identity") {
        OutlinedTextField(
            value = state.draft.title,
            onValueChange = actions.onTitle,
            enabled = editable,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Name your bot") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
            isError = state.nameProblem != null,
            supportingText = {
                Text(state.nameProblem ?: state.name.takeIf(String::isNotEmpty).orEmpty())
            },
        )
        OutlinedTextField(
            value = state.draft.role,
            onValueChange = actions.onRole,
            enabled = editable,
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            label = { Text("Role") },
            placeholder = { Text("What this bot is for (optional)") },
            minLines = 1,
            maxLines = 3,
        )
    }

    SectionCard("Setup") {
        ModelChoiceRow(
            model = state.draft.model,
            enabled = editable,
            emptyTitle = "Host default",
            onClick = actions.onModel,
        )
        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Use this Hermes’s API keys", style = MaterialTheme.typography.bodyLarge)
                Text(
                    if (state.draft.sharesCredentials) {
                        "The bot signs in with keys already saved on the host. Nothing is copied to this phone."
                    } else {
                        "The bot starts with no API keys. Add them in Hermes Desktop before it can answer."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = state.draft.sharesCredentials,
                onCheckedChange = actions.onSharesCredentials,
                enabled = editable,
            )
        }
    }
}

@Composable
private fun CreateAppearance(state: BotCreator.State, actions: BotCreateContentActions) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Appearance", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        BotAppearancePicker(
            name = state.name.ifEmpty { "bot" },
            appearance = state.draft.appearance,
            enabled = !state.hasStarted && state.phase == BotCreator.Phase.EDITING,
            onShape = actions.onShape,
            onColor = actions.onColor,
            onExpression = actions.onExpression,
        )
    }
}

@Composable
private fun CreateStartingPoint(state: BotCreator.State, actions: BotCreateContentActions) {
    if (state.isDuplicate) return
    val editable = !state.hasStarted && state.phase == BotCreator.Phase.EDITING
    SectionCard("Instructions") {
        OutlinedTextField(
            value = state.draft.instructions,
            onValueChange = actions.onInstructions,
            enabled = editable,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("How this bot should work (optional)") },
            minLines = 5,
            maxLines = 10,
        )
        Text(
            "Leave empty to use the host’s default instructions.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
    SectionCard("Skills") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Start without bundled skills")
                Text(
                    if (state.draft.skipsBundledSkills) {
                        "Only the host’s essential skills. Add more later from the bot’s settings."
                    } else {
                        "The bot starts with every skill that ships with Hermes."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = state.draft.skipsBundledSkills,
                onCheckedChange = actions.onSkipsBundledSkills,
                enabled = editable,
            )
        }
    }
}

@Composable
private fun CreateResults(state: BotCreator.State) {
    if (!state.hasStarted) return
    SectionCard(if (state.phase == BotCreator.Phase.CREATED) "Created" else "Needs Attention") {
        BotCreator.Step.entries.forEach { step ->
            val outcome = state.outcomes[step] ?: return@forEach
            val (label, color) = when (outcome) {
                BotCreator.Outcome.Done -> "Done" to MaterialTheme.colorScheme.primary
                is BotCreator.Outcome.Failed -> outcome.message to MaterialTheme.colorScheme.error
                BotCreator.Outcome.Uncertain -> "Outcome Uncertain" to MaterialTheme.colorScheme.tertiary
            }
            Text(step.title, fontWeight = FontWeight.SemiBold)
            Text(label, style = MaterialTheme.typography.bodySmall, color = color)
            if (step != BotCreator.Step.CHAT) Spacer(Modifier.height(8.dp))
        }
        state.note?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 10.dp),
            )
        }
    }
}

@Composable
private fun CreatePrimaryAction(state: BotCreator.State, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = state.phase == BotCreator.Phase.CREATED || state.canCreate,
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (state.phase == BotCreator.Phase.CREATING) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        } else {
            Text(
                when {
                    state.phase == BotCreator.Phase.CREATED -> "Done"
                    state.hasStarted -> "Try Again"
                    state.isDuplicate -> "Duplicate"
                    else -> "Create"
                },
            )
        }
    }
}

@Composable
internal fun ModelChoiceRow(
    model: ModelCatalogOption?,
    enabled: Boolean,
    emptyTitle: String,
    onClick: () -> Unit,
) {
    OutlinedButton(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Outlined.Memory, contentDescription = null)
        Column(Modifier.weight(1f).padding(start = 10.dp), horizontalAlignment = Alignment.Start) {
            Text(model?.displayName ?: emptyTitle)
            model?.providerID?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
