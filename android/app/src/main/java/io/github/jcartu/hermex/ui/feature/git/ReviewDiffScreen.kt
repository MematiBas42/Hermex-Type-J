package io.github.jcartu.hermex.ui.feature.git

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.jcartu.hermex.model.GitFile
import io.github.jcartu.hermex.ui.feature.workspace.FileTypeIcon
import io.github.jcartu.hermex.ui.theme.CodeTextStyle

private sealed interface ReviewDisplayRow {
    val key: String

    data class FileHeader(val model: ReviewDiffFileModel) : ReviewDisplayRow {
        override val key = "${model.file.id}:header"
    }

    data class Notice(val fileId: String, val text: String) : ReviewDisplayRow {
        override val key = "$fileId:notice"
    }

    data class Hunk(val fileId: String, val index: Int, val value: UnifiedDiffHunk) : ReviewDisplayRow {
        override val key = "$fileId:hunk:$index"
    }

    data class Line(
        val fileId: String,
        val hunkIndex: Int,
        val lineIndex: Int,
        val value: UnifiedDiffLine,
    ) : ReviewDisplayRow {
        override val key = "$fileId:line:$hunkIndex:$lineIndex"
    }
}

/**
 * Compose port of `ReviewDiffSurface`: every changed file in one lazy scroll,
 * sticky/collapsible headers, viewed state, hunk labels, line numbers, and the
 * same green/red edit colours used by transcript diffs.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ReviewDiffScreen(
    files: List<ReviewDiffFileModel>,
    initialPath: String? = null,
    modifier: Modifier = Modifier,
    showFileIcons: Boolean = true,
) {
    var collapsed by rememberSaveable(stateSaver = stringSetSaver()) {
        mutableStateOf<Set<String>>(emptySet())
    }
    var viewed by rememberSaveable(stateSaver = stringSetSaver()) {
        mutableStateOf<Set<String>>(emptySet())
    }
    val rows = remember(files, collapsed) { flattenReview(files, collapsed) }
    val listState = rememberLazyListState()
    val horizontal = rememberScrollState()
    LaunchedEffect(initialPath, rows) {
        val target = rows.indexOfFirst {
            it is ReviewDisplayRow.FileHeader && it.model.file.displayPath == initialPath
        }
        if (target >= 0) listState.scrollToItem(target)
    }

    if (files.isEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No Changes", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    LazyColumn(state = listState, modifier = modifier.fillMaxSize()) {
        itemsIndexed(rows, key = { _, row -> row.key }) { _, row ->
            when (row) {
                is ReviewDisplayRow.FileHeader -> stickyFileHeader(
                    model = row.model,
                    collapsed = row.model.file.id in collapsed,
                    viewed = row.model.file.id in viewed,
                    showFileIcon = showFileIcons,
                    onToggle = {
                        collapsed = if (row.model.file.id in collapsed) collapsed - row.model.file.id
                        else collapsed + row.model.file.id
                    },
                    onViewed = {
                        val id = row.model.file.id
                        if (id in viewed) viewed -= id else {
                            viewed += id
                            collapsed += id
                        }
                    },
                )
                is ReviewDisplayRow.Notice -> Text(
                    row.text,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                is ReviewDisplayRow.Hunk -> Text(
                    row.value.displayLabel,
                    modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.secondaryContainer)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    style = CodeTextStyle,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                is ReviewDisplayRow.Line -> DiffLineRow(row.value, horizontal)
            }
        }
    }
}

@Composable
private fun stickyFileHeader(
    model: ReviewDiffFileModel,
    collapsed: Boolean,
    viewed: Boolean,
    showFileIcon: Boolean,
    onToggle: () -> Unit,
    onViewed: () -> Unit,
) {
    Surface(tonalElevation = 3.dp) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(horizontal = 10.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(if (collapsed) Icons.Filled.ChevronRight else Icons.Filled.ExpandMore, if (collapsed) "Expand file" else "Collapse file")
            if (showFileIcon) FileTypeIcon(model.file.displayPath, Modifier.width(22.dp))
            Text(
                displayReviewPath(model.file),
                modifier = Modifier.weight(1f),
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
            )
            Text("+${model.additions}", color = ADDITION_TEXT, style = MaterialTheme.typography.labelSmall)
            Text("−${model.deletions}", color = DELETION_TEXT, style = MaterialTheme.typography.labelSmall)
            Icon(
                if (viewed) Icons.Filled.CheckCircle else Icons.Outlined.Circle,
                contentDescription = if (viewed) "Mark not viewed" else "Mark viewed",
                tint = if (viewed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clickable(onClick = onViewed).padding(4.dp),
            )
        }
    }
}

@Composable
private fun DiffLineRow(line: UnifiedDiffLine, horizontal: androidx.compose.foundation.ScrollState) {
    val background = when (line.kind) {
        ReviewLineKind.ADDITION -> ADDITION_BACKGROUND
        ReviewLineKind.DELETION -> DELETION_BACKGROUND
        ReviewLineKind.CONTEXT -> Color.Transparent
    }
    val marker = when (line.kind) {
        ReviewLineKind.ADDITION -> "+"
        ReviewLineKind.DELETION -> "−"
        ReviewLineKind.CONTEXT -> " "
    }
    Row(
        modifier = Modifier.fillMaxWidth().background(background).padding(vertical = 1.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            line.oldLineNumber?.toString().orEmpty(),
            modifier = Modifier.width(42.dp).padding(end = 6.dp),
            style = CodeTextStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            line.newLineNumber?.toString().orEmpty(),
            modifier = Modifier.width(42.dp).padding(end = 6.dp),
            style = CodeTextStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(marker, modifier = Modifier.width(18.dp), style = CodeTextStyle, color = when (line.kind) {
            ReviewLineKind.ADDITION -> ADDITION_TEXT
            ReviewLineKind.DELETION -> DELETION_TEXT
            ReviewLineKind.CONTEXT -> MaterialTheme.colorScheme.onSurfaceVariant
        })
        Text(
            line.content.ifEmpty { " " },
            modifier = Modifier.horizontalScroll(horizontal).padding(end = 24.dp),
            style = CodeTextStyle,
            softWrap = false,
        )
    }
}

private fun flattenReview(
    files: List<ReviewDiffFileModel>,
    collapsed: Set<String>,
): List<ReviewDisplayRow> = buildList {
    for (model in files) {
        add(ReviewDisplayRow.FileHeader(model))
        if (model.file.id in collapsed) continue
        model.notice?.let { add(ReviewDisplayRow.Notice(model.file.id, it)) }
        model.hunks.forEachIndexed { hunkIndex, hunk ->
            add(ReviewDisplayRow.Hunk(model.file.id, hunkIndex, hunk))
            hunk.lines.forEachIndexed { lineIndex, line ->
                add(ReviewDisplayRow.Line(model.file.id, hunkIndex, lineIndex, line))
            }
        }
    }
}

private fun displayReviewPath(file: GitFile): String {
    val old = file.oldPath?.trim().orEmpty()
    return if (old.isNotEmpty() && old != file.displayPath) "$old → ${file.displayPath}" else file.displayPath
}

private fun stringSetSaver() = Saver<Set<String>, ArrayList<String>>(
    save = { ArrayList(it) },
    restore = { it.toSet() },
)

internal val ADDITION_BACKGROUND = Color(0x2634C759)
internal val DELETION_BACKGROUND = Color(0x26FF453A)
internal val ADDITION_TEXT = Color(0xFF34C759)
internal val DELETION_TEXT = Color(0xFFFF453A)
