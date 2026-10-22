package io.github.jcartu.hermex.ui.feature.bots

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddComment
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.GroupAdd
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.Reorder
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.jcartu.hermex.model.hermes.BotGroupRoom
import io.github.jcartu.hermex.model.hermes.BotProfile
import io.github.jcartu.hermex.net.hermes.BotLiveStatus
import io.github.jcartu.hermex.net.hermes.BotRoomRPC
import io.github.jcartu.hermex.ui.feature.sessions.sessionRowStateColors
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

/** Slots supplied by the Hermes home chrome while Bots is one side of that home. */
interface BotsInboxBar {
    fun setFilter(content: @Composable () -> Unit)
    fun setNewChat(content: @Composable () -> Unit)
}

/**
 * Pure Bot inbox surface, ported from `Features/Bots/BotsInboxView.swift`.
 *
 * The route owns lifecycle, engine construction, selection/detail panes and navigation. This
 * composable renders only the supplied immutable [state], which makes all 1/2/3-pane states
 * deterministic without opening a socket. Mutating menu actions call the real [BotInbox]
 * methods; navigation and create/edit destinations stay explicit callbacks to the route.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BotsInboxScreen(
    inbox: BotInbox,
    state: BotInbox.State,
    onOpenBot: (BotProfile) -> Unit,
    onOpenRoom: (BotGroupRoom, Int?) -> Unit,
    onEdit: (BotProfile) -> Unit,
    onCreate: (BotProfile?) -> Unit,
    onCreateRoom: () -> Unit,
    onConnection: () -> Unit,
    header: (@Composable (onSearch: () -> Unit) -> Unit)? = null,
    bottomBar: (@Composable () -> Unit)? = null,
    bar: BotsInboxBar? = null,
    bottomContentPadding: Dp = 88.dp,
    modifier: Modifier = Modifier,
) {
    var showsSearch by rememberSaveable { mutableStateOf(false) }
    var searchConnectionID by rememberSaveable { mutableStateOf<String?>(null) }
    var showsSectionOrder by rememberSaveable { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<BotProfile?>(null) }
    var moving by remember { mutableStateOf<BotProfile?>(null) }
    var renamingRoom by remember { mutableStateOf<BotGroupRoom?>(null) }
    var disbandingRoom by remember { mutableStateOf<BotGroupRoom?>(null) }
    val actionScope = rememberCoroutineScope()

    val openSearch: () -> Unit = {
        state.connection?.let {
            searchConnectionID = it.id
            showsSearch = true
        }
    }

    LaunchedEffect(state.connection?.id) {
        if (showsSearch && state.connection?.id != searchConnectionID) showsSearch = false
        deleting = null
        moving = null
        renamingRoom = null
        disbandingRoom = null
        if (state.connection == null) showsSectionOrder = false
    }

    val latestState = rememberUpdatedState(state)
    val latestCreate = rememberUpdatedState(onCreate)
    val latestCreateRoom = rememberUpdatedState(onCreateRoom)
    val latestShowOrder = rememberUpdatedState<() -> Unit>(newValue = { showsSectionOrder = true })
    val filterSlot: @Composable () -> Unit = remember(inbox) {
        {
            BotInboxFilterMenuButton(
                inbox = inbox,
                state = latestState.value,
                onReorderSections = latestShowOrder.value,
            )
        }
    }
    val newChatSlot: @Composable () -> Unit = remember(inbox) {
        {
            BotInboxNewChatMenuButton(
                state = latestState.value,
                onCreateBot = { latestCreate.value(null) },
                onCreateRoom = latestCreateRoom.value,
                onReorderSections = latestShowOrder.value,
                usesAddIcon = false,
            )
        }
    }
    DisposableEffect(bar, filterSlot, newChatSlot) {
        bar?.setFilter(filterSlot)
        bar?.setNewChat(newChatSlot)
        onDispose {
            bar?.setFilter {}
            bar?.setNewChat {}
        }
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val paneWidth = minOf(maxWidth, 560.dp)
        Surface(
            modifier = Modifier
                .width(paneWidth)
                .fillMaxHeight()
                .align(Alignment.TopCenter),
            color = MaterialTheme.colorScheme.background,
        ) {
            Column(Modifier.fillMaxSize()) {
                if (header == null) {
                    BotsInboxToolbar(
                        state = state,
                        onSearch = openSearch,
                        onCreate = { onCreate(null) },
                        onCreateRoom = onCreateRoom,
                        onConnection = onConnection,
                        onReorderSections = { showsSectionOrder = true },
                    )
                }

                if (state.connection == null) {
                    if (header != null) header(openSearch)
                    BotConnectionWelcome(
                        onConnection = onConnection,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    Box(Modifier.weight(1f)) {
                        BotsInboxList(
                            inbox = inbox,
                            state = state,
                            header = header,
                            onSearch = openSearch,
                            onOpenBot = onOpenBot,
                            onOpenRoom = onOpenRoom,
                            onEdit = onEdit,
                            onDuplicate = { onCreate(it) },
                            onDelete = { deleting = it },
                            onMove = { moving = it },
                            onRenameRoom = { renamingRoom = it },
                            onDisbandRoom = { disbandingRoom = it },
                            onConnection = onConnection,
                            showsInlineHiddenControl = bar == null,
                            bottomPadding = if (bottomBar == null) 24.dp else bottomContentPadding,
                        )
                        if (bottomBar != null) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .fillMaxWidth(),
                            ) {
                                bottomBar()
                            }
                        }
                    }
                }
            }
        }
    }

    if (showsSearch && state.connection?.id == searchConnectionID) {
        BotSearchSheet(
            inbox = inbox,
            state = state,
            onDismiss = { showsSearch = false },
            onOpenBot = onOpenBot,
            onOpenRoom = onOpenRoom,
        )
    }
    if (showsSectionOrder) {
        BotSectionOrderSheet(
            inbox = inbox,
            state = state,
            onDismiss = { showsSectionOrder = false },
        )
    }
    deleting?.let { profile ->
        DeleteBotDialog(
            profile = profile,
            hostName = state.connection?.name ?: "Hermes",
            onDismiss = { deleting = null },
            onHide = {
                deleting = null
                actionScope.launch { inbox.setHidden(true, profile) }
            },
            onDelete = {
                deleting = null
                actionScope.launch { inbox.delete(profile) }
            },
        )
    }
    moving?.let { profile ->
        MoveBotSectionDialog(
            inbox = inbox,
            state = state,
            profile = profile,
            onDismiss = { moving = null },
        )
    }
    renamingRoom?.let { room ->
        RenameRoomDialog(
            room = room,
            onDismiss = { renamingRoom = null },
            onRename = { name ->
                renamingRoom = null
                actionScope.launch { inbox.renameRoom(room, name) }
            },
        )
    }
    disbandingRoom?.let { room ->
        DisbandRoomDialog(
            room = room,
            onDismiss = { disbandingRoom = null },
            onDisband = {
                disbandingRoom = null
                actionScope.launch { inbox.disbandRoom(room) }
            },
        )
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BotsInboxToolbar(
    state: BotInbox.State,
    onSearch: () -> Unit,
    onCreate: () -> Unit,
    onCreateRoom: () -> Unit,
    onConnection: () -> Unit,
    onReorderSections: () -> Unit,
) {
    CenterAlignedTopAppBar(
        // The pushed inbox has no visible title; keep its screen name for accessibility.
        title = { Box(Modifier.semantics { contentDescription = "Bots" }) },
        actions = {
            IconButton(onClick = onSearch, enabled = state.connection != null) {
                Icon(Icons.Filled.Search, contentDescription = "Search bots and messages")
            }
            BotInboxNewChatMenuButton(
                state = state,
                onCreateBot = onCreate,
                onCreateRoom = onCreateRoom,
                onReorderSections = onReorderSections,
                usesAddIcon = true,
            )
            IconButton(onClick = onConnection) {
                Icon(Icons.Filled.Settings, contentDescription = "Bot connection")
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BotsInboxList(
    inbox: BotInbox,
    state: BotInbox.State,
    header: (@Composable (onSearch: () -> Unit) -> Unit)?,
    onSearch: () -> Unit,
    onOpenBot: (BotProfile) -> Unit,
    onOpenRoom: (BotGroupRoom, Int?) -> Unit,
    onEdit: (BotProfile) -> Unit,
    onDuplicate: (BotProfile) -> Unit,
    onDelete: (BotProfile) -> Unit,
    onMove: (BotProfile) -> Unit,
    onRenameRoom: (BotGroupRoom) -> Unit,
    onDisbandRoom: (BotGroupRoom) -> Unit,
    onConnection: () -> Unit,
    showsInlineHiddenControl: Boolean,
    bottomPadding: Dp,
) {
    val coroutineScope = rememberCoroutineScope()
    val sections = remember(state) { state.sections }
    val pinned = remember(state) { state.pinned }
    val pinnedRows = remember(pinned) { pinned.chunked(3) }
    val headed = sections.any { it.name != null }

    PullToRefreshBox(
        isRefreshing = state.link == BotInbox.Link.CONNECTING && !state.isLoadingRoster,
        onRefresh = { inbox.openAsync() },
        modifier = Modifier.fillMaxSize(),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = bottomPadding),
        ) {
        if (header != null) {
            item(key = "home-header") { header(onSearch) }
        }

        val problem = state.errorMessage ?: state.routeAdvice
        if (problem != null) {
            item(key = "problem") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    horizontalAlignment = Alignment.Start,
                ) {
                    Text(problem, style = MaterialTheme.typography.bodyMedium)
                    TextButton(
                        onClick = if (state.needsSignIn) onConnection else ({ inbox.openAsync(); Unit }),
                    ) {
                        Text(if (state.needsSignIn) "Update sign-in" else "Reconnect")
                    }
                }
            }
        } else if (state.isLoadingRoster) {
            items(4, key = { "skeleton:$it" }) { index ->
                BotInboxSkeletonRow(
                    modifier = Modifier.semantics {
                        if (index == 0) contentDescription = "Loading bots"
                    },
                )
            }
        } else if (state.profiles.isEmpty() && state.rooms.isEmpty() && state.link == BotInbox.Link.LIVE) {
            item(key = "empty") {
                Text(
                    text = if (header == null) "No bots yet. Tap + to create one." else "No bots yet. Tap New Chat to create one.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp),
                )
            }
        }

        state.notice?.let { notice ->
            item(key = "notice") {
                Text(
                    text = notice,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }
        }

        if (pinned.isNotEmpty()) {
            item(key = "pinned") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    pinnedRows.forEach { row ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.Top,
                        ) {
                            row.forEach { chat ->
                                when (chat) {
                                    is BotInbox.ChatRow.Bot -> BotPinnedProfileTile(
                                        profile = chat.profile,
                                        avatar = state.avatars[chat.profile.id],
                                        unread = state.isUnread(chat.profile),
                                        status = state.liveStatuses[chat.profile.id],
                                        mayEdit = state.mayEdit(chat.profile),
                                        mayDelete = state.mayDelete(chat.profile),
                                        onOpen = { onOpenBot(chat.profile) },
                                        onEdit = { onEdit(chat.profile) },
                                        onPin = { coroutineScope.launch { inbox.setPinned(!chat.profile.pinned, chat.profile) } },
                                        onHide = { coroutineScope.launch { inbox.setHidden(!chat.profile.hidden, chat.profile) } },
                                        onMove = { onMove(chat.profile) },
                                        onDuplicate = { onDuplicate(chat.profile) },
                                        onDelete = { onDelete(chat.profile) },
                                    )
                                    is BotInbox.ChatRow.Room -> BotPinnedRoomTile(
                                        room = chat.room,
                                        state = state,
                                        onOpen = { onOpenRoom(chat.room, null) },
                                        onRename = { onRenameRoom(chat.room) },
                                        onPin = { inbox.setRoomPinned(!state.isRoomPinned(chat.room), chat.room) },
                                        onHide = { inbox.setRoomHidden(!state.isRoomHidden(chat.room), chat.room) },
                                        onDisband = { onDisbandRoom(chat.room) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        sections.forEach { section ->
            if (headed) {
                item(key = "header:${section.id}") {
                    Text(
                        text = section.name ?: "Other chats",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .semantics { heading() }
                            .padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 6.dp),
                    )
                }
            }
            items(section.chats, key = { it.id }) { chat ->
                when (chat) {
                    is BotInbox.ChatRow.Bot -> BotRosterRow(
                        profile = chat.profile,
                        avatar = state.avatars[chat.profile.id],
                        unread = state.isUnread(chat.profile),
                        status = state.liveStatuses[chat.profile.id],
                        dimmed = chat.profile.hidden,
                        selected = state.selection.profile?.id == chat.profile.id,
                        mayEdit = state.mayEdit(chat.profile),
                        mayDelete = state.mayDelete(chat.profile),
                        onOpen = { onOpenBot(chat.profile) },
                        onEdit = { onEdit(chat.profile) },
                        onPin = { coroutineScope.launch { inbox.setPinned(!chat.profile.pinned, chat.profile) } },
                        onHide = { coroutineScope.launch { inbox.setHidden(!chat.profile.hidden, chat.profile) } },
                        onMove = { onMove(chat.profile) },
                        onDuplicate = { onDuplicate(chat.profile) },
                        onDelete = { onDelete(chat.profile) },
                    )
                    is BotInbox.ChatRow.Room -> BotRoomRosterRow(
                        room = chat.room,
                        state = state,
                        selected = state.selection.room?.let {
                            it.server == inbox.server &&
                                it.connectionID == state.connection?.id &&
                                it.roomID == chat.room.id
                        } == true,
                        onOpen = { onOpenRoom(chat.room, null) },
                        onRename = { onRenameRoom(chat.room) },
                        onPin = { inbox.setRoomPinned(!state.isRoomPinned(chat.room), chat.room) },
                        onHide = { inbox.setRoomHidden(!state.isRoomHidden(chat.room), chat.room) },
                        onDisband = { onDisbandRoom(chat.room) },
                    )
                }
            }
        }

        if (showsInlineHiddenControl && state.hiddenCount > 0) {
            item(key = "hidden-toggle") {
                TextButton(
                    onClick = { inbox.setShowsHidden(!state.showsHidden) },
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Text(if (state.showsHidden) "Hide hidden" else "Show hidden (${state.hiddenCount})")
                }
            }
        }
    }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BotRosterRow(
    profile: BotProfile,
    avatar: android.graphics.Bitmap?,
    unread: Boolean,
    status: BotLiveStatus?,
    dimmed: Boolean,
    selected: Boolean,
    mayEdit: Boolean,
    mayDelete: Boolean,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    onPin: () -> Unit,
    onHide: () -> Unit,
    onMove: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val background = if (selected) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f) else Color.Transparent
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(background)
            .alpha(if (dimmed) 0.5f else 1f),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onOpen, onLongClick = { menuExpanded = true })
                .semantics(mergeDescendants = true) {}
                .padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            BotAvatarView(
                profile = profile,
                avatar = avatar,
                size = 44.dp,
                modifier = Modifier.clearAndSetSemantics {},
            )
            BotRosterText(profile, unread, status, Modifier.weight(1f))
            IconButton(onClick = { menuExpanded = true }, enabled = mayEdit) {
                Icon(Icons.Filled.MoreVert, contentDescription = "${profile.name} actions")
            }
        }
        BotProfileActionsMenu(
            expanded = menuExpanded,
            profile = profile,
            mayEdit = mayEdit,
            mayDelete = mayDelete,
            onDismiss = { menuExpanded = false },
            onEdit = onEdit,
            onPin = onPin,
            onHide = onHide,
            onMove = onMove,
            onDuplicate = onDuplicate,
            onDelete = onDelete,
            modifier = Modifier.align(Alignment.TopEnd),
        )
    }
}

@Composable
private fun BotRosterText(
    profile: BotProfile,
    unread: Boolean,
    status: BotLiveStatus?,
    modifier: Modifier = Modifier,
) {
    val accessibilityText = LocalDensity.current.fontScale >= 1.3f
    val chip = profile.description?.takeIf { it.length <= 24 && !profile.preview.isNullOrEmpty() }
    val subline = profile.preview?.takeIf { it.isNotEmpty() } ?: profile.description ?: profile.id
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (accessibilityText) {
            BotNameAndChip(profile.name, chip)
            BotRosterTrailing(status, profile.lastActive)
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(Modifier.weight(1f)) { BotNameAndChip(profile.name, chip) }
                BotRosterTrailing(status, profile.lastActive)
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = subline,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (unread) BotUnreadDot()
        }
    }
}

@Composable
private fun BotNameAndChip(name: String, chip: String?) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = name,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (chip != null) {
            Text(
                text = chip,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
    }
}

@Composable
private fun BotRosterTrailing(status: BotLiveStatus?, lastActive: Double?) {
    when (status) {
        BotLiveStatus.WAITING -> BotLiveStatusLabel(status)
        BotLiveStatus.WORKING -> BotLiveStatusLabel(status)
        null -> if (lastActive != null) {
            Text(
                text = botInboxDateLabel(lastActive),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun BotLiveStatusLabel(status: BotLiveStatus) {
    val colors = sessionRowStateColors()
    Text(
        text = if (status == BotLiveStatus.WAITING) "Waiting for you" else "Working",
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = if (status == BotLiveStatus.WAITING) colors.approval else colors.working,
        maxLines = 2,
    )
}

@Composable
private fun BotUnreadDot() {
    Box(
        modifier = Modifier
            .size(10.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary)
            .semantics { contentDescription = "Unread" },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BotPinnedProfileTile(
    profile: BotProfile,
    avatar: android.graphics.Bitmap?,
    unread: Boolean,
    status: BotLiveStatus?,
    mayEdit: Boolean,
    mayDelete: Boolean,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    onPin: () -> Unit,
    onHide: () -> Unit,
    onMove: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Box(modifier = Modifier.widthIn(max = 164.dp)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onOpen, onLongClick = { menuExpanded = true })
                .semantics(mergeDescendants = true) {}
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            BotAvatarView(
                profile = profile,
                avatar = avatar,
                size = 84.dp,
                modifier = Modifier.clearAndSetSemantics {},
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = profile.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (unread) {
                    Spacer(Modifier.width(6.dp))
                    BotUnreadDot()
                }
            }
            if (status != null) BotLiveStatusLabel(status)
        }
        IconButton(
            onClick = { menuExpanded = true },
            enabled = mayEdit,
            modifier = Modifier.align(Alignment.TopEnd),
        ) {
            Icon(Icons.Filled.MoreVert, contentDescription = "${profile.name} actions")
        }
        BotProfileActionsMenu(
            expanded = menuExpanded,
            profile = profile,
            mayEdit = mayEdit,
            mayDelete = mayDelete,
            onDismiss = { menuExpanded = false },
            onEdit = onEdit,
            onPin = onPin,
            onHide = onHide,
            onMove = onMove,
            onDuplicate = onDuplicate,
            onDelete = onDelete,
            modifier = Modifier.align(Alignment.TopEnd),
        )
    }
}

@Composable
private fun BotProfileActionsMenu(
    expanded: Boolean,
    profile: BotProfile,
    mayEdit: Boolean,
    mayDelete: Boolean,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onPin: () -> Unit,
    onHide: () -> Unit,
    onMove: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss, modifier = modifier) {
        DropdownMenuItem(
            text = { Text("Edit") },
            leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
            enabled = mayEdit,
            onClick = { onDismiss(); onEdit() },
        )
        DropdownMenuItem(
            text = { Text(if (profile.pinned) "Unpin" else "Pin") },
            leadingIcon = { Icon(Icons.Filled.PushPin, contentDescription = null) },
            enabled = mayEdit,
            onClick = { onDismiss(); onPin() },
        )
        DropdownMenuItem(
            text = { Text(if (profile.hidden) "Unhide" else "Hide bot") },
            leadingIcon = { Icon(if (profile.hidden) Icons.Filled.Visibility else Icons.Filled.VisibilityOff, contentDescription = null) },
            enabled = mayEdit,
            onClick = { onDismiss(); onHide() },
        )
        DropdownMenuItem(
            text = { Text("Move to Section…") },
            leadingIcon = { Icon(Icons.Filled.DriveFileMove, contentDescription = null) },
            enabled = mayEdit,
            onClick = { onDismiss(); onMove() },
        )
        DropdownMenuItem(
            text = { Text("Duplicate") },
            leadingIcon = { Icon(Icons.Filled.ContentCopy, contentDescription = null) },
            enabled = mayEdit,
            onClick = { onDismiss(); onDuplicate() },
        )
        if (mayDelete) {
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                onClick = { onDismiss(); onDelete() },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BotRoomRosterRow(
    room: BotGroupRoom,
    state: BotInbox.State,
    selected: Boolean,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onPin: () -> Unit,
    onHide: () -> Unit,
    onDisband: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val hidden = state.isRoomHidden(room)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f) else Color.Transparent)
            .alpha(if (hidden) 0.5f else 1f),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onOpen, onLongClick = { menuExpanded = true })
                .semantics(mergeDescendants = true) {}
                .padding(start = 12.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BotRoomInboxRow(
                room = room,
                roster = state.profiles,
                avatars = state.avatars,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { menuExpanded = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = "${room.name} actions")
            }
        }
        BotRoomActionsMenu(
            expanded = menuExpanded,
            room = room,
            state = state,
            onDismiss = { menuExpanded = false },
            onRename = onRename,
            onPin = onPin,
            onHide = onHide,
            onDisband = onDisband,
            modifier = Modifier.align(Alignment.TopEnd),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BotPinnedRoomTile(
    room: BotGroupRoom,
    state: BotInbox.State,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onPin: () -> Unit,
    onHide: () -> Unit,
    onDisband: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Box(modifier = Modifier.widthIn(max = 164.dp)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onOpen, onLongClick = { menuExpanded = true })
                .semantics(mergeDescendants = true) {}
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            BotRoomAvatars(
                room = room,
                roster = state.profiles,
                avatars = state.avatars,
                size = 60.dp,
                modifier = Modifier
                    .height(84.dp)
                    .clearAndSetSemantics {},
            )
            Text(
                text = room.name,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = { menuExpanded = true }, modifier = Modifier.align(Alignment.TopEnd)) {
            Icon(Icons.Filled.MoreVert, contentDescription = "${room.name} actions")
        }
        BotRoomActionsMenu(
            expanded = menuExpanded,
            room = room,
            state = state,
            onDismiss = { menuExpanded = false },
            onRename = onRename,
            onPin = onPin,
            onHide = onHide,
            onDisband = onDisband,
            modifier = Modifier.align(Alignment.TopEnd),
        )
    }
}

@Composable
private fun BotRoomActionsMenu(
    expanded: Boolean,
    room: BotGroupRoom,
    state: BotInbox.State,
    onDismiss: () -> Unit,
    onRename: () -> Unit,
    onPin: () -> Unit,
    onHide: () -> Unit,
    onDisband: () -> Unit,
    modifier: Modifier = Modifier,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss, modifier = modifier) {
        DropdownMenuItem(
            text = { Text("Rename") },
            leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
            enabled = state.mayRenameRoom(room),
            onClick = { onDismiss(); onRename() },
        )
        DropdownMenuItem(
            text = { Text(if (state.isRoomPinned(room)) "Unpin" else "Pin") },
            leadingIcon = { Icon(Icons.Filled.PushPin, contentDescription = null) },
            onClick = { onDismiss(); onPin() },
        )
        DropdownMenuItem(
            text = { Text(if (state.isRoomHidden(room)) "Unhide" else "Hide group") },
            leadingIcon = { Icon(if (state.isRoomHidden(room)) Icons.Filled.Visibility else Icons.Filled.VisibilityOff, contentDescription = null) },
            onClick = { onDismiss(); onHide() },
        )
        HorizontalDivider()
        DropdownMenuItem(
            text = { Text("Disband", color = if (state.mayDisbandRoom(room)) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)) },
            leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
            enabled = state.mayDisbandRoom(room),
            onClick = { onDismiss(); onDisband() },
        )
    }
}

@Composable
internal fun BotInboxFilterMenuButton(
    inbox: BotInbox,
    state: BotInbox.State,
    onReorderSections: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }, enabled = state.connection != null) {
            Icon(Icons.Filled.FilterList, contentDescription = "Filter Bots")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = {
                    Text(if (state.showsHidden) "Hide hidden" else "Show hidden (${state.hiddenCount})")
                },
                leadingIcon = {
                    Icon(if (state.showsHidden) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, contentDescription = null)
                },
                enabled = state.hiddenCount > 0 || state.showsHidden,
                onClick = {
                    expanded = false
                    inbox.setShowsHidden(!state.showsHidden)
                },
            )
            DropdownMenuItem(
                text = { Text("Reorder Sections…") },
                leadingIcon = { Icon(Icons.Filled.Reorder, contentDescription = null) },
                enabled = state.reorderableSectionNames.size >= 2,
                onClick = { expanded = false; onReorderSections() },
            )
        }
    }
}

@Composable
internal fun BotInboxNewChatMenuButton(
    state: BotInbox.State,
    onCreateBot: () -> Unit,
    onCreateRoom: () -> Unit,
    onReorderSections: () -> Unit,
    usesAddIcon: Boolean,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }, enabled = state.link == BotInbox.Link.LIVE) {
            Icon(
                imageVector = if (usesAddIcon) Icons.Filled.Add else Icons.Filled.AddComment,
                contentDescription = "New chat",
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("New Bot") },
                leadingIcon = { Icon(Icons.Filled.AddComment, contentDescription = null) },
                onClick = { expanded = false; onCreateBot() },
            )
            DropdownMenuItem(
                text = { Text("New Group Chat") },
                leadingIcon = { Icon(Icons.Filled.GroupAdd, contentDescription = null) },
                enabled = state.roomCapabilities.enabled && "groups.create" in state.roomCapabilities.methods,
                onClick = { expanded = false; onCreateRoom() },
            )
            if (usesAddIcon && state.reorderableSectionNames.size >= 2) {
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text("Reorder Sections…") },
                    leadingIcon = { Icon(Icons.Filled.Reorder, contentDescription = null) },
                    onClick = { expanded = false; onReorderSections() },
                )
            }
        }
    }
}

@Composable
private fun BotInboxSkeletonRow(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(
                Modifier
                    .fillMaxWidth(0.45f)
                    .height(16.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
            Box(
                Modifier
                    .fillMaxWidth(0.8f)
                    .height(14.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)),
            )
        }
    }
}

@Composable
private fun DeleteBotDialog(
    profile: BotProfile,
    hostName: String,
    onDismiss: () -> Unit,
    onHide: () -> Unit,
    onDelete: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete “${profile.name}”?") },
        text = {
            Text("Deletes this bot’s Profile on $hostName: its instructions, settings, skills, saved keys, and chat history. Drafts on this phone are removed too. This cannot be undone. Hiding keeps everything and only removes it from the list.")
        },
        confirmButton = {
            Row {
                TextButton(onClick = onHide) { Text("Hide Instead") }
                TextButton(onClick = onDelete) { Text("Delete Bot", color = MaterialTheme.colorScheme.error) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun MoveBotSectionDialog(
    inbox: BotInbox,
    state: BotInbox.State,
    profile: BotProfile,
    onDismiss: () -> Unit,
) {
    var name by remember(profile.id) { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Move “${profile.name}”") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                state.sectionNames.forEach { section ->
                    TextButton(
                        enabled = section.id != profile.sectionID || profile.sectionName == null,
                        onClick = {
                            onDismiss()
                            scope.launch { inbox.moveToSection(profile, section) }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(section.name, modifier = Modifier.weight(1f), textAlign = TextAlign.Start)
                        Icon(Icons.Filled.ArrowForward, contentDescription = null)
                    }
                }
                if (profile.sectionID != null) {
                    TextButton(
                        onClick = {
                            onDismiss()
                            scope.launch { inbox.removeFromSection(profile) }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Filled.RemoveCircleOutline, contentDescription = null)
                        Text("Remove from Section", modifier = Modifier.padding(start = 8.dp))
                    }
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("New section name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.trim().isNotEmpty(),
                onClick = {
                    val captured = name
                    onDismiss()
                    scope.launch { inbox.moveToNewSection(profile, captured) }
                },
            ) { Text("Move") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun RenameRoomDialog(
    room: BotGroupRoom,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit,
) {
    var name by remember(room.id) { mutableStateOf(room.name) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename group") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Group name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = "Enter a name of up to 200 characters.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = BotRoomRPC.validName(name) && name != room.name,
                onClick = { onRename(name) },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun DisbandRoomDialog(
    room: BotGroupRoom,
    onDismiss: () -> Unit,
    onDisband: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Disband “${room.name}”?") },
        text = { Text("The room and its history will be removed from every device. All bots in it will stop. The room cannot be restored.") },
        confirmButton = {
            TextButton(onClick = onDisband) {
                Text("Disband Group", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun botInboxDateLabel(epochSeconds: Double, now: Instant = Instant.now()): String {
    val zone = ZoneId.systemDefault()
    val value = Instant.ofEpochMilli((epochSeconds * 1_000.0).toLong()).atZone(zone)
    val current = now.atZone(zone)
    return when {
        value.toLocalDate() == current.toLocalDate() -> value.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))
        ChronoUnit.DAYS.between(value.toLocalDate(), current.toLocalDate()) in 1L..6L ->
            value.format(DateTimeFormatter.ofPattern("EEEE", Locale.getDefault()))
        else -> value.format(DateTimeFormatter.ofPattern("MMM d", Locale.getDefault()))
    }
}
