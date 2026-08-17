package io.github.jcartu.hermex.ui.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.delay
import kotlin.math.max
import kotlin.math.min

/**
 * Port of `ClarificationRequestOverlay.swift`: a bottom-anchored card over a
 * dim scrim — header ("Clarification Required" + pending count + live
 * expiration countdown), the question in a tinted box, one tappable button per
 * offered choice, a free-form response field with a circular submit button,
 * and an inline error footer. Everything is disabled while a response is in
 * flight.
 */
@Composable
fun ClarificationRequestOverlay(
    prompt: ClarificationPromptState,
    isResponding: Boolean,
    errorMessage: String?,
    onSubmit: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrimInteraction = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .fillMaxSize()
            .zIndex(9f)
            .background(Color.Black.copy(alpha = 0.22f))
            .clickable(
                interactionSource = scrimInteraction,
                indication = null,
                onClick = {},
            ),
        contentAlignment = Alignment.BottomCenter,
    ) {
        ClarificationRequestCard(
            prompt = prompt,
            isResponding = isResponding,
            errorMessage = errorMessage,
            onSubmit = onSubmit,
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp),
        )
    }
}

@Composable
private fun ClarificationRequestCard(
    prompt: ClarificationPromptState,
    isResponding: Boolean,
    errorMessage: String?,
    onSubmit: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Keyed on the prompt so a new clarification starts with a fresh draft,
    // while recompositions of the same prompt keep the user's text.
    var draftResponse by rememberSaveable(prompt.id) { mutableStateOf("") }
    val trimmedDraft = draftResponse.trim()

    Surface(
        modifier = modifier.widthIn(max = 560.dp),
        shape = RoundedCornerShape(24.dp),
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
            ClarificationHeader(prompt = prompt)

            // Question box.
            Text(
                text = prompt.pending.displayQuestion,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        shape = RoundedCornerShape(12.dp),
                    )
                    .padding(12.dp),
            )

            // One button per offered choice.
            val choices = prompt.pending.displayChoices
            if (choices.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    choices.forEach { choice ->
                        OutlinedButton(
                            onClick = { onSubmit(choice) },
                            enabled = !isResponding,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp),
                        ) {
                            Text(
                                text = choice,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }

            // Free-form response field + circular submit.
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = draftResponse,
                    onValueChange = { draftResponse = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Type a response") },
                    enabled = !isResponding,
                    minLines = 1,
                    maxLines = 5,
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(),
                )
                val submitEnabled = !isResponding && trimmedDraft.isNotEmpty()
                IconButton(
                    onClick = { if (trimmedDraft.isNotEmpty()) onSubmit(trimmedDraft) },
                    enabled = submitEnabled,
                    modifier = Modifier.size(48.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                ) {
                    if (isResponding) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Filled.ArrowUpward,
                            contentDescription = "Submit clarification",
                        )
                    }
                }
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
}

@Composable
private fun ClarificationHeader(prompt: ClarificationPromptState) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(
            imageVector = Icons.Outlined.HelpOutline,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                text = "Clarification Required",
                style = MaterialTheme.typography.titleMedium,
            )
            if (prompt.pendingCount > 1) {
                Text(
                    text = "1 of ${prompt.pendingCount} pending",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        ClarificationExpirationBadge(prompt = prompt)
    }
}

/**
 * Live countdown badge (Swift `expirationView`): remaining time text plus a
 * shrinking progress capsule, ticking once per second while an expiry or
 * timeout is known.
 */
@Composable
private fun ClarificationExpirationBadge(prompt: ClarificationPromptState) {
    val expiresAt = prompt.pending.expiresAt
    val timeoutSeconds = prompt.pending.timeoutSeconds
    if (expiresAt == null && timeoutSeconds == null) return

    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(prompt.id) {
        while (true) {
            nowMillis = System.currentTimeMillis()
            delay(1_000)
        }
    }

    val remainingSeconds: Double? = expiresAt?.let { max(0.0, it - nowMillis / 1_000.0) }
    val fraction: Float = if (remainingSeconds != null && timeoutSeconds != null && timeoutSeconds > 0) {
        min(1.0, max(0.0, remainingSeconds / timeoutSeconds.toDouble())).toFloat()
    } else {
        1f
    }

    Column(
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Text(
            text = expirationText(remainingSeconds, timeoutSeconds),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LinearProgressIndicator(
            progress = { fraction },
            modifier = Modifier
                .width(68.dp)
                .height(4.dp),
        )
    }
}

private fun expirationText(remainingSeconds: Double?, timeoutSeconds: Int?): String {
    if (remainingSeconds == null) {
        return timeoutSeconds?.let { "Timeout ${durationText(it.toDouble())}" } ?: ""
    }
    if (remainingSeconds <= 0) return "Expired"
    return "${durationText(remainingSeconds)} left"
}

private fun durationText(seconds: Double): String {
    val value = max(0, kotlin.math.ceil(seconds).toInt())
    val minutes = value / 60
    val secs = value % 60
    return if (minutes > 0) "${minutes}m ${secs}s" else "${secs}s"
}

private fun nonEmptyText(value: String?): String? =
    value?.trim()?.takeIf { it.isNotEmpty() }
