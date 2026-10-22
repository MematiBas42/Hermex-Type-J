package io.github.jcartu.hermex.ui.feature.hermes.home

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** The two sides of a Hermes server's home, Sessions on first open (Features/Hermes/HermesHome.swift). */
enum class HermesHomeTab { BOTS, SESSIONS }

/**
 * Port of HermesHomeChrome/HermesHomeSwitch (#1108/#1251). Three separate pills float over the
 * first pane's list, not a solid bottom band. Each side supplies its own filter/new-chat menus.
 */
@Composable
fun HermesHomeBar(
    tab: HermesHomeTab,
    onTab: (HermesHomeTab) -> Unit,
    filter: @Composable () -> Unit,
    newChat: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier.fillMaxWidth().padding(horizontal = 12.dp).semantics { contentDescription = "Hermes home switch" },
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
        Surface(shape = CircleShape, tonalElevation = 8.dp, shadowElevation = 4.dp,
            color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = .96f)) { filter() }
        SingleChoiceSegmentedButtonRow(modifier = Modifier.weight(1f, fill = false)) {
            SegmentedButton(selected = tab == HermesHomeTab.BOTS, onClick = { onTab(HermesHomeTab.BOTS) },
                shape = SegmentedButtonDefaults.itemShape(0, 2), icon = {}) { Text("Bots", maxLines = 1) }
            SegmentedButton(selected = tab == HermesHomeTab.SESSIONS, onClick = { onTab(HermesHomeTab.SESSIONS) },
                shape = SegmentedButtonDefaults.itemShape(1, 2), icon = {}) { Text("Sessions", maxLines = 1) }
        }
        Surface(shape = CircleShape, tonalElevation = 8.dp, shadowElevation = 4.dp,
            color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = .96f)) { newChat() }
    }
}
