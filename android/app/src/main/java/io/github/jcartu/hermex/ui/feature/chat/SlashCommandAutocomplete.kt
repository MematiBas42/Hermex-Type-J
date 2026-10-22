package io.github.jcartu.hermex.ui.feature.chat

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.jcartu.hermex.model.SlashSkillFormatter
import io.github.jcartu.hermex.ui.feature.models.ReasoningEffortOption

internal data class ComposerAutocompleteSelection(
    val replacement: String,
    val token: ComposerToken? = null,
)

private data class SlashAutocompleteItem(
    val key: String,
    val title: String,
    val subtitle: String?,
    val description: String?,
    val completion: ComposerAutocompleteSelection,
    val isSkill: Boolean = false,
    val isSelected: Boolean = false,
    val rankName: String = title.removePrefix("/"),
)

/**
 * Caret-owned `/` and `@` autocomplete panel.
 *
 * Ports `SlashCommandAutocompleteView.swift`, `ComposerSlashTrigger.swift`, and
 * `FilePathAutocompleteView.swift`. Only the trigger ending at the live caret is
 * replaced; text before and after it is untouched by the caller.
 */
@Composable
internal fun SlashCommandAutocomplete(
    state: ChatUiState,
    actions: ChatComposerActions,
    trigger: ComposerCaretTrigger?,
    fileController: ComposerFileAutocompleteController,
    loadFileSuggestions: (suspend (String) -> List<ComposerFileSuggestion>)?,
    hapticsEnabled: Boolean,
    onSelectSlash: (ComposerAutocompleteSelection) -> Unit,
    onSelectFile: (ComposerFileSuggestion) -> Unit,
    modifier: Modifier = Modifier,
) {
    val view = LocalView.current

    LaunchedEffect(state.sessionId, trigger?.kind) {
        if (trigger?.kind != ComposerTriggerKind.FILE) fileController.reset()
    }
    LaunchedEffect(state.sessionId, trigger?.kind, trigger?.query, loadFileSuggestions) {
        if (trigger?.kind == ComposerTriggerKind.FILE && loadFileSuggestions != null) {
            fileController.search(trigger.query, loadFileSuggestions)
        }
    }

    val performSelectionHaptic = {
        if (hapticsEnabled) view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    when (trigger?.kind) {
        ComposerTriggerKind.FILE -> {
            if (loadFileSuggestions == null) return
            FileAutocompletePanel(
                query = trigger.query,
                matches = fileController.matches,
                isLoading = fileController.isLoading,
                hasAnswered = fileController.answeredQuery == trigger.query,
                onSelect = { match ->
                    performSelectionHaptic()
                    onSelectFile(match)
                },
                modifier = modifier,
            )
        }

        ComposerTriggerKind.SLASH -> SlashAutocompletePanel(
            trigger = trigger,
            state = state,
            actions = actions,
            onSelect = { selection ->
                performSelectionHaptic()
                onSelectSlash(selection)
            },
            modifier = modifier,
        )

        null -> Unit
    }
}

@Composable
private fun SlashAutocompletePanel(
    trigger: ComposerCaretTrigger,
    state: ChatUiState,
    actions: ChatComposerActions,
    onSelect: (ComposerAutocompleteSelection) -> Unit,
    modifier: Modifier,
) {
    val parsed = SlashCommandExecutor.parse(trigger.text) ?: return
    val command = parsed.command
    val hasSeparator = hasWhitespaceAfterCommand(trigger.text, parsed.name)
    val isSubArgMode = trigger.startsDraft && command != null &&
        command.subArgs != SlashCommandSubArgs.NONE && hasSeparator

    if (isSubArgMode && command?.subArgs == SlashCommandSubArgs.WORKSPACES) {
        LaunchedEffect(parsed.args) { actions.loadWorkspaceSuggestions(parsed.args.trim()) }
    }

    val items = if (isSubArgMode && command != null) {
        subArgumentItems(command, parsed.args, state)
    } else {
        topLevelItems(parsed.name, state, skillsOnly = !trigger.startsDraft)
    }

    // Mid-sentence slash completion is skills-only. Once none match, disappear
    // instead of covering the keyboard with an empty command panel.
    if (!trigger.startsDraft && items.isEmpty()) return
    if (!isSubArgMode && hasSeparator) return

    AutocompleteSurface(modifier = modifier) {
        if (items.isEmpty()) {
            EmptyAutocompleteMessage(if (isSubArgMode) parsed.args.trim() else parsed.name)
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 280.dp),
            ) {
                itemsIndexed(items, key = { _, item -> item.key }) { index, item ->
                    SlashAutocompleteRow(item = item, onClick = { onSelect(item.completion) })
                    if (index != items.lastIndex) {
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun FileAutocompletePanel(
    query: String,
    matches: List<ComposerFileSuggestion>,
    isLoading: Boolean,
    hasAnswered: Boolean,
    onSelect: (ComposerFileSuggestion) -> Unit,
    modifier: Modifier,
) {
    AutocompleteSurface(modifier = modifier) {
        when {
            isLoading -> Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 20.dp),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
            }

            matches.isEmpty() && hasAnswered -> EmptyAutocompleteMessage(query)
            matches.isEmpty() -> Unit
            else -> LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 280.dp),
            ) {
                itemsIndexed(matches, key = { _, item -> item.path }) { index, item ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(item) }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(
                            imageVector = if (item.isDirectory) Icons.Filled.Folder else Icons.Filled.Description,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = item.name,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (item.parentPath.isNotEmpty()) {
                                Text(
                                    text = item.parentPath,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        if (item.isDirectory) {
                            Icon(
                                Icons.Filled.ChevronRight,
                                contentDescription = "Open folder",
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                    if (index != matches.lastIndex) {
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun AutocompleteSurface(
    modifier: Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 4.dp,
        shadowElevation = 6.dp,
        content = content,
    )
}

@Composable
private fun EmptyAutocompleteMessage(query: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (query.isBlank()) "No matches" else "No matches for \"$query\"",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SlashAutocompleteRow(item: SlashAutocompleteItem, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            imageVector = if (item.isSkill) Icons.Filled.Bolt else Icons.Filled.Terminal,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = if (item.isSkill) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                item.subtitle?.let { subtitle ->
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            item.description?.let { description ->
                Text(
                    text = description,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (item.isSelected) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = "Selected",
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

private fun topLevelItems(
    nameQuery: String,
    state: ChatUiState,
    skillsOnly: Boolean,
): List<SlashAutocompleteItem> {
    val candidates = ArrayList<SlashAutocompleteItem>()
    val seen = HashSet<String>()

    if (!skillsOnly) {
        for (command in SlashCommandCatalog.allCommands) {
            val key = command.name.lowercase()
            if (!seen.add(key)) continue
            candidates += SlashAutocompleteItem(
                key = "command:$key",
                title = "/${command.name}",
                subtitle = command.argHint,
                description = command.description,
                completion = ComposerAutocompleteSelection("/${command.name} "),
                rankName = command.name,
            )
        }
    }

    for (skill in state.skillSuggestions) {
        val slug = skill.slashName
        if (slug.isEmpty() || SlashCommandCatalog.command(named = slug) != null) continue
        val key = slug.lowercase()
        if (!seen.add(key)) continue
        candidates += SlashAutocompleteItem(
            key = "skill:$key",
            title = "/$slug",
            subtitle = skill.category,
            description = skill.description ?: "Skill",
            completion = ComposerAutocompleteSelection(
                replacement = "/$slug ",
                token = ComposerToken.SkillChip(skill.name),
            ),
            isSkill = true,
            rankName = slug,
        )
    }

    if (!skillsOnly) {
        for (serverCommand in state.serverCommands) {
            if (serverCommand.cliOnly == true || serverCommand.gatewayOnly == true) continue
            val name = serverCommand.name?.trim().orEmpty()
            if (name.isEmpty()) continue
            val key = name.lowercase()
            if (!seen.add(key)) continue
            candidates += SlashAutocompleteItem(
                key = "server:$key",
                title = "/$name",
                subtitle = serverCommand.argsHint?.trim()?.takeIf(String::isNotEmpty),
                description = serverCommand.description?.trim()?.takeIf(String::isNotEmpty) ?: "Agent command",
                completion = ComposerAutocompleteSelection("/$name "),
                rankName = name,
            )
        }
    }

    return SlashCommandRanker.rank(candidates, nameQuery) { item ->
        SlashRankableFields(
            name = item.rankName,
            label = item.title,
            shortDescription = item.subtitle,
            description = item.description,
        )
    }
}

private fun subArgumentItems(
    command: SlashCommand,
    argQuery: String,
    state: ChatUiState,
): List<SlashAutocompleteItem> {
    val candidates = when (command.subArgs) {
        SlashCommandSubArgs.MODELS -> {
            val seen = HashSet<String>()
            state.modelCatalogGroups
                .flatMap { it.slashAutocompleteModels }
                .filter { seen.add(it.id) }
                .map { option ->
                    SlashAutocompleteItem(
                        key = "model:${option.id}",
                        title = option.displayName,
                        subtitle = option.providerID,
                        description = option.id.takeIf { it != option.displayName },
                        completion = ComposerAutocompleteSelection("/${command.name} ${option.id} "),
                        isSelected = option.id == state.selectedModelId &&
                            (state.selectedModelProviderId == null || option.providerID == state.selectedModelProviderId),
                        rankName = option.id,
                    )
                }
        }

        SlashCommandSubArgs.REASONING_LEVELS -> SlashCommandCatalog.reasoningLevels.map { effort ->
            SlashAutocompleteItem(
                key = "reasoning:$effort",
                title = ReasoningEffortOption.titleFor(effort),
                subtitle = effort,
                description = null,
                completion = ComposerAutocompleteSelection("/${command.name} $effort "),
                isSelected = effort == state.reasoningEffort,
                rankName = effort,
            )
        }

        SlashCommandSubArgs.WORKSPACES -> {
            val seen = HashSet<String>()
            (state.workspaceRoots.mapNotNull { it.path } + state.workspaceSuggestions)
                .filter { it.isNotBlank() && seen.add(it) }
                .map { path ->
                    SlashAutocompleteItem(
                        key = "workspace:$path",
                        title = path,
                        subtitle = null,
                        description = null,
                        completion = ComposerAutocompleteSelection("/${command.name} $path "),
                        isSelected = path == state.selectedWorkspacePath,
                        rankName = path,
                    )
                }
        }

        SlashCommandSubArgs.SKILLS -> state.skillSuggestions.mapNotNull { skill ->
            val slug = SlashSkillFormatter.slug(skill.name)
            if (slug.isEmpty()) return@mapNotNull null
            SlashAutocompleteItem(
                key = "skill-argument:$slug",
                title = skill.name,
                subtitle = skill.category,
                description = skill.description ?: "Skill",
                completion = ComposerAutocompleteSelection("/${command.name} $slug "),
                isSkill = true,
                rankName = slug,
            )
        }

        SlashCommandSubArgs.GOAL_ACTIONS -> SlashCommandCatalog.goalActions.map { action ->
            SlashAutocompleteItem(
                key = "goal:$action",
                title = "/${command.name} $action",
                subtitle = null,
                description = null,
                completion = ComposerAutocompleteSelection("/${command.name} $action"),
                rankName = action,
            )
        }

        SlashCommandSubArgs.PERSONALITIES,
        SlashCommandSubArgs.NONE,
        -> emptyList()
    }

    return SlashCommandRanker.rank(candidates, argQuery) { item ->
        SlashRankableFields(
            name = item.rankName,
            label = item.title,
            shortDescription = item.subtitle,
            description = item.description,
        )
    }
}

private fun hasWhitespaceAfterCommand(query: String, commandName: String): Boolean {
    if (commandName.isBlank()) return false
    val prefix = "/$commandName"
    if (!query.startsWith(prefix, ignoreCase = true)) return false
    return query.drop(prefix.length).firstOrNull()?.isWhitespace() == true
}
