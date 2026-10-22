package io.github.jcartu.hermex.ui.feature.sessions

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AltRoute
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.jcartu.hermex.model.SessionSummary

/** Port of `SessionRowView.displayTitle(for:)`. */
fun sessionDisplayTitle(session: SessionSummary): String =
    session.title?.trim()?.takeIf { it.isNotEmpty() } ?: "Untitled Session"

/** Port of `SessionRowView.isActiveStreaming(_:)`. */
fun isActiveStreaming(session: SessionSummary): Boolean =
    session.isStreaming == true || !session.activeStreamId?.trim().isNullOrEmpty()

/** Port of `SessionRowView.metadataLabel(for:showsMessageCount:showsWorkspace:)`. */
fun sessionMetadataLabel(
    session: SessionSummary,
    showsMessageCount: Boolean,
    showsWorkspace: Boolean,
): String? {
    val parts = listOfNotNull(
        messageCountLabel(session, showsMessageCount),
        workspaceLabel(session, showsWorkspace),
    )
    return if (parts.isEmpty()) null else parts.joinToString(" • ")
}

private fun messageCountLabel(session: SessionSummary, showsMessageCount: Boolean): String? {
    if (!showsMessageCount) return null
    val count = session.messageCount ?: return null
    if (count < 0) return null
    return "$count messages"
}

private fun workspaceLabel(session: SessionSummary, showsWorkspace: Boolean): String? {
    if (!showsWorkspace) return null
    val workspace = session.workspace?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val lastPathComponent = workspace.trimEnd('/').substringAfterLast('/')
    return lastPathComponent.ifEmpty { workspace }
}

/**
 * Abbreviated relative timestamp, mirroring the iOS row's
 * `RelativeDateTimeFormatter` with `.abbreviated` units.
 */
fun sessionRelativeTimeLabel(
    session: SessionSummary,
    nowEpochSeconds: Long = System.currentTimeMillis() / 1000,
): String? {
    val timestamp = session.lastMessageAt ?: session.updatedAt ?: session.createdAt ?: return null
    if (timestamp <= 0.0) return null

    val deltaSeconds = nowEpochSeconds - timestamp.toLong()
    if (deltaSeconds < 60) return "now"

    val minutes = deltaSeconds / 60
    if (minutes < 60) return "${minutes}m ago"

    val hours = minutes / 60
    if (hours < 24) return "${hours}h ago"

    val days = hours / 24
    if (days < 7) return "${days}d ago"

    val weeks = days / 7
    if (days < 30) return "${weeks}w ago"

    val months = days / 30
    if (months < 12) return "${months}mo ago"

    return "${days / 365}y ago"
}

/**
 * Port of `SessionRowView` (1.6.0 row states, 1.7.0 unread dot, 1.8.0 "rows no
 * longer pulse a redundant streaming dot" — streaming carries no dot or badge
 * at all now; the trailing "Working" label already says it).
 *
 * One trailing slot, one meaning: the attention state when the session wants
 * something, otherwise the relative time. Never both, so the row keeps its
 * height in every state. Only the time text ticks, once a minute (the caller
 * passes [nowEpochSeconds] from its resumed-only ticker), so an idle list
 * stays current without re-rendering whole rows.
 *
 * Keeps its content compact in narrow panes: below ~220dp the excerpt, fork
 * subtitle, supplemental line (badges + metadata) and the relative date are
 * hidden, leaving the ellipsized title with its attention state.
 */
@Composable
fun SessionRow(
    session: SessionSummary,
    showsMessageCount: Boolean,
    showsWorkspace: Boolean,
    isViewingCachedData: Boolean,
    modifier: Modifier = Modifier,
    isUnread: Boolean = false,
    attentionState: SessionRowAttentionState? = null,
    searchExcerpt: SessionSearchExcerpt? = null,
    forkOrigin: ForkOrigin? = null,
    onOpenForkParent: (() -> Unit)? = null,
    nowEpochSeconds: Long = System.currentTimeMillis() / 1000,
) {
    val stateColors = sessionRowStateColors()

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val isNarrow = maxWidth < 220.dp
        val metadata = sessionMetadataLabel(session, showsMessageCount, showsWorkspace)
        val model = session.model?.trim()?.takeIf { it.isNotEmpty() }
        val showsSupplemental = !isNarrow &&
            (metadata != null || isViewingCachedData || session.isSessionReadOnly || model != null)
        val showsExcerpt = !isNarrow && searchExcerpt != null
        val showsFork = !isNarrow && forkOrigin != null

        val minHeight = (if (showsSupplemental) 54 else 46) +
            (if (showsExcerpt) 16 else 0) +
            (if (showsFork) 16 else 0)

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = minHeight.dp)
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .semantics { contentDescription = sessionRowAccessibilitySummary(session, isViewingCachedData, isUnread, attentionState, searchExcerpt, forkOrigin, metadata) },
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // The unread dot shows only when no attention state claims the row
            // (SessionRowView.body: `isUnread && effectiveAttentionState == nil`).
            if (isUnread && attentionState == null) {
                Box(
                    Modifier
                        .padding(top = 7.dp)
                        .size(10.dp)
                        .background(stateColors.unread, CircleShape),
                )
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = sessionDisplayTitle(session),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )

                    if (session.pinned == true) {
                        Spacer(Modifier.width(6.dp))
                        Icon(
                            imageVector = Icons.Filled.PushPin,
                            contentDescription = "Pinned",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(12.dp),
                        )
                    }

                    // Trailing status slot: attention label or relative time.
                    if (attentionState != null) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = attentionState.title,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = attentionState.tint(stateColors),
                            maxLines = 1,
                        )
                    } else if (!isNarrow) {
                        sessionRelativeTimeLabel(session, nowEpochSeconds)?.let { relative ->
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = relative,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                    }
                }

                if (showsExcerpt && searchExcerpt != null) {
                    Text(
                        text = searchExcerpt.highlighted(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                if (showsFork && forkOrigin != null) {
                    ForkOriginSubtitle(
                        origin = forkOrigin,
                        onOpen = onOpenForkParent?.takeIf { forkOrigin.parent != null },
                    )
                }

                if (showsSupplemental) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        if (isViewingCachedData) {
                            SessionRowBadge("Cached", stateColors.cached)
                        }
                        if (session.isSessionReadOnly) {
                            SessionRowBadge(
                                "Read-only",
                                MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        model?.let {
                            SessionRowBadge(
                                text = it,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                        }
                        metadata?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * The row's fork subtitle: "Forked from <parent>", tappable when the list
 * holds the parent. Styled as a quiet one-line metadata row (the transcript's
 * full-width `ForkOriginRowView` belongs to the chat pane).
 */
@Composable
private fun ForkOriginSubtitle(origin: ForkOrigin, onOpen: (() -> Unit)?) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onOpen != null) Modifier.clickable(onClick = onOpen) else Modifier),
    ) {
        Icon(
            imageVector = Icons.Filled.AltRoute,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(12.dp),
        )
        Text(
            text = origin.title,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Port of `SessionRowView.accessibilitySummary` (row-level subset). */
private fun sessionRowAccessibilitySummary(
    session: SessionSummary,
    isViewingCachedData: Boolean,
    isUnread: Boolean,
    attentionState: SessionRowAttentionState?,
    searchExcerpt: SessionSearchExcerpt?,
    forkOrigin: ForkOrigin?,
    metadataLabel: String?,
): String {
    val parts = mutableListOf(sessionDisplayTitle(session))

    searchExcerpt?.let { parts.add("Matched: ${it.text}") }
    forkOrigin?.let { parts.add(it.title) }

    if (attentionState != null) {
        parts.add(attentionState.accessibilityLabel)
    } else if (isUnread) {
        parts.add("Unread")
    }

    if (session.pinned == true) parts.add("Pinned")
    if (isViewingCachedData) parts.add("Cached")
    if (session.isSessionReadOnly) parts.add("Read-only")

    metadataLabel?.let(parts::add)
    sessionRelativeTimeLabel(session)?.let(parts::add)

    return parts.joinToString(", ")
}

@Composable
private fun SessionRowBadge(text: String, tint: Color, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        color = tint,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .background(tint.copy(alpha = 0.12f), RoundedCornerShape(8.dp))
            .padding(horizontal = 5.dp, vertical = 2.dp),
    )
}
