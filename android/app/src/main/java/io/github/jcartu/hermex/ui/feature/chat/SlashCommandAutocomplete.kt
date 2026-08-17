package io.github.jcartu.hermex.ui.feature.chat

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
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.jcartu.hermex.model.SlashSkillFormatter
import io.github.jcartu.hermex.ui.feature.models.ReasoningEffortOption

private data class SlashAutocompleteItem(
    val key: String,
    val title: String,
    val subtitle: String?,
    val description: String?,
    val completion: String,
    val isSkill: Boolean = false,
    val isSelected: Boolean = false,
)

/**
 * Built-in, server-command, skill, and command-subargument autocomplete for a
 * composer draft beginning with `/`.
 */
@Composable
internal fun SlashCommandAutocomplete(
    state: ChatUiState,
    actions: ChatComposerActions,
    modifier: Modifier = Modifier,
) {
    val query = state.draft.trimStart()
    if (!query.startsWith('/')) return

    val parsed = SlashCommandExecutor.parse(query) ?: return
    val command = parsed.command
    val hasSeparator = hasWhitespaceAfterCommand(query, parsed.name)
    val isSubArgMode = command != null &&
        command.subArgs != SlashCommandSubArgs.NONE &&
        hasSeparator

    if (!isSubArgMode && hasSeparator && shouldHideAfterCommand(parsed.name, state)) return

    if (isSubArgMode && command?.subArgs == SlashCommandSubArgs.WORKSPACES) {
        LaunchedEffect(parsed.args) {
            actions.loadWorkspaceSuggestions(parsed.args.trim())
        }
    }

    val items = if (isSubArgMode && command != null) {
        subArgumentItems(command, parsed.args, state)
    } else {
        topLevelItems(parsed.name, state)
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 4.dp,
        shadowElevation = 6.dp,
    ) {
        if (items.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 20.dp),
                contentAlignment = Alignment.Center,
            ) {
                val unmatched = if (isSubArgMode) parsed.args.trim() else parsed.name
                Text(
                    text = "No matches for \"$unmatched\"",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 280.dp),
            ) {
                itemsIndexed(items, key = { _, item -> item.key }) { index, item ->
                    SlashAutocompleteRow(
                        item = item,
                        onClick = { actions.updateDraft(item.completion) },
                    )
                    if (index != items.lastIndex) {
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun SlashAutocompleteRow(
    item: SlashAutocompleteItem,
    onClick: () -> Unit,
) {
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

private fun topLevelItems(nameQuery: String, state: ChatUiState): List<SlashAutocompleteItem> {
    val normalizedQuery = nameQuery.trim().lowercase()
    val seen = HashSet<String>()
    val result = ArrayList<SlashAutocompleteItem>()

    for (command in SlashCommandCatalog.matching(normalizedQuery)) {
        val key = command.name.lowercase()
        if (!seen.add(key)) continue
        result += SlashAutocompleteItem(
            key = "command:$key",
            title = "/${command.name}",
            subtitle = command.argHint,
            description = command.description,
            completion = "/${command.name} ",
        )
    }

    for (skill in state.skillSuggestions) {
        val slug = SlashSkillFormatter.slug(skill.name)
        if (slug.isEmpty()) continue
        val commandName = "skill-$slug"
        val key = commandName.lowercase()
        if (normalizedQuery.isNotEmpty() && !key.startsWith(normalizedQuery)) continue
        if (!seen.add(key)) continue
        result += SlashAutocompleteItem(
            key = "skill:$key",
            title = "/$commandName",
            subtitle = skill.category,
            description = skill.description ?: "Skill",
            completion = "/$commandName ",
            isSkill = true,
        )
    }

    for (serverCommand in state.serverCommands) {
        if (serverCommand.cliOnly == true || serverCommand.gatewayOnly == true) continue
        val name = serverCommand.name?.trim().orEmpty()
        if (name.isEmpty()) continue
        val key = name.lowercase()
        if (normalizedQuery.isNotEmpty() && !key.startsWith(normalizedQuery)) continue
        if (!seen.add(key)) continue
        result += SlashAutocompleteItem(
            key = "server:$key",
            title = "/$name",
            subtitle = serverCommand.argsHint?.trim()?.takeIf(String::isNotEmpty),
            description = serverCommand.description?.trim()?.takeIf(String::isNotEmpty) ?: "Agent command",
            completion = "/$name ",
        )
    }

    return result
}

private fun subArgumentItems(
    command: SlashCommand,
    argQuery: String,
    state: ChatUiState,
): List<SlashAutocompleteItem> {
    val normalizedQuery = argQuery.trim().lowercase()
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
                        completion = "/${command.name} ${option.id} ",
                        isSelected = option.id == state.selectedModelId &&
                            (state.selectedModelProviderId == null || option.providerID == state.selectedModelProviderId),
                    )
                }
        }

        SlashCommandSubArgs.REASONING_LEVELS -> SlashCommandCatalog.reasoningLevels.map { effort ->
            SlashAutocompleteItem(
                key = "reasoning:$effort",
                title = ReasoningEffortOption.titleFor(effort),
                subtitle = effort,
                description = null,
                completion = "/${command.name} $effort ",
                isSelected = effort == state.reasoningEffort,
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
                        completion = "/${command.name} $path ",
                        isSelected = path == state.selectedWorkspacePath,
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
                completion = "/${command.name} $slug ",
                isSkill = true,
            )
        }

        SlashCommandSubArgs.GOAL_ACTIONS -> SlashCommandCatalog.goalActions.map { action ->
            SlashAutocompleteItem(
                key = "goal:$action",
                title = "/${command.name} $action",
                subtitle = null,
                description = null,
                completion = "/${command.name} $action",
            )
        }

        SlashCommandSubArgs.PERSONALITIES,
        SlashCommandSubArgs.NONE,
        -> emptyList()
    }

    return candidates.filter { item ->
        if (normalizedQuery.isEmpty()) {
            true
        } else {
            val completionValue = item.completion
                .substringAfter("/${command.name} ", missingDelimiterValue = item.title)
                .trim()
                .lowercase()
            completionValue.startsWith(normalizedQuery) || item.title.lowercase().startsWith(normalizedQuery)
        }
    }
}

private fun hasWhitespaceAfterCommand(query: String, commandName: String): Boolean {
    if (commandName.isBlank()) return false
    val prefix = "/$commandName"
    if (!query.startsWith(prefix, ignoreCase = true)) return false
    return query.drop(prefix.length).firstOrNull()?.isWhitespace() == true
}

private fun shouldHideAfterCommand(commandName: String, state: ChatUiState): Boolean {
    val normalized = commandName.trim().lowercase()
    val builtin = SlashCommandCatalog.command(named = normalized)
    if (builtin != null) return builtin.subArgs == SlashCommandSubArgs.NONE

    val isServerCommand = state.serverCommands.any { command ->
        command.cliOnly != true &&
            command.gatewayOnly != true &&
            command.name?.trim()?.equals(normalized, ignoreCase = true) == true
    }
    if (isServerCommand) return true

    return state.skillSuggestions.any { skill ->
        "skill-${SlashSkillFormatter.slug(skill.name)}".equals(normalized, ignoreCase = true)
    }
}
