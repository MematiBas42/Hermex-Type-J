package io.github.jcartu.hermex.ui.feature.sessions

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.jcartu.hermex.ui.theme.SuccessDark
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
 * Port of `SessionRowView`. Keeps its content compact in narrow panes: below
 * ~220dp the supplemental line (badges + metadata) and the relative date are
 * hidden, leaving just the ellipsized title.
 */
@Composable
fun SessionRow(
    session: SessionSummary,
    showsMessageCount: Boolean,
    showsWorkspace: Boolean,
    isViewingCachedData: Boolean,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val isNarrow = maxWidth < 220.dp
        val streaming = isActiveStreaming(session)
        val metadata = sessionMetadataLabel(session, showsMessageCount, showsWorkspace)
        val model = session.model?.trim()?.takeIf { it.isNotEmpty() }
        val showsSupplemental = !isNarrow && (metadata != null || streaming || isViewingCachedData || model != null)

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = if (showsSupplemental) 54.dp else 46.dp)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (streaming) {
                Box(
                    Modifier
                        .padding(top = 7.dp)
                        .size(9.dp)
                        .background(SessionRowColors.streaming, CircleShape),
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

                    if (!isNarrow) {
                        sessionRelativeTimeLabel(session)?.let { relative ->
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

                if (showsSupplemental) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        if (streaming) {
                            SessionRowBadge("Live", SessionRowColors.streaming)
                        }
                        if (isViewingCachedData) {
                            SessionRowBadge("Cached", SessionRowColors.cached)
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

private object SessionRowColors {
    /** iOS `.green` streaming tint — the theme's success color. */
    val streaming = SuccessDark

    /** iOS `.orange` cached tint (no theme equivalent). */
    val cached = Color(0xFFFB923C)
}
