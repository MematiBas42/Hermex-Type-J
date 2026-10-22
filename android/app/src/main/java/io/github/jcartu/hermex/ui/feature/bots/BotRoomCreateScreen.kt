@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package io.github.jcartu.hermex.ui.feature.bots

import android.graphics.Bitmap
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jcartu.hermex.model.hermes.BotGroupRoom
import io.github.jcartu.hermex.model.hermes.BotProfile
import io.github.jcartu.hermex.net.hermes.BotRoomRPC
import io.github.jcartu.hermex.ui.adaptive.HingeAwarePaneRow
import io.github.jcartu.hermex.ui.adaptive.LocalPaneCount
import kotlinx.coroutines.launch

/** Pure action seam for the room creation panes. */
data class BotRoomCreateActions(
    val onQueryChange: (String) -> Unit = {},
    val onSelect: (BotProfile) -> Unit = {},
    val onRemove: (BotProfile) -> Unit = {},
    val onNameChange: (String) -> Unit = {},
    val onCreate: () -> Unit = {},
    val onCancel: () -> Unit = {},
)

/**
 * Port of `BotRoomCreateView.swift`. One pane walks member/name steps; two panes keep selector
 * and frozen payload preview beside each other; a trifold dedicates its middle panel to selected
 * members. The creator, not Compose state, owns the immutable dispatched attempt.
 */
@Composable
fun BotRoomCreateScreen(
    creator: BotRoomCreator,
    avatars: Map<String, Bitmap>,
    onDone: (BotGroupRoom) -> Unit,
    modifier: Modifier = Modifier,
    onCancel: () -> Unit = {},
) {
    val state by creator.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    var delivered by rememberSaveable(creator.connection.id, creator.hashCode()) { mutableStateOf(false) }
    LaunchedEffect(state.created) {
        val room = state.created
        if (room != null && !delivered) {
            delivered = true
            onDone(room)
        }
    }
    DisposableEffect(creator, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) creator.cancel()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            creator.cancel()
        }
    }
    BotRoomCreateContent(
        creator = creator,
        state = state,
        avatars = avatars,
        actions = BotRoomCreateActions(
            onQueryChange = creator::setQuery,
            onSelect = creator::select,
            onRemove = creator::remove,
            onNameChange = creator::setName,
            onCreate = { scope.launch { creator.create() } },
            onCancel = { creator.cancel(); onCancel() },
        ),
        modifier = modifier,
    )
}

/** Pure pane-count content; invoking it performs no transport work until [BotRoomCreateActions.onCreate]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BotRoomCreateContent(
    creator: BotRoomCreator,
    state: BotRoomCreator.State,
    avatars: Map<String, Bitmap>,
    actions: BotRoomCreateActions,
    modifier: Modifier = Modifier,
    paneCount: Int = LocalPaneCount.current,
) {
    var naming by rememberSaveable(creator.connection.id, creator.hashCode()) { mutableStateOf(false) }
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("New Group Chat") },
                navigationIcon = {
                    IconButton(onClick = {
                        if (paneCount == 1 && naming && !creator.locked) naming = false else actions.onCancel()
                    }) {
                        Icon(if (paneCount == 1 && naming && !creator.locked) Icons.Filled.ArrowBack else Icons.Filled.Close, "Cancel")
                    }
                },
                actions = {
                    if (paneCount == 1 && !naming) {
                        TextButton(onClick = { naming = true }, enabled = creator.mayContinue) { Text("Next") }
                    }
                },
            )
        },
    ) { padding ->
        val body = Modifier.fillMaxSize().padding(padding)
        when {
            paneCount <= 1 -> if (naming) {
                RoomNamingPane(creator, state, avatars, actions, body.semantics { contentDescription = "room-create-name" })
            } else {
                RoomMemberPicker(creator, state, avatars, actions, body.semantics { contentDescription = "room-create-members" })
            }
            paneCount == 2 -> HingeAwarePaneRow(2, body) { index, paneModifier ->
                if (index == 0) RoomMemberPicker(creator, state, avatars, actions, paneModifier.semantics { contentDescription = "room-create-members" })
                else RoomNamingPane(creator, state, avatars, actions, paneModifier.semantics { contentDescription = "room-create-name" })
            }
            else -> HingeAwarePaneRow(3, body) { index, paneModifier ->
                when (index) {
                    0 -> RoomMemberPicker(creator, state, avatars, actions, paneModifier.semantics { contentDescription = "room-create-members" }, showSelected = false)
                    1 -> SelectedMembersPane(state, avatars, actions, paneModifier.semantics { contentDescription = "room-create-selected" })
                    else -> RoomNamingPane(creator, state, avatars, actions, paneModifier.semantics { contentDescription = "room-create-name" }, showMembers = false)
                }
            }
        }
    }
}

@Composable
private fun RoomMemberPicker(
    creator: BotRoomCreator,
    state: BotRoomCreator.State,
    avatars: Map<String, Bitmap>,
    actions: BotRoomCreateActions,
    modifier: Modifier,
    showSelected: Boolean = true,
) {
    Column(modifier.fillMaxSize()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Choose two to six bots", style = MaterialTheme.typography.titleMedium)
            if (showSelected && state.selected.isNotEmpty()) SelectedMemberChips(state.selected, avatars, actions, creator.locked)
            OutlinedTextField(
                value = state.query,
                onValueChange = actions.onQueryChange,
                enabled = !creator.locked,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Add another") },
                singleLine = true,
            )
            Text(
                if (state.selected.size == 6) "Groups can have up to six members." else "${state.selected.size} of 2–6 selected",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        HorizontalDivider()
        LazyColumn(Modifier.fillMaxSize()) {
            items(creator.remaining, key = BotProfile::id) { profile ->
                Row(
                    Modifier.fillMaxWidth().clickable(enabled = !creator.locked && state.selected.size < 6) { actions.onSelect(profile) }
                        .padding(horizontal = 18.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    BotAvatarView(
                        profile = profile,
                        avatar = avatars[profile.id],
                        size = 40.dp,
                        motion = BotFaceMotion.Still,
                    )
                    Column(Modifier.weight(1f)) {
                        Text(profile.name, fontWeight = FontWeight.SemiBold)
                        profile.description?.let { Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SelectedMembersPane(
    state: BotRoomCreator.State,
    avatars: Map<String, Bitmap>,
    actions: BotRoomCreateActions,
    modifier: Modifier,
) {
    Column(modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Group members", style = MaterialTheme.typography.titleLarge)
        if (state.selected.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Choose at least two bots", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        } else {
            state.selected.forEach { profile ->
                Surface(shape = RoundedCornerShape(16.dp), tonalElevation = 1.dp) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        BotAvatarView(
                            profile = profile,
                            avatar = avatars[profile.id],
                            size = 40.dp,
                            motion = BotFaceMotion.Still,
                        )
                        Text(profile.name, Modifier.weight(1f))
                        IconButton(onClick = { actions.onRemove(profile) }, enabled = state.attempt == null) { Icon(Icons.Filled.Close, "Remove ${profile.name}") }
                    }
                }
            }
        }
    }
}

@Composable
private fun RoomNamingPane(
    creator: BotRoomCreator,
    state: BotRoomCreator.State,
    avatars: Map<String, Bitmap>,
    actions: BotRoomCreateActions,
    modifier: Modifier,
    showMembers: Boolean = true,
) {
    Column(
        modifier.fillMaxSize().padding(22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically),
    ) {
        if (showMembers) BotRoomAvatars(creator.preview, creator.roster, avatars, 72.dp)
        else Icon(Icons.Filled.Groups, null, Modifier.size(72.dp), tint = MaterialTheme.colorScheme.primary)
        OutlinedTextField(
            value = state.name,
            onValueChange = actions.onNameChange,
            enabled = !creator.locked,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Group name") },
            supportingText = if (!BotRoomRPC.validName(state.name)) {
                { Text("Enter a name of up to 200 characters.") }
            } else null,
        )
        if (!showMembers) {
            Text(
                state.selected.joinToString { it.name }.ifEmpty { "Choose at least two bots" },
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        state.message?.let { Text(it, color = if (state.attempt != null) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error, textAlign = TextAlign.Center) }
        Button(onClick = actions.onCreate, enabled = creator.mayCreate) {
            Text(if (state.busy) "Creating…" else if (creator.locked) "Try Again" else "Create")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectedMemberChips(
    selected: List<BotProfile>,
    avatars: Map<String, Bitmap>,
    actions: BotRoomCreateActions,
    locked: Boolean,
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        selected.forEach { profile ->
            Surface(
                modifier = Modifier.clickable(enabled = !locked) { actions.onRemove(profile) },
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Row(Modifier.padding(horizontal = 9.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    BotAvatarView(
                        profile = profile,
                        avatar = avatars[profile.id],
                        size = 24.dp,
                        motion = BotFaceMotion.Still,
                    )
                    Text(profile.name, maxLines = 1)
                    Icon(Icons.Filled.Close, "Remove ${profile.name}", Modifier.size(14.dp))
                }
            }
        }
    }
}
