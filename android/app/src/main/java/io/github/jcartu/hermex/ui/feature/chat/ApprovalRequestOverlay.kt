package io.github.jcartu.hermex.ui.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import io.github.jcartu.hermex.model.ApprovalChoice

/**
 * Port of `ApprovalRequestOverlay.swift`: a modal scrim with a decision card —
 * header ("Approval required" + pending count), optional description, the
 * command in a horizontally scrollable monospace box, pattern-key chips, an
 * inline error line, and the five decision actions (Allow once / Allow
 * session / Always allow / Deny / Skip all this session). All actions are
 * disabled while a response is in flight.
 */
@Composable
fun ApprovalRequestOverlay(
    prompt: ApprovalPromptState,
    isResponding: Boolean,
    errorMessage: String?,
    onChoice: (ApprovalChoice) -> Unit,
    onSkipAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrimInteraction = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .fillMaxSize()
            .zIndex(10f)
            .background(Color.Black.copy(alpha = 0.38f))
            // Swallow taps so the transcript behind is inert (modal semantics).
            .clickable(
                interactionSource = scrimInteraction,
                indication = null,
                onClick = {},
            ),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier
                .padding(horizontal = 18.dp)
                .widthIn(max = 520.dp),
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
            shadowElevation = 12.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                ApprovalHeader(pendingCount = prompt.pendingCount)
                ApprovalDetails(prompt = prompt, errorMessage = errorMessage)
                ApprovalActions(
                    isResponding = isResponding,
                    onChoice = onChoice,
                    onSkipAll = onSkipAll,
                )
            }
        }
    }
}

@Composable
private fun ApprovalHeader(pendingCount: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(
            imageVector = Icons.Filled.Warning,
            contentDescription = null,
            tint = Color(0xFFFFCC00),
        )
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = "Approval required",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = "Pending approvals: $pendingCount",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ApprovalDetails(prompt: ApprovalPromptState, errorMessage: String?) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        nonEmptyText(prompt.pending.description)?.let { description ->
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        nonEmptyText(prompt.pending.command)?.let { command ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        shape = RoundedCornerShape(8.dp),
                    )
                    .horizontalScroll(rememberScrollState()),
            ) {
                Text(
                    text = command,
                    modifier = Modifier.padding(10.dp),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                )
            }
        }

        if (prompt.patternKeys.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "Pattern keys",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                prompt.patternKeys.forEach { key ->
                    Text(
                        text = key,
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier
                            .background(
                                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                                shape = CircleShape,
                            )
                            .padding(horizontal = 8.dp, vertical = 5.dp),
                    )
                }
            }
        }

        if (prompt.pendingCount > 1) {
            Text(
                text = "1 of ${prompt.pendingCount} pending",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        nonEmptyText(errorMessage)?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun ApprovalActions(
    isResponding: Boolean,
    onChoice: (ApprovalChoice) -> Unit,
    onSkipAll: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ApprovalDecisionButton(
                title = "Allow once",
                icon = Icons.Filled.CheckCircle,
                prominent = true,
                enabled = !isResponding,
                onClick = { onChoice(ApprovalChoice.ONCE) },
                modifier = Modifier.weight(1f),
            )
            ApprovalDecisionButton(
                title = "Allow session",
                icon = Icons.Filled.LockOpen,
                prominent = false,
                enabled = !isResponding,
                onClick = { onChoice(ApprovalChoice.SESSION) },
                modifier = Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ApprovalDecisionButton(
                title = "Always allow",
                icon = Icons.Filled.Star,
                prominent = false,
                enabled = !isResponding,
                onClick = { onChoice(ApprovalChoice.ALWAYS) },
                modifier = Modifier.weight(1f),
            )
            ApprovalDecisionButton(
                title = "Deny",
                icon = Icons.Filled.Cancel,
                prominent = false,
                destructive = true,
                enabled = !isResponding,
                onClick = { onChoice(ApprovalChoice.DENY) },
                modifier = Modifier.weight(1f),
            )
        }
        OutlinedButton(
            onClick = onSkipAll,
            enabled = !isResponding,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(
                imageVector = Icons.Filled.Bolt,
                contentDescription = null,
                modifier = Modifier.padding(end = 6.dp),
            )
            Text("Skip all this session")
        }
    }
}

@Composable
private fun ApprovalDecisionButton(
    title: String,
    icon: ImageVector,
    prominent: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    destructive: Boolean = false,
) {
    if (prominent) {
        Button(onClick = onClick, enabled = enabled, modifier = modifier) {
            Icon(icon, contentDescription = null, modifier = Modifier.padding(end = 6.dp))
            Text(title, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    } else {
        OutlinedButton(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier,
            colors = if (destructive) {
                ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
            } else {
                ButtonDefaults.outlinedButtonColors()
            },
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.padding(end = 6.dp))
            Text(title, maxLines = 1)
        }
    }
}

private fun nonEmptyText(value: String?): String? =
    value?.trim()?.takeIf { it.isNotEmpty() }
