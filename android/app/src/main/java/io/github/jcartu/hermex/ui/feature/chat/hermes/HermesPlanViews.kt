package io.github.jcartu.hermex.ui.feature.chat.hermes

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Timelapse
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import io.github.jcartu.hermex.model.hermes.HermesPlan
import io.github.jcartu.hermex.ui.feature.chat.rememberReduceMotion

/**
 * Port of `HermesPlanRowView` in `Features/Chat/HermesPlanViews.swift`: a settled plan row
 * with local disclosure state and an explicit copy callback.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HermesPlanRowView(
    plan: HermesPlan,
    onCopy: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable(plan.revision) { mutableStateOf(false) }
    val reduceMotion = rememberReduceMotion()
    val accessibility = "Plan, ${plan.progress}"

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .semantics(mergeDescendants = true) {
                    role = Role.Button
                    contentDescription = accessibility
                    onClick(if (expanded) "Hide details" else "Show details") {
                        expanded = !expanded
                        true
                    }
                    onLongClick("Copy") {
                        onCopy(plan.copyText)
                        true
                    }
                }
                .combinedClickable(
                    onClick = { expanded = !expanded },
                    onLongClick = { onCopy(plan.copyText) },
                )
                .padding(horizontal = 2.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.Checklist,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = "Plan",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = plan.progress,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
        }
        if (reduceMotion) {
            if (expanded) HermesPlanRowBody(plan)
        } else {
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                HermesPlanRowBody(plan)
            }
        }
    }
}

@Composable
private fun HermesPlanRowBody(plan: HermesPlan) {
    HermesPlanItemList(
        plan = plan,
        modifier = Modifier
            .padding(start = 38.dp, top = 2.dp, bottom = 8.dp)
            .heightIn(max = 240.dp)
            .verticalScroll(rememberScrollState()),
    )
}

/** Port of `HermesPlanItemList` in `Features/Chat/HermesPlanViews.swift`. */
@Composable
fun HermesPlanItemList(
    plan: HermesPlan,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        plan.items.forEach { item ->
            val stateLabel = planItemStateLabel(item)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics(mergeDescendants = true) {
                        contentDescription = "$stateLabel: ${item.content}"
                    },
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Icon(
                    imageVector = planItemIcon(item),
                    contentDescription = null,
                    tint = if (item.status == "in_progress") {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.padding(top = 1.dp).size(16.dp),
                )
                Text(
                    text = item.content,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (item.isDone) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    textDecoration = if (item.isDone) TextDecoration.LineThrough else null,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * Port of `HermesPlanStrip` in `Features/Chat/HermesPlanViews.swift`: a running plan pinned
 * above the composer. Only the collapsed line contributes to transcript inset accounting.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HermesPlanStrip(
    plan: HermesPlan,
    onCollapsedHeightChange: (Dp) -> Unit,
    onCopy: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable(plan.revision) { mutableStateOf(false) }
    val reduceMotion = rememberReduceMotion()
    val density = LocalDensity.current
    val count = "${plan.completedCount} of ${plan.items.size}"
    val description = listOfNotNull(count, plan.current?.content).joinToString(", ")

    Box(modifier = modifier.widthIn(max = 560.dp).fillMaxWidth()) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
            tonalElevation = 2.dp,
            shadowElevation = 3.dp,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .onGloballyPositioned { coordinates ->
                        onCollapsedHeightChange(with(density) { coordinates.size.height.toDp() })
                    }
                    .semantics(mergeDescendants = true) {
                        role = Role.Button
                        contentDescription = "Plan, $description"
                        onClick(if (expanded) "Hide details" else "Show details") {
                            expanded = !expanded
                            true
                        }
                        onLongClick("Copy") {
                            onCopy(plan.copyText)
                            true
                        }
                    }
                    .combinedClickable(
                        onClick = { expanded = !expanded },
                        onLongClick = { onCopy(plan.copyText) },
                    )
                    .padding(horizontal = 11.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Checklist,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(17.dp),
                )
                Text(
                    text = count,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
                Text(
                    text = plan.current?.content.orEmpty(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = if (expanded) Icons.Rounded.ExpandMore else Icons.Rounded.ExpandLess,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            offset = DpOffset(0.dp, 0.dp),
            modifier = Modifier.widthIn(min = 280.dp, max = 560.dp).heightIn(max = 240.dp),
        ) {
            HermesPlanItemList(
                plan = plan,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}



private fun planItemIcon(item: HermesPlan.Item) = when (item.status) {
    "completed" -> Icons.Outlined.CheckCircle
    "cancelled" -> Icons.Outlined.Cancel
    "in_progress" -> Icons.Outlined.Timelapse
    else -> Icons.Outlined.RadioButtonUnchecked
}

private fun planItemStateLabel(item: HermesPlan.Item): String = when (item.status) {
    "completed" -> "Done"
    "cancelled" -> "Cancelled"
    "in_progress" -> "In progress"
    else -> "Pending"
}
