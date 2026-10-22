package io.github.jcartu.hermex.ui.feature.bots

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.Collator

/**
 * Device-local Desktop-section ordering from `BotSectionOrderView` in
 * `Features/Bots/BotsInboxView.swift`. Only sections that currently head a list block appear;
 * pinned-only sections keep their stored slot, and the unfiled "Other chats" block is always last.
 * Explicit up/down controls are the Android equivalent of the Swift reorder gesture and remain
 * operable with TalkBack and switch access.
 */
@Composable
fun BotSectionOrderSheet(
    inbox: BotInbox,
    state: BotInbox.State,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sections = state.reorderableSectionNames
    val sectionByID = remember(sections) { sections.associateBy { it.id } }
    val sectionIDs = remember(sections) { sections.map { it.id } }
    var order by remember(state.connection?.id) { mutableStateOf(sectionIDs) }

    LaunchedEffect(sectionIDs) {
        order = order.filter { it in sectionIDs } + sectionIDs.filter { it !in order }
    }

    BotAdaptiveSheet(onDismiss = onDismiss, maxContentWidth = 480.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Reorder Sections",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onDismiss) { Text("Done") }
        }
        HorizontalDivider()

        if (order.isEmpty()) {
            Column(
                modifier = modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text("No sections to reorder", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(
                modifier = modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                itemsIndexed(order, key = { _, id -> id }) { index, id ->
                    val section = sectionByID[id] ?: return@itemsIndexed
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = section.name,
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(
                            enabled = index > 0,
                            onClick = {
                                val next = order.toMutableList()
                                val value = next.removeAt(index)
                                next.add(index - 1, value)
                                order = next
                                inbox.placeReorderableSections(next)
                            },
                        ) {
                            Icon(Icons.Filled.ArrowUpward, contentDescription = "Move ${section.name} up")
                        }
                        IconButton(
                            enabled = index < order.lastIndex,
                            onClick = {
                                val next = order.toMutableList()
                                val value = next.removeAt(index)
                                next.add(index + 1, value)
                                order = next
                                inbox.placeReorderableSections(next)
                            },
                        ) {
                            Icon(Icons.Filled.ArrowDownward, contentDescription = "Move ${section.name} down")
                        }
                    }
                    if (index < order.lastIndex) HorizontalDivider(modifier = Modifier.padding(start = 20.dp))
                }
            }
        }

        HorizontalDivider()
        TextButton(
            enabled = state.sectionOrder.isNotEmpty(),
            onClick = {
                inbox.resetSectionOrder()
                val collator = Collator.getInstance()
                order = sections.sortedWith { first, second ->
                    val byName = collator.compare(first.name, second.name)
                    if (byName != 0) byName else first.id.compareTo(second.id)
                }.map { it.id }
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Icon(Icons.Filled.RestartAlt, contentDescription = null)
            Text("Reset to A–Z", modifier = Modifier.padding(start = 8.dp))
        }
    }
}
