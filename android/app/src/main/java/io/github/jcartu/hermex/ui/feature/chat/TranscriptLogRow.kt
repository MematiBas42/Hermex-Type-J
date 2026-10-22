package io.github.jcartu.hermex.ui.feature.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

internal object TranscriptLogRowMetrics {
    val MinimumHeight = 32.dp
    val BodyIndent = 26.dp
    val BodyWindowHeight = 240.dp
}

/** Dense shared row ported from `TranscriptLogRowView.swift`. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun TranscriptLogRow(
    summary: String,
    detail: String?,
    isFailure: Boolean,
    isExpanded: Boolean,
    accessibilityLabel: String,
    copyText: () -> String,
    onToggle: () -> Unit,
    hapticsEnabled: Boolean,
    modifier: Modifier = Modifier,
    leading: @Composable () -> Unit,
    accessory: @Composable () -> Unit = {},
    status: @Composable () -> Unit = {},
    expandedBody: @Composable () -> Unit,
) {
    val reduceMotion = rememberReduceMotion()
    @Suppress("DEPRECATION") val clipboard = LocalClipboardManager.current
    val haptics = LocalHapticFeedback.current
    var copied by remember { mutableStateOf(false) }

    fun toggle() {
        if (hapticsEnabled) haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
        onToggle()
    }

    fun copy() {
        clipboard.setText(AnnotatedString(copyText()))
        if (hapticsEnabled) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        copied = true
    }

    LaunchedEffect(copied) {
        if (!copied) return@LaunchedEffect
        delay(1_200)
        copied = false
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = TranscriptLogRowMetrics.MinimumHeight)
                .semantics(mergeDescendants = true) {
                    role = Role.Button
                    contentDescription = accessibilityLabel
                    onClick(if (isExpanded) "Hide details" else "Show details") {
                        toggle()
                        true
                    }
                    onLongClick("Copy") {
                        copy()
                        true
                    }
                }
                .combinedClickable(onClick = ::toggle, onLongClick = ::copy)
                .padding(horizontal = 2.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(Modifier.size(width = 20.dp, height = 18.dp), contentAlignment = Alignment.Center) {
                leading()
            }
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = summary,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isFailure) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
                if (!detail.isNullOrBlank()) {
                    Spacer(Modifier.width(5.dp))
                    Text(
                        text = detail,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            if (copied) {
                Text(
                    text = "Copied",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            accessory()
            Icon(
                imageVector = if (isExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
            Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) { status() }
        }

        if (reduceMotion) {
            if (isExpanded) ExpandedLogBody(expandedBody)
        } else {
            AnimatedVisibility(
                visible = isExpanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                ExpandedLogBody(expandedBody)
            }
        }
    }
}

@Composable
private fun ExpandedLogBody(content: @Composable () -> Unit) {
    val scrollState = rememberScrollState()
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = TranscriptLogRowMetrics.BodyIndent + 12.dp, top = 2.dp, bottom = 6.dp)
            .heightIn(max = TranscriptLogRowMetrics.BodyWindowHeight)
            .verticalScroll(scrollState),
    ) {
        content()
    }
}

/** Copy icon used by every message footer. */
@Composable
internal fun TranscriptCopyButton(
    text: String,
    hapticsEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    @Suppress("DEPRECATION") val clipboard = LocalClipboardManager.current
    val haptics = LocalHapticFeedback.current
    var copied by remember(text) { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (!copied) return@LaunchedEffect
        delay(1_200)
        copied = false
    }
    androidx.compose.material3.IconButton(
        onClick = {
            clipboard.setText(AnnotatedString(text))
            if (hapticsEnabled) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            copied = true
        },
        modifier = modifier.size(32.dp),
    ) {
        Icon(
            imageVector = if (copied) Icons.Rounded.Check else Icons.Rounded.ContentCopy,
            contentDescription = if (copied) "Copied" else "Copy",
            modifier = Modifier.size(15.dp),
        )
    }
}
