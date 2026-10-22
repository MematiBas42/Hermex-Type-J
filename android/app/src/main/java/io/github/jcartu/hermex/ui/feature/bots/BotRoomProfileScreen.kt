package io.github.jcartu.hermex.ui.feature.bots

import android.graphics.Bitmap
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jcartu.hermex.model.hermes.BotProfile
import io.github.jcartu.hermex.net.hermes.BotRoomRPC
import kotlinx.coroutines.launch
import java.util.UUID

/** Standalone room profile route; embedded three-pane/profile dialogs use [BotRoomProfilePane]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BotRoomProfileScreen(
    reader: BotRoomReader,
    roster: List<BotProfile>,
    avatars: Map<String, Bitmap>,
    onBack: () -> Unit,
    onUpdateSignIn: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenBot: ((BotProfile) -> Unit)? = null,
) {
    val state by reader.state.collectAsStateWithLifecycle()
    val coroutineScope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    val owner = rememberSaveable(reader.key.server, reader.key.connectionID, reader.key.roomID) { UUID.randomUUID().toString() }
    var reconnectRevision by rememberSaveable(reader.key.server, reader.key.connectionID, reader.key.roomID) { mutableIntStateOf(0) }
    LaunchedEffect(reader, owner, reconnectRevision) {
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) reader.open(owner, preservingLoadedHistory = true)
    }
    DisposableEffect(reader, lifecycleOwner, owner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> if (reader.current.link == BotRoomReader.Link.IDLE) reconnectRevision += 1
                Lifecycle.Event.ON_STOP -> reader.leaveAsync(owner, preservingLoadedHistory = true)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            reader.leaveAsync(owner, preservingLoadedHistory = true)
        }
    }
    val presentation = BotRoomPresentation(
        state = state,
        connectionName = reader.connection.name,
        showsWelcome = reader.showsWelcome,
        showsComposer = reader.showsComposer,
        showsStop = reader.showsStop,
        mayStop = reader.mayStop,
        showsRename = reader.showsRename,
        showsDisband = reader.showsDisband,
        mayRename = reader.mayRename,
        mayDisband = reader.mayDisband,
        maySend = reader::maySend,
        mayResend = reader::mayResend,
        mayEditDraft = reader::mayEditDraft,
        mayAct = reader::mayAct,
    )
    val actions = BotRoomScreenActions(
        onBack = onBack,
        onReconnect = { reconnectRevision += 1 },
        onUpdateSignIn = onUpdateSignIn,
        onRename = { coroutineScope.launch { reader.rename(it) } },
        onDisband = { coroutineScope.launch { reader.disband() } },
        onOpenBot = onOpenBot,
    )
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(state.room.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        BotRoomProfilePane(presentation, roster, avatars, actions, Modifier.padding(padding))
    }
}

@Composable
fun BotRoomProfileDialog(
    presentation: BotRoomPresentation,
    roster: List<BotProfile>,
    avatars: Map<String, Bitmap>,
    actions: BotRoomScreenActions,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.9f).fillMaxSize(0.9f),
            shape = RoundedCornerShape(24.dp),
            tonalElevation = 8.dp,
        ) {
            Column {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Room profile", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    TextButton(onClick = onDismiss) { Text("Done") }
                }
                HorizontalDivider()
                BotRoomProfilePane(presentation, roster, avatars, actions, Modifier.fillMaxSize())
            }
        }
    }
}

/** Pure profile pane shared by the dialog and the first panel on a trifold. */
@Composable
fun BotRoomProfilePane(
    presentation: BotRoomPresentation,
    roster: List<BotProfile>,
    avatars: Map<String, Bitmap>,
    actions: BotRoomScreenActions,
    modifier: Modifier = Modifier,
) {
    val state = presentation.state
    var name by remember(state.room.id) { mutableStateOf(state.room.name) }
    var baselineName by remember(state.room.id) { mutableStateOf(state.room.name) }
    var confirmsDisband by rememberSaveable(state.room.id) { mutableStateOf(false) }
    LaunchedEffect(state.room.name) {
        if (name == baselineName || !presentation.mayRename) name = state.room.name
        baselineName = state.room.name
    }

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BotRoomAvatars(state.room, roster, avatars, 78.dp)
        if (presentation.showsRename) {
            OutlinedTextField(
                value = name,
                onValueChange = { if (presentation.mayRename) name = it },
                enabled = presentation.mayRename,
                singleLine = false,
                label = { Text("Group name") },
                supportingText = if (!BotRoomRPC.validName(name)) {
                    { Text("Enter a name of up to 200 characters.") }
                } else null,
            )
            if (name != state.room.name) {
                Button(
                    onClick = { actions.onRename(name) },
                    enabled = presentation.mayRename && BotRoomRPC.validName(name),
                ) { Text("Save name") }
            }
        } else {
            Text(state.room.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        }
        HorizontalDivider()
        Text("Members", Modifier.fillMaxWidth(), style = MaterialTheme.typography.titleMedium)
        state.room.members.forEach { member ->
            val profile = roster.firstOrNull { it.id == member.profile }
            val opensProfile = profile != null && actions.onOpenBot != null
            Row(
                Modifier.fillMaxWidth()
                    .then(if (opensProfile) Modifier.clickable { actions.onOpenBot?.invoke(requireNotNull(profile)) } else Modifier)
                    .padding(vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                BotRoomMemberAvatar(member, roster, avatars, 38.dp)
                Text(member.name, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        if (state.foreignAuthority) Text("Managed by another Hermes", color = MaterialTheme.colorScheme.onSurfaceVariant)
        (state.commandMessage ?: state.errorMessage)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (state.status.stopping > 0 || state.awaitingStop) Text("Finishing stop…", color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (state.link == BotRoomReader.Link.STOPPED) {
            if (state.needsSignIn) {
                Button(onClick = actions.onUpdateSignIn) { Text("Update sign-in") }
            } else {
                Button(onClick = actions.onReconnect) { Icon(Icons.Filled.Refresh, null); Text("Reconnect") }
            }
        }
        if (presentation.showsDisband) {
            TextButton(
                onClick = { confirmsDisband = true },
                enabled = presentation.mayDisband,
                colors = androidx.compose.material3.ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) {
                Icon(Icons.Filled.DeleteForever, null)
                Text("Disband Group")
            }
        }
    }
    if (confirmsDisband) {
        AlertDialog(
            onDismissRequest = { confirmsDisband = false },
            title = { Text("Disband this group?") },
            text = { Text("The room and its history will be removed from every device. All bots in it will stop. The room cannot be restored.") },
            confirmButton = {
                TextButton(
                    onClick = { confirmsDisband = false; actions.onDisband() },
                    enabled = presentation.mayDisband,
                ) { Text("Disband Group", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmsDisband = false }) { Text("Cancel") } },
        )
    }
}
