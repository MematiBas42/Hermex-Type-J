package io.github.jcartu.hermex.ui.feature.movetohermes

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.jcartu.hermex.HermexApplication
import io.github.jcartu.hermex.model.ServerAccount
import io.github.jcartu.hermex.model.ServerKind
import io.github.jcartu.hermex.ui.adaptive.HingeAwarePaneRow
import io.github.jcartu.hermex.ui.adaptive.LocalPaneCount
import io.github.jcartu.hermex.ui.feature.onboarding.ConnectFormMoveSource
import io.github.jcartu.hermex.ui.feature.onboarding.showsMoveToHermesInvite
import io.github.jcartu.hermex.ui.feature.settings.AddServerDialog
import kotlinx.coroutines.launch

private enum class MoveDialogEntry { START, RESUME, DETAILS, COPY_AGAIN }

enum class MoveBannerKind { INVITE, PAUSED, REPORT, MOVED }

/** One additive slot mounted in the webui Sessions list. */
@Composable
fun MoveToHermesBannerSlot(serverId: String, modifier: Modifier = Modifier) {
    if (serverId.isBlank()) return
    val context = LocalContext.current
    val container = remember(context) { (context.applicationContext as HermexApplication).container }
    val auth = container.authManager
    val servers by auth.servers.collectAsStateWithLifecycle()
    val persisted by container.moveToHermesStore.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val account = servers.firstOrNull { it.id == serverId } ?: return
    val sourceRecord = persisted.moves.firstOrNull { it.sourceServerId == serverId }
    val destinationRecord = if (account.kind == ServerKind.HERMES) {
        persisted.moves.firstOrNull {
            it.destinationServerId == serverId && (!it.reportAcknowledged || it.stage != MoveStage.FINISHED)
        }
    } else null
    val record = sourceRecord ?: destinationRecord

    var questionSource by rememberSaveable(serverId) { mutableStateOf<String?>(null) }
    var dialogSource by rememberSaveable(serverId) { mutableStateOf<String?>(null) }
    var dialogEntry by rememberSaveable(serverId) { mutableStateOf(MoveDialogEntry.START) }
    var confirmRemoval by remember { mutableStateOf<MoveRecord?>(null) }

    LaunchedEffect(sourceRecord?.destinationServerId, servers.map { it.id }) {
        val destinationId = sourceRecord?.destinationServerId
        if (account.kind == ServerKind.WEBUI && destinationId != null && servers.none { it.id == destinationId }) {
            container.moveToHermesStore.removeDestination(destinationId)
        }
    }

    LaunchedEffect(account.id, account.kind, persisted.prompts, persisted.moves) {
        if (
            account.kind == ServerKind.WEBUI && sourceRecord == null &&
            persisted.prompts[account.id] == null && container.moveToHermesStore.claimQuestion(account.id)
        ) {
            questionSource = account.id
        }
    }

    val showInvite = auth.showsMoveToHermesInvite(container.moveToHermesStore, account.id)
    if (record != null || showInvite) {
        val source = servers.firstOrNull { it.id == record?.sourceServerId }
        val destination = servers.firstOrNull { it.id == record?.destinationServerId }
        val sourceName = MoveToHermesUiState.displayName(
            source ?: account.takeIf { it.kind == ServerKind.WEBUI },
            record?.sourceServerId,
        )
        val destinationName = MoveToHermesUiState.displayName(destination, record?.destinationServerId)
        val kind = when {
            record == null -> MoveBannerKind.INVITE
            record.stage == MoveStage.PAUSED || record.stage == MoveStage.RUNNING -> MoveBannerKind.PAUSED
            record.stage == MoveStage.FINISHED && !record.reportAcknowledged -> MoveBannerKind.REPORT
            else -> MoveBannerKind.MOVED
        }
        MoveToHermesBannerCard(
            kind = kind,
            sourceName = sourceName,
            destinationName = destinationName,
            inHermes = record?.items?.count { it.isInHermes },
            total = record?.items?.size,
            onPrimary = {
                when (kind) {
                    MoveBannerKind.INVITE -> {
                        dialogSource = account.id
                        dialogEntry = MoveDialogEntry.START
                    }
                    MoveBannerKind.PAUSED -> {
                        dialogSource = record?.sourceServerId
                        dialogEntry = MoveDialogEntry.RESUME
                    }
                    MoveBannerKind.REPORT -> {
                        dialogSource = record?.sourceServerId
                        dialogEntry = MoveDialogEntry.DETAILS
                    }
                    MoveBannerKind.MOVED -> destination?.let { scope.launch { auth.switchActiveServer(it) } }
                }
            },
            onDetails = record?.let {
                {
                    dialogSource = it.sourceServerId
                    dialogEntry = MoveDialogEntry.DETAILS
                }
            },
            onCopyAgain = record?.takeIf { it.stage == MoveStage.FINISHED }?.let {
                {
                    dialogSource = it.sourceServerId
                    dialogEntry = MoveDialogEntry.COPY_AGAIN
                }
            },
            onRemove = record?.takeIf { source != null }?.let { { confirmRemoval = it } },
            onDismissInvite = if (kind == MoveBannerKind.INVITE) {
                { scope.launch { container.moveToHermesStore.dismissInvite(account.id) } }
            } else null,
            modifier = modifier,
        )
    }

    if (questionSource != null) {
        val source = servers.firstOrNull { it.id == questionSource }
        val sourceName = MoveToHermesUiState.displayName(source)
        AlertDialog(
            onDismissRequest = { questionSource = null },
            icon = { Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null) },
            title = { Text("Move $sourceName to Hermes") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Hermex now talks to Hermes directly. Bring your chats, titles, pins, folders, and drafts with you.")
                    Text("Nothing is deleted. $sourceName keeps working until you remove it.")
                }
            },
            confirmButton = {
                Button(onClick = {
                    dialogSource = questionSource
                    dialogEntry = MoveDialogEntry.START
                    questionSource = null
                }) { Text("Move to Hermes") }
            },
            dismissButton = { TextButton(onClick = { questionSource = null }) { Text("Not Now") } },
        )
    }

    confirmRemoval?.let { held ->
        val source = servers.firstOrNull { it.id == held.sourceServerId }
        AlertDialog(
            onDismissRequest = { confirmRemoval = null },
            title = { Text("Remove ${MoveToHermesUiState.displayName(source)}?") },
            text = { Text("Chats stay on that host. Old links for chats confirmed in Hermes keep opening the destination.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemoval = null
                    if (source != null) {
                        scope.launch {
                            container.cacheStore.chatDraftStore.discardDrafts(source.id)
                            auth.removeServer(source)
                        }
                    }
                }) { Text("Remove Server", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmRemoval = null }) { Text("Cancel") } },
        )
    }

    dialogSource?.let { sourceId ->
        key(sourceId, dialogEntry) {
            MoveToHermesDialog(
                sourceServerId = sourceId,
                entry = dialogEntry,
                onDismiss = { dialogSource = null },
                onMoveNext = { next ->
                    dialogSource = next
                    dialogEntry = MoveDialogEntry.START
                },
            )
        }
    }
}

/** Stateless banner card, separated for Robolectric pane-count composition. */
@Composable
fun MoveToHermesBannerCard(
    kind: MoveBannerKind,
    sourceName: String,
    destinationName: String,
    inHermes: Int? = null,
    total: Int? = null,
    onPrimary: () -> Unit,
    onDetails: (() -> Unit)? = null,
    onCopyAgain: (() -> Unit)? = null,
    onRemove: (() -> Unit)? = null,
    onDismissInvite: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val title = when (kind) {
        MoveBannerKind.INVITE -> "Move $sourceName to Hermes"
        MoveBannerKind.PAUSED -> "Move paused"
        MoveBannerKind.REPORT -> "Move finished"
        MoveBannerKind.MOVED -> "Moved to $destinationName"
    }
    val detail = when (kind) {
        MoveBannerKind.INVITE -> "Bring your chats and drafts to a Hermes host."
        MoveBannerKind.PAUSED -> if (inHermes != null && total != null) {
            "$inHermes of $total chats are in $destinationName. Some work is unfinished."
        } else "Some work is unfinished."
        MoveBannerKind.REPORT -> "See what moved to $destinationName and what stayed on $sourceName."
        MoveBannerKind.MOVED -> "Continue moved chats there. Sending here too can split a conversation."
    }
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (kind == MoveBannerKind.INVITE) {
                MaterialTheme.colorScheme.surfaceVariant
            } else {
                MaterialTheme.colorScheme.tertiaryContainer
            },
        ),
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(
                    if (kind == MoveBannerKind.PAUSED) Icons.Filled.PauseCircle else Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Column(Modifier.weight(1f)) {
                    Text(title, fontWeight = FontWeight.SemiBold)
                    Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                onDismissInvite?.let { dismiss ->
                    IconButton(onClick = dismiss, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Filled.Close, contentDescription = "Dismiss", modifier = Modifier.size(16.dp))
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                FilledTonalButton(onClick = onPrimary) {
                    Text(
                        when (kind) {
                            MoveBannerKind.INVITE -> "Move"
                            MoveBannerKind.PAUSED -> "Resume"
                            MoveBannerKind.REPORT -> "See Report"
                            MoveBannerKind.MOVED -> "Open $destinationName"
                        },
                    )
                }
                onDetails?.let { TextButton(onClick = it) { Text("Details") } }
                if (onCopyAgain != null || onRemove != null) {
                    var expanded by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { expanded = true }) { Icon(Icons.Filled.MoreVert, "More") }
                        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                            onCopyAgain?.let {
                                DropdownMenuItem(text = { Text("Copy Missing Chats Again") }, onClick = {
                                    expanded = false
                                    it()
                                })
                            }
                            onRemove?.let {
                                DropdownMenuItem(text = { Text("Remove This Server") }, onClick = {
                                    expanded = false
                                    it()
                                })
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MoveToHermesDialog(
    sourceServerId: String,
    entry: MoveDialogEntry,
    onDismiss: () -> Unit,
    onMoveNext: (String) -> Unit,
) {
    val context = LocalContext.current
    val container = remember(context) { (context.applicationContext as HermexApplication).container }
    val model: MoveToHermesViewModel = viewModel(
        key = "move-to-hermes:$sourceServerId",
        factory = viewModelFactory {
            initializer { MoveToHermesViewModel(container, sourceServerId) }
        },
    )
    val state by model.uiState.collectAsStateWithLifecycle()
    var showConnect by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(entry) {
        model.open()
        when (entry) {
            MoveDialogEntry.RESUME -> if (model.uiState.value.stage == MoveWizardStage.PAUSED) model.resume()
            MoveDialogEntry.COPY_AGAIN -> model.copyMissingAgain()
            MoveDialogEntry.START, MoveDialogEntry.DETAILS -> Unit
        }
    }
    DisposableEffect(model) { onDispose(model::pause) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Column(
            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).systemBarsPadding(),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = "Close") }
                Text(wizardTitle(state), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            HorizontalDivider()
            MoveToHermesWizardContent(
                state = state,
                onChooseHost = model::chooseDestination,
                onUseSavedHost = model::useSavedSourceHost,
                onAddHost = { showConnect = true },
                onMapProfile = model::setProfileDestination,
                onMove = model::startMove,
                onRetry = model::retryScan,
                onResume = model::resume,
                onFinishEarly = model::finishEarly,
                onCopyAgain = model::copyMissingAgain,
                onDone = { model.activateDestination(onDismiss) },
                onMoveNext = { model.moveNext(onMoveNext) },
                onRemove = { model.removeSource(onDismiss) },
                onClose = onDismiss,
                modifier = Modifier.weight(1f),
            )
        }
    }

    if (showConnect) {
        val source = state.source
        AddServerDialog(
            onDismiss = { showConnect = false },
            onAdded = { account ->
                showConnect = false
                model.hostAdded(account)
            },
            moveSource = source?.let {
                ConnectFormMoveSource(
                    name = MoveToHermesUiState.displayName(it),
                    headers = container.authManager.savedCustomHeaders(it),
                )
            },
        )
    }
}

private fun wizardTitle(state: MoveToHermesUiState): String = when (state.stage) {
    MoveWizardStage.CHOOSE_HOST -> "Which Hermes host?"
    MoveWizardStage.CHECKING -> "Checking"
    MoveWizardStage.REVIEW -> "Review"
    MoveWizardStage.MOVING -> "Moving"
    MoveWizardStage.PAUSED -> "Move Paused"
    MoveWizardStage.FINISHED -> if (state.record?.report?.finishedEarly == true) "Finished Early" else "Moved"
    MoveWizardStage.ERROR -> "Move to Hermes"
}

/** Adaptive wizard body used directly by the pane-count Compose acceptance test. */
@Composable
fun MoveToHermesWizardContent(
    state: MoveToHermesUiState,
    onChooseHost: (ServerAccount) -> Unit,
    onUseSavedHost: () -> Unit,
    onAddHost: () -> Unit,
    onMapProfile: (String, String?, Boolean) -> Unit,
    onMove: () -> Unit,
    onRetry: () -> Unit,
    onResume: () -> Unit,
    onFinishEarly: () -> Unit,
    onCopyAgain: () -> Unit,
    onDone: () -> Unit,
    onMoveNext: () -> Unit,
    onRemove: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val paneCount = LocalPaneCount.current.coerceIn(1, 3)
    if (paneCount == 1) {
        WizardDetails(
            state, onChooseHost, onUseSavedHost, onAddHost, onMapProfile, onMove, onRetry, onResume,
            onFinishEarly, onCopyAgain, onDone, onMoveNext, onRemove, onClose,
            modifier.fillMaxSize(),
        )
    } else {
        HingeAwarePaneRow(paneCount = paneCount, modifier = modifier.fillMaxSize()) { index, paneModifier ->
            Surface(paneModifier) {
                when {
                    index == 0 -> WizardOverview(state, Modifier.fillMaxSize())
                    index == paneCount - 1 -> WizardDetails(
                        state, onChooseHost, onUseSavedHost, onAddHost, onMapProfile, onMove, onRetry, onResume,
                        onFinishEarly, onCopyAgain, onDone, onMoveNext, onRemove, onClose,
                        Modifier.fillMaxSize(),
                    )
                    else -> WizardSummary(state, Modifier.fillMaxSize())
                }
            }
        }
    }
}

@Composable
private fun WizardOverview(state: MoveToHermesUiState, modifier: Modifier) {
    val steps = listOf("Choose host", "Scan", "Review", "Copy", "Report")
    val current = when (state.stage) {
        MoveWizardStage.CHOOSE_HOST -> 0
        MoveWizardStage.CHECKING -> 1
        MoveWizardStage.REVIEW -> 2
        MoveWizardStage.MOVING, MoveWizardStage.PAUSED -> 3
        MoveWizardStage.FINISHED -> 4
        MoveWizardStage.ERROR -> 1
    }
    Column(
        modifier.verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Icon(Icons.Filled.Cloud, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp))
        Text("${state.sourceName} → Hermes", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        steps.forEachIndexed { index, step ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (index < current) Icons.Filled.CheckCircle else if (index == current) Icons.AutoMirrored.Filled.ArrowForward else Icons.Filled.Check,
                    contentDescription = null,
                    tint = if (index <= current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(20.dp),
                )
                Text(step, fontWeight = if (index == current) FontWeight.SemiBold else FontWeight.Normal)
            }
        }
    }
}

@Composable
private fun WizardSummary(state: MoveToHermesUiState, modifier: Modifier) {
    val record = state.record
    val report = record?.report ?: record?.let { MoveReportBuilder.build(it) }
    Column(
        modifier.verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Move summary", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        SummaryLine("Source", state.sourceName)
        SummaryLine("Destination", state.destinationName)
        state.scan?.let {
            SummaryLine("Chats checked", it.entries.size.toString())
            SummaryLine("Already in Hermes", it.entries.count { entry -> entry.location.kind == MoveChatLocation.Kind.PRESENT }.toString())
        }
        report?.let {
            SummaryLine("In Hermes", it.chatsInHermes.toString())
            SummaryLine("Still on webui", it.remaining.toString())
            SummaryLine("Drafts moved", it.draftsMoved.toString())
        }
    }
}

@Composable
private fun SummaryLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Suppress("LongParameterList")
@Composable
private fun WizardDetails(
    state: MoveToHermesUiState,
    onChooseHost: (ServerAccount) -> Unit,
    onUseSavedHost: () -> Unit,
    onAddHost: () -> Unit,
    onMapProfile: (String, String?, Boolean) -> Unit,
    onMove: () -> Unit,
    onRetry: () -> Unit,
    onResume: () -> Unit,
    onFinishEarly: () -> Unit,
    onCopyAgain: () -> Unit,
    onDone: () -> Unit,
    onMoveNext: () -> Unit,
    onRemove: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier,
) {
    LazyColumn(
        modifier = modifier.widthIn(max = 720.dp).padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Spacer(Modifier.size(4.dp)) }
        when (state.stage) {
            MoveWizardStage.CHOOSE_HOST -> {
                item {
                    Text("Pick the Hermes host that should take ${state.sourceName}’s chats.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (state.hasSavedSourceHermesSignIn) {
                    item {
                        FilledTonalButton(onClick = onUseSavedHost, modifier = Modifier.fillMaxWidth()) {
                            Text("Use ${state.sourceName}’s saved Hermes sign-in")
                        }
                    }
                }
                items(state.hostChoices, key = { it.id }) { host ->
                    OutlinedButton(onClick = { onChooseHost(host) }, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.fillMaxWidth()) {
                            Text(MoveToHermesUiState.displayName(host), fontWeight = FontWeight.SemiBold)
                            Text(host.urlString, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                item {
                    Button(onClick = onAddHost, modifier = Modifier.fillMaxWidth()) { Text("Sign in to a Hermes host") }
                }
            }
            MoveWizardStage.CHECKING -> {
                item {
                    WizardStatusCard(Icons.Filled.Cloud, "Signed in to ${state.destinationName}", "Nothing has been changed yet.")
                }
                item {
                    val progress = if (state.totalChats <= 0) 0f else state.checkedChats.toFloat() / state.totalChats
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Checking which chats are already in Hermes", fontWeight = FontWeight.SemiBold)
                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                        Text("Checked ${state.checkedChats} of ${state.totalChats}", style = MaterialTheme.typography.bodySmall)
                    }
                }
                item { Text("Cancel leaves both servers as they are.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            MoveWizardStage.REVIEW -> {
                val scan = state.scan
                val present = scan?.entries?.count { it.location.kind == MoveChatLocation.Kind.PRESENT } ?: 0
                val missing = scan?.entries?.count { it.location.kind == MoveChatLocation.Kind.MISSING } ?: 0
                item {
                    Text("$present already in Hermes · $missing available to copy", style = MaterialTheme.typography.titleMedium)
                }
                scan?.onlyProfile?.let { only ->
                    item { WizardStatusCard(Icons.Filled.Error, "Only “$only” was checked", "Other webui Profiles are not counted by this move.") }
                }
                items(state.profileChoices, key = { it.sourceProfile }) { choice ->
                    val mapping = state.mappings.firstOrNull { it.sourceProfile == choice.sourceProfile }
                    ProfileMappingRow(
                        choice = choice,
                        mapping = mapping,
                        destinationProfiles = state.catalog?.profiles.orEmpty(),
                        sourceName = state.sourceName,
                        enabled = !state.isRepeat,
                        onSelect = { profile, keep -> onMapProfile(choice.sourceProfile, profile, keep) },
                    )
                }
                item {
                    WizardStatusCard(
                        Icons.Filled.CheckCircle,
                        "Also brought over",
                        "Titles, pins, archives, folders, and drafts are filled after chats copy.",
                    )
                }
                item {
                    Text(
                        "Push pairing is not available on Android, so notification setup is not moved. Existing Android notification settings stay unchanged.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                item {
                    Button(onClick = onMove, enabled = state.canMove, modifier = Modifier.fillMaxWidth()) {
                        Text(if (state.isRepeat) "Copy Missing Chats Again" else "Move")
                    }
                }
            }
            MoveWizardStage.MOVING -> {
                item { CircularProgressIndicator(modifier = Modifier.size(36.dp)) }
                item { Text("Moving chats", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
                item { ProgressDetails(state.record) }
                item {
                    Text(
                        "You can close this or quit the app. Resume starts from the durable journal and skips work already confirmed in Hermes.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                item { OutlinedButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text("Close") } }
            }
            MoveWizardStage.PAUSED -> {
                item { WizardStatusCard(Icons.Filled.PauseCircle, "Move paused", state.errorMessage ?: "Resume picks up where it stopped.") }
                item { ProgressDetails(state.record) }
                item { Button(onClick = onResume, modifier = Modifier.fillMaxWidth()) { Text("Resume") } }
                item { OutlinedButton(onClick = onFinishEarly, modifier = Modifier.fillMaxWidth()) { Text("Finish with What’s Done") } }
            }
            MoveWizardStage.FINISHED -> {
                item { FinishedReport(state.record?.report ?: state.record?.let { MoveReportBuilder.build(it) }) }
                item { Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("Done") } }
                if (state.record?.report?.let { it.remaining + it.unchecked > 0 } == true) {
                    item { OutlinedButton(onClick = onCopyAgain, modifier = Modifier.fillMaxWidth()) { Text("Copy Missing Chats Again") } }
                }
                if (state.hasNextSource) {
                    item { OutlinedButton(onClick = onMoveNext, modifier = Modifier.fillMaxWidth()) { Text("Move Next Webui Server") } }
                }
                if (state.source != null) {
                    item { TextButton(onClick = onRemove, modifier = Modifier.fillMaxWidth()) { Text("Remove ${state.sourceName}") } }
                }
            }
            MoveWizardStage.ERROR -> {
                item { WizardStatusCard(Icons.Filled.Error, "Couldn’t continue", state.errorMessage ?: "Nothing was changed.") }
                item { Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) { Text("Try Again") } }
                item { OutlinedButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text("Close") } }
            }
        }
        item { Spacer(Modifier.size(24.dp)) }
    }
}

@Composable
private fun ProfileMappingRow(
    choice: MoveProfileChoice,
    mapping: MoveProfileMapping?,
    destinationProfiles: List<String>,
    sourceName: String,
    enabled: Boolean,
    onSelect: (profile: String?, keepOnWebui: Boolean) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(choice.sourceProfile, fontWeight = FontWeight.SemiBold)
                Text("${choice.chatCount} chats", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Box {
                TextButton(onClick = { expanded = true }, enabled = enabled) {
                    Text(
                        when {
                            mapping?.destinationProfile != null -> mapping.destinationProfile
                            mapping?.keepOnWebui == true -> "Keep on $sourceName"
                            else -> "Choose…"
                        },
                    )
                }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    destinationProfiles.forEach { profile ->
                        DropdownMenuItem(text = { Text(profile) }, onClick = {
                            expanded = false
                            onSelect(profile, false)
                        })
                    }
                    HorizontalDivider()
                    DropdownMenuItem(text = { Text("Keep on $sourceName") }, onClick = {
                        expanded = false
                        onSelect(null, true)
                    })
                }
            }
        }
    }
}

@Composable
private fun WizardStatusCard(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, detail: String) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ProgressDetails(record: MoveRecord?) {
    if (record == null) return
    val report = MoveReportBuilder.build(record)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SummaryLine("Chats in Hermes", "${report.chatsInHermes} of ${record.items.size}")
        SummaryLine("Still to copy", record.items.count { it.copyState == MoveCopyState.PENDING || it.copyState == MoveCopyState.DISPATCHING }.toString())
        SummaryLine("Titles, pins, folders applied", report.changesApplied.toString())
        SummaryLine("Drafts moved", report.draftsMoved.toString())
    }
}

@Composable
private fun FinishedReport(report: MoveToHermesReport?) {
    if (report == null) return
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp))
        Text(if (report.finishedEarly) "Finished with what was done" else "Move finished", style = MaterialTheme.typography.titleLarge)
        SummaryLine("Chats in Hermes", report.chatsInHermes.toString())
        SummaryLine("Copied", report.copied.toString())
        SummaryLine("Already there", report.alreadyThere.toString())
        SummaryLine("Stay on webui", report.remaining.toString())
        SummaryLine("Unchecked", report.unchecked.toString())
        SummaryLine("Drafts moved", report.draftsMoved.toString())
        if (report.draftConflicts > 0) SummaryLine("Draft conflicts (both kept)", report.draftConflicts.toString())
    }
}
