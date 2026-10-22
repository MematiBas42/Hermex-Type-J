package io.github.jcartu.hermex.ui.feature.bots

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.LocalFocusManager
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.jcartu.hermex.model.hermes.BotGroupRoom
import io.github.jcartu.hermex.model.hermes.BotProfile
import io.github.jcartu.hermex.ui.adaptive.LocalFoldState
import io.github.jcartu.hermex.ui.adaptive.LocalPaneCount
import io.github.jcartu.hermex.ui.feature.sessions.SessionSearchExcerpt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

private enum class BotSearchScope(val title: String) {
    ALL("All"),
    BOTS("Bots"),
    MESSAGES("Messages"),
}

/**
 * All / Bots / Messages search from `Features/Bots/BotSearchView.swift`.
 *
 * Roster and room-name matches are immediate and include hidden bots when the query matches.
 * Canonical Bot Chats are searched on the host through [BotInbox.searchBotChats]; room messages
 * are searched only in [BotHistoryCache], because the phone indexes only room pages it loaded.
 * A cached room identity is admitted only while the inbox has no authoritative live room list.
 */
@Composable
fun BotSearchSheet(
    inbox: BotInbox,
    state: BotInbox.State,
    onDismiss: () -> Unit,
    onOpenBot: (BotProfile) -> Unit,
    onOpenRoom: (BotGroupRoom, Int?) -> Unit,
    modifier: Modifier = Modifier,
    cache: BotHistoryCache? = null,
) {
    val resolvedCache = cache ?: BotHistoryCache.shared(LocalContext.current)
    var query by rememberSaveable { mutableStateOf("") }
    var scope by rememberSaveable { mutableStateOf(BotSearchScope.ALL) }
    var roomHits by remember { mutableStateOf<List<BotHistoryCache.Hit>>(emptyList()) }
    var botHits by remember { mutableStateOf<List<BotInbox.BotChatHit>>(emptyList()) }
    var localSearching by remember { mutableStateOf(false) }
    var hostSearching by remember { mutableStateOf(false) }
    var localError by remember { mutableStateOf(false) }
    var hostError by remember { mutableStateOf<String?>(null) }
    val trimmedQuery = query.trim()
    val connectionID = state.connection?.id
    val roomIDs = remember(state.hasRoomList, state.link, state.rooms) { state.searchableRoomIDs }
    val profileIDs = remember(state.profiles) { state.profiles.map { it.id } }
    val includesMessages = scope != BotSearchScope.BOTS
    val selectBot: (BotProfile) -> Unit = { candidate ->
        val latest = inbox.current
        if (connectionID != null && latest.connection?.id == connectionID) {
            latest.profiles.firstOrNull { it.id == candidate.id }?.let { profile ->
                onDismiss()
                onOpenBot(profile)
            }
        }
    }
    val selectRoom: (BotGroupRoom) -> Unit = { candidate ->
        val latest = inbox.current
        if (connectionID != null && latest.connection?.id == connectionID) {
            latest.rooms.firstOrNull { it.id == candidate.id }?.let { room ->
                onDismiss()
                onOpenRoom(room, null)
            }
        }
    }
    val selectRoomHit: (BotHistoryCache.Hit) -> Unit = { hit ->
        val latest = inbox.current
        if (connectionID != null && latest.connection?.id == connectionID) {
            val room = if (latest.searchableRoomIDs != null) {
                latest.rooms.firstOrNull { it.id == hit.roomID }
            } else {
                latest.rooms.firstOrNull { it.id == hit.roomID } ?: hit.cachedRoom
            }
            room?.let {
                onDismiss()
                onOpenRoom(it, hit.sequence)
            }
        }
    }

    LaunchedEffect(
        trimmedQuery,
        scope,
        connectionID,
        profileIDs,
        roomIDs,
        state.link,
    ) {
        roomHits = emptyList()
        botHits = emptyList()
        localError = false
        hostError = null
        localSearching = false
        hostSearching = false
        if (!includesMessages || trimmedQuery.isEmpty() || connectionID == null) return@LaunchedEffect

        localSearching = true
        hostSearching = state.link == BotInbox.Link.LIVE
        delay(200)

        try {
            roomHits = resolvedCache.search(
                query = trimmedQuery,
                server = inbox.server,
                connectionID = connectionID,
                roomIDs = roomIDs,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            localError = true
        } finally {
            localSearching = false
        }

        if (state.link == BotInbox.Link.LIVE) {
            try {
                botHits = inbox.searchBotChats(trimmedQuery)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                hostError = error.localizedMessage ?: "Could not search Bot chats."
            } finally {
                hostSearching = false
            }
        }
    }

    BotAdaptiveSheet(onDismiss = onDismiss, maxContentWidth = 640.dp) {
        BotSearchControls(
            query = query,
            onQueryChange = { query = it },
            scope = scope,
            onScopeChange = { scope = it },
            onDismiss = onDismiss,
        )

        val rosterMatches = remember(state.profiles, state.showsHidden, trimmedQuery) {
            val rows = state.rows(trimmedQuery)
            rows.pinned + rows.others + rows.hidden
        }
        val roomMatches = remember(state.rooms, state.roomCapabilities, trimmedQuery) {
            state.rooms(trimmedQuery)
        }
        val rosterByID = remember(state.profiles) { state.profiles.associateBy { it.id } }
        val visibleRoomHits = remember(roomHits, state.rooms, roomIDs) {
            if (roomIDs == null) roomHits
            else roomHits.filter { hit -> state.rooms.any { it.id == hit.roomID } }
        }

        LazyColumn(
            modifier = modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp),
        ) {
            if (scope != BotSearchScope.MESSAGES) {
                items(rosterMatches, key = { "bot:${it.id}" }) { profile ->
                    BotSearchProfileResult(
                        profile = profile,
                        avatar = state.avatars[profile.id],
                        onClick = { selectBot(profile) },
                    )
                }
                items(roomMatches, key = { "room:${it.id}" }) { room ->
                    BotRoomSearchResult(
                        room = room,
                        state = state,
                        onClick = { selectRoom(room) },
                    )
                }
                if (scope == BotSearchScope.BOTS && rosterMatches.isEmpty() && roomMatches.isEmpty()) {
                    item(key = "no-bots") { SearchStatus("No bots found.") }
                }
            }

            if (scope != BotSearchScope.BOTS) {
                if (trimmedQuery.isNotEmpty()) {
                    items(botHits, key = { "host:${it.profileID}" }) { hit ->
                        rosterByID[hit.profileID]?.let { profile ->
                            if (hit.isFromUser == null) {
                                BotSearchProfileResult(
                                    profile = profile,
                                    avatar = state.avatars[profile.id],
                                    onClick = { selectBot(profile) },
                                )
                            } else {
                                BotChatMessageResult(
                                    profile = profile,
                                    avatar = state.avatars[profile.id],
                                    hit = hit,
                                    query = trimmedQuery,
                                    onClick = { selectBot(profile) },
                                )
                            }
                        }
                    }
                    if (hostSearching && !localSearching) {
                        item(key = "host-searching") { SearchStatus("Searching…") }
                    } else if (hostError != null) {
                        item(key = "host-error") { SearchStatus(hostError.orEmpty()) }
                    }
                }

                item(key = "local-label") {
                    Text(
                        text = "Messages saved on this phone",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 8.dp),
                    )
                }

                if (trimmedQuery.isNotEmpty()) {
                    items(visibleRoomHits, key = { "local:${it.id}" }) { hit ->
                        val room = state.rooms.firstOrNull { it.id == hit.roomID } ?: hit.cachedRoom
                        BotRoomMessageResult(
                            hit = hit,
                            room = room,
                            query = trimmedQuery,
                            state = state,
                            onClick = { selectRoomHit(hit) },
                        )
                    }
                    when {
                        localSearching -> item(key = "local-searching") { SearchStatus("Searching…") }
                        localError -> item(key = "local-error") { SearchStatus("Could not search saved messages.") }
                        visibleRoomHits.isEmpty() -> item(key = "local-empty") { SearchStatus("No saved messages found.") }
                        visibleRoomHits.size == BotHistoryCache.MAXIMUM_HITS -> item(key = "local-limit") {
                            SearchStatus("Showing the first 100 matches. Refine your search to find more.")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BotSearchControls(
    query: String,
    onQueryChange: (String) -> Unit,
    scope: BotSearchScope,
    onScopeChange: (BotSearchScope) -> Unit,
    onDismiss: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            IconButton(onClick = onDismiss) {
                Icon(Icons.Filled.Close, contentDescription = "Close search")
            }
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focusRequester),
                singleLine = true,
                label = { Text("Search bots and messages") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { onQueryChange("") }) {
                            Icon(Icons.Filled.Clear, contentDescription = "Clear search")
                        }
                    }
                },
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    imeAction = ImeAction.Search,
                ),
                keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            BotSearchScope.entries.forEach { item ->
                FilterChip(
                    selected = item == scope,
                    onClick = { onScopeChange(item) },
                    label = { Text(item.title) },
                )
            }
        }
    }
}

@Composable
private fun BotSearchProfileResult(
    profile: BotProfile,
    avatar: android.graphics.Bitmap?,
    onClick: () -> Unit,
) {
    SearchResultRow(
        avatar = {
            BotAvatarView(
                profile = profile,
                avatar = avatar,
                size = 44.dp,
                modifier = Modifier.clearAndSetSemantics {},
            )
        },
        title = profile.name,
        kind = "Bot",
        excerpt = profile.description,
        onClick = onClick,
    )
}

@Composable
private fun BotRoomSearchResult(
    room: BotGroupRoom,
    state: BotInbox.State,
    onClick: () -> Unit,
) {
    SearchResultRow(
        avatar = {
            BotRoomAvatars(
                room = room,
                roster = state.profiles,
                avatars = state.avatars,
                size = 32.dp,
                modifier = Modifier
                    .size(44.dp)
                    .clearAndSetSemantics {},
            )
        },
        title = room.name,
        kind = "Group",
        excerpt = room.members.joinToString { it.name }.ifEmpty { null },
        onClick = onClick,
    )
}

@Composable
private fun BotChatMessageResult(
    profile: BotProfile,
    avatar: android.graphics.Bitmap?,
    hit: BotInbox.BotChatHit,
    query: String,
    onClick: () -> Unit,
) {
    val highlighted = remember(hit.snippet, query) {
        hit.snippet?.let { highlightedHermesSnippet(it, query) }
    }
    val title = if (hit.isFromUser == true) "You to ${profile.name}" else "${profile.name} to you"
    SearchResultRow(
        avatar = {
            BotAvatarView(
                profile = profile,
                avatar = avatar,
                size = 44.dp,
                modifier = Modifier.clearAndSetSemantics {},
            )
        },
        title = title,
        kind = "Message",
        excerpt = highlighted?.text,
        query = query,
        annotatedExcerpt = highlighted,
        onClick = onClick,
    )
}

@Composable
private fun BotRoomMessageResult(
    hit: BotHistoryCache.Hit,
    room: BotGroupRoom,
    query: String,
    state: BotInbox.State,
    onClick: () -> Unit,
) {
    SearchResultRow(
        avatar = {
            BotRoomAvatars(
                room = room,
                roster = state.profiles,
                avatars = state.avatars,
                size = 32.dp,
                modifier = Modifier
                    .size(44.dp)
                    .clearAndSetSemantics {},
            )
        },
        title = listOfNotNull(room.name, hit.sender).joinToString(" · "),
        kind = "Message",
        excerpt = hit.excerpt,
        query = query,
        onClick = onClick,
    )
}

@Composable
private fun SearchResultRow(
    avatar: @Composable () -> Unit,
    title: String,
    kind: String,
    excerpt: String?,
    onClick: () -> Unit,
    query: String = "",
    annotatedExcerpt: AnnotatedString? = null,
) {
    val accessibilityText = LocalDensity.current.fontScale >= 1.3f
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .semantics(mergeDescendants = true) {}
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        avatar()
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = if (accessibilityText) 2 else 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = kind,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!excerpt.isNullOrBlank()) {
                Text(
                    text = annotatedExcerpt ?: if (query.isEmpty()) AnnotatedString(excerpt)
                    else SessionSearchExcerpt.of(excerpt, query).highlighted(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = if (accessibilityText) 3 else 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Parses Hermes FTS `>>>match<<<` spans; unmarked substring hits fall back to query bolding. */
private fun highlightedHermesSnippet(raw: String, query: String): AnnotatedString {
    val text = SessionSearchExcerpt.displayText(raw)
    var cursor = 0
    var bold = false
    var hasMarks = false
    val marked = buildAnnotatedString {
        while (cursor < text.length) {
            val open = text.indexOf(\">>>\", startIndex = cursor).takeIf { it >= 0 }
            val close = text.indexOf(\"<<<\", startIndex = cursor).takeIf { it >= 0 }
            val next = listOfNotNull(open?.let { it to true }, close?.let { it to false })
                .minByOrNull { it.first }
            val end = next?.first ?: text.length
            val piece = text.substring(cursor, end)
            if (bold) withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(piece) }
            else append(piece)
            if (next == null) break
            hasMarks = true
            bold = next.second
            cursor = end + 3
        }
    }
    return if (hasMarks) marked else SessionSearchExcerpt.of(text, query).highlighted()
}

@Composable
private fun SearchStatus(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(20.dp),
    )
}

/** Adaptive modal used by Bot search and section ordering without crossing a vertical hinge. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BotAdaptiveSheet(
    onDismiss: () -> Unit,
    maxContentWidth: Dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (LocalPaneCount.current == 1) {
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.94f)
                    .imePadding(),
                content = content,
            )
        }
    } else {
        Dialog(
            onDismissRequest = onDismiss,
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss,
                    ),
            ) {
                val density = LocalDensity.current
                val (segmentStartPx, segmentWidthPx) = widestBotSheetSegment(
                    windowWidthPx = constraints.maxWidth.toFloat(),
                    hingeCentersPx = LocalFoldState.current.verticalHingeCenters(),
                )
                val segmentStart = with(density) { segmentStartPx.toDp() }
                val segmentWidth = with(density) { segmentWidthPx.toDp() }
                val maxHeight = with(density) { (constraints.maxHeight * 0.9f).toDp() }
                Box(
                    modifier = Modifier
                        .offset(x = segmentStart)
                        .width(segmentWidth)
                        .fillMaxHeight(),
                    contentAlignment = Alignment.Center,
                ) {
                    Surface(
                        modifier = Modifier
                            .padding(24.dp)
                            .widthIn(max = maxContentWidth)
                            .heightIn(max = minOf(760.dp, maxHeight))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = {},
                            ),
                        shape = RoundedCornerShape(28.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        tonalElevation = 6.dp,
                    ) {
                        Column(
                            modifier = Modifier.imePadding(),
                            content = content,
                        )
                    }
                }
            }
        }
    }
}

private fun widestBotSheetSegment(windowWidthPx: Float, hingeCentersPx: List<Float>): Pair<Float, Float> {
    if (windowWidthPx <= 0f) return 0f to windowWidthPx
    val centers = hingeCentersPx.filter { it > 0f && it < windowWidthPx }.sorted()
    if (centers.isEmpty()) return 0f to windowWidthPx
    val edges = listOf(0f) + centers + listOf(windowWidthPx)
    return edges.zipWithNext()
        .map { (start, end) -> start to end - start }
        .maxBy { it.second }
}
