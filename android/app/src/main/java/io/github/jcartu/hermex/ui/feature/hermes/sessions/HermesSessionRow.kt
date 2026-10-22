package io.github.jcartu.hermex.ui.feature.hermes.sessions

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import io.github.jcartu.hermex.ui.adaptive.LocalPaneCount
import io.github.jcartu.hermex.ui.feature.sessions.SessionListSettings
import io.github.jcartu.hermex.ui.feature.sessions.SessionRow

/** Port of SessionRowActionPolicy's Hermes arm; the actual row is the shared SessionRow composable. */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun HermesSessionInteractiveRow(
    row: HermesListedSession, state: HermesSessionListState, settings: SessionListSettings,
    showsRowStates: Boolean, selected: Boolean, now: Long,
    open: () -> Unit, unread: () -> Unit, pin: () -> Unit, rename: () -> Unit,
    duplicate: () -> Unit, move: () -> Unit, archive: () -> Unit, delete: () -> Unit, export: () -> Unit,
) {
    var expanded by remember(row.id) { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val offersMutation = !row.row.isBotChat && !row.summary().isSessionReadOnly
    val enabled = state.canMutate(row)
    val content: @Composable () -> Unit = {
        Box {
            SessionRow(
                session = row.summary(state.projectOwners[row.row.id]), showsMessageCount = settings.showsMessageCount,
                showsWorkspace = settings.showsWorkspace, isViewingCachedData = state.showsOfflineCache,
                displayTitle = row.displayTitle, profileTag = row.profile.takeIf { state.showsAllProfiles },
                labelsArchived = !state.archived, isUnread = state.isUnread(row),
                attentionState = if (showsRowStates) state.attention(row) else null,
                searchExcerpt = state.excerpt(row), nowEpochSeconds = now,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
                    .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = .12f) else MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
                    .combinedClickable(onClick = open, onLongClick = { expanded = true }),
            )
            DropdownMenu(expanded, { expanded = false }) {
                fun action(run: () -> Unit): () -> Unit = { expanded = false; run() }
                DropdownMenuItem(text = { Text("Copy Full Title") }, onClick = action { clipboard.setText(AnnotatedString(row.displayTitle)) })
                if (!row.row.isBotChat) DropdownMenuItem(text = { Text(if (state.isUnread(row)) "Mark as Read" else "Mark as Unread") }, enabled = enabled, onClick = action(unread))
                if (offersMutation) {
                    HorizontalDivider()
                    DropdownMenuItem(text = { Text(if (row.row.pinned == true) "Unpin" else "Pin") }, enabled = enabled, onClick = action(pin))
                    DropdownMenuItem(text = { Text("Rename") }, enabled = enabled && !state.isRenamingSession, onClick = action(rename))
                    DropdownMenuItem(text = { Text("Duplicate") }, enabled = enabled, onClick = action(duplicate))
                    DropdownMenuItem(text = { Text("Move to Project") }, enabled = enabled, onClick = action(move))
                }
                HorizontalDivider()
                DropdownMenuItem(text = { Text("Export as JSON") }, enabled = state.isLive && !state.isViewingCachedData && row.key !in state.mutating, onClick = action(export))
                if (offersMutation || state.archived) {
                    HorizontalDivider()
                    if (state.archived || row.row.archived != true) DropdownMenuItem(
                        text = { Text(if (state.archived) "Restore" else "Archive") },
                        enabled = state.isLive && !state.isViewingCachedData && row.key !in state.mutating,
                        onClick = action(archive),
                    )
                    DropdownMenuItem(text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                        enabled = state.isLive && !state.isViewingCachedData && row.key !in state.mutating, onClick = action(delete))
                }
            }
        }
    }
    if (LocalPaneCount.current == 1 && enabled && !state.archived && row.row.archived != true) {
        val latestPin by rememberUpdatedState(pin)
        val latestArchive by rememberUpdatedState(archive)
        val dismiss = rememberSwipeToDismissBoxState(confirmValueChange = {
            when (it) { SwipeToDismissBoxValue.StartToEnd -> latestPin(); SwipeToDismissBoxValue.EndToStart -> latestArchive(); else -> Unit }
            false
        })
        SwipeToDismissBox(state = dismiss, backgroundContent = {
            Row(Modifier.fillMaxSize().padding(horizontal = 24.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(if (row.row.pinned == true) "Unpin" else "Pin"); Text("Archive")
            }
        }) { content() }
    } else content()
}
