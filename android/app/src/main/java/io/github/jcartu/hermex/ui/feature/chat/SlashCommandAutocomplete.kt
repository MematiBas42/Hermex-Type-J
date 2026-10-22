package io.github.jcartu.hermex.ui.feature.chat

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.jcartu.hermex.model.SlashSkillFormatter
import io.github.jcartu.hermex.ui.feature.models.ReasoningEffortOption
import io.github.jcartu.hermex.ui.feature.chat.hermes.HermesSlashCompletion

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
    mentionController: ComposerMentionAutocompleteController,
    loadFileSuggestions: (suspend (String) -> List<ComposerFileSuggestion>)?,
    loadMentionSuggestions: (suspend (String) -> List<ComposerMentionSuggestion>)?,
    slashScope: SlashCommandScope,
    hostSlashCompletion: HermesSlashCompletion?,
    reasoningLevels: List<String>?,
    hapticsEnabled: Boolean,
    onSelectSlash: (ComposerAutocompleteSelection) -> Unit,
    onSelectFile: (ComposerFileSuggestion) -> Unit,
    onSelectMention: (ComposerMentionSuggestion) -> Unit,
    modifier: Modifier = Modifier,
) {
    val view = LocalView.current

    LaunchedEffect(state.sessionId, trigger?.kind) {
        if (trigger?.kind != ComposerTriggerKind.FILE) {
            fileController.reset()
            mentionController.reset()
        }
    }
    LaunchedEffect(state.sessionId, trigger?.kind, trigger?.query, loadFileSuggestions) {
        if (trigger?.kind == ComposerTriggerKind.FILE && loadFileSuggestions != null) {
            fileController.search(trigger.query, loadFileSuggestions)
        }
    }
    LaunchedEffect(state.sessionId, trigger?.kind, trigger?.query, loadMentionSuggestions) {
        if (trigger?.kind == ComposerTriggerKind.FILE && loadMentionSuggestions != null) {
            if ('/' in trigger.query) mentionController.reset()
            else mentionController.search(trigger.query, loadMentionSuggestions)
        }
    }

    val performSelectionHaptic = {
        if (hapticsEnabled) view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    when (trigger?.kind) {
        ComposerTriggerKind.FILE -> {
            if (loadFileSuggestions == null && loadMentionSuggestions == null) return
            AtAutocompletePanel(
                query = trigger.query,
                mentions = if (loadMentionSuggestions == null || '/' in trigger.query) {
                    emptyList()
                } else {
                    mentionController.matches.take(8)
                },
                mentionLoading = loadMentionSuggestions != null && mentionController.isLoading,
                mentionAnswered = '/' in trigger.query ||
                    loadMentionSuggestions == null ||
                    mentionController.answeredQuery == trigger.query,
                files = if (loadFileSuggestions == null) emptyList() else fileController.matches,
                fileLoading = loadFileSuggestions != null && fileController.isLoading,
                fileAnswered = loadFileSuggestions == null || fileController.answeredQuery == trigger.query,
                showsMentions = loadMentionSuggestions != null,
                showsFiles = loadFileSuggestions != null,
                onSelectMention = { match ->
                    performSelectionHaptic()
                    onSelectMention(match)
                },
                onSelectFile = { match ->
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
            scope = slashScope,
            hostCompletion = hostSlashCompletion,
            reasoningLevels = reasoningLevels,
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
    scope: SlashCommandScope,
    hostCompletion: HermesSlashCompletion?,
    reasoningLevels: List<String>?,
    onSelect: (ComposerAutocompleteSelection) -> Unit,
    modifier: Modifier,
) {
    if (scope.isHermes && !trigger.startsDraft) return
    val parsed = ParsedSlashQuery(query = trigger.text, scope = scope)
    val command = parsed.command
    val isSubArgMode = trigger.startsDraft && parsed.isSubArgMode && command != null
    val isHostArgumentMode = trigger.startsDraft && parsed.isHostArgumentMode

    if (isSubArgMode && command?.subArgs == SlashCommandSubArgs.WORKSPACES) {
        LaunchedEffect(parsed.argQuery) { actions.loadWorkspaceSuggestions(parsed.argQuery) }
    }

    val items = when {
        isHostArgumentMode -> {
            val completion = hostCompletion?.takeIf { it.applies(trigger.text) } ?: return
            completion.items.mapIndexed { index, item ->
                SlashAutocompleteItem(
                    key = "host:$index:${item.text}",
                    title = item.display,
                    subtitle = null,
                    description = item.meta,
                    completion = ComposerAutocompleteSelection(completion.applying(item)),
                    rankName = item.text,
                )
            }
        }
        isSubArgMode && command != null -> {
            subArgumentItems(command, parsed.argQuery, state, reasoningLevels)
        }
        trigger.text.drop(1).any(Char::isWhitespace) -> return
        else -> {
            topLevelItems(
                nameQuery = parsed.commandName,
                state = state,
                skillsOnly = !trigger.startsDraft,
                scope = scope,
            )
        }
    }

    // A Hermes host-argument panel exists only while the host has applicable
    // rows. Mid-sentence webui completion remains skills-only.
    if (isHostArgumentMode && items.isEmpty()) return
    if (!trigger.startsDraft && items.isEmpty()) return

    AutocompleteSurface(modifier = modifier) {
        if (items.isEmpty()) {
            EmptyAutocompleteMessage(if (isSubArgMode) parsed.argQuery else parsed.commandName)
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
private fun AtAutocompletePanel(
    query: String,
    mentions: List<ComposerMentionSuggestion>,
    mentionLoading: Boolean,
    mentionAnswered: Boolean,
    files: List<ComposerFileSuggestion>,
    fileLoading: Boolean,
    fileAnswered: Boolean,
    showsMentions: Boolean,
    showsFiles: Boolean,
    onSelectMention: (ComposerMentionSuggestion) -> Unit,
    onSelectFile: (ComposerFileSuggestion) -> Unit,
    modifier: Modifier,
) {
    AutocompleteSurface(modifier = modifier) {
        val isLoading = mentionLoading || fileLoading
        val hasAnswered = (!showsMentions || mentionAnswered) && (!showsFiles || fileAnswered)
        when {
            mentions.isEmpty() && files.isEmpty() && isLoading -> Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 20.dp),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
            }

            mentions.isEmpty() && files.isEmpty() && hasAnswered -> EmptyAutocompleteMessage(query)
            mentions.isEmpty() && files.isEmpty() -> Unit
            else -> LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 280.dp),
            ) {
                if (mentions.isNotEmpty()) {
                    item(key = "mentions-header") { AutocompleteGroupHeader("Bots") }
                    itemsIndexed(mentions, key = { _, item -> "mention:${item.profile}:${item.tag}" }) { index, item ->
                        MentionAutocompleteRow(item = item, onClick = { onSelectMention(item) })
                        if (index != mentions.lastIndex || files.isNotEmpty()) {
                            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                        }
                    }
                }
                if (files.isNotEmpty()) {
                    item(key = "files-header") { AutocompleteGroupHeader("Files") }
                    itemsIndexed(files, key = { _, item -> "file:${item.path}" }) { index, item ->
                        FileAutocompleteRow(item = item, onClick = { onSelectFile(item) })
                        if (index != files.lastIndex) {
                            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AutocompleteGroupHeader(title: String) {
    Text(
        text = title,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun MentionAutocompleteRow(
    item: ComposerMentionSuggestion,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        val avatar = item.avatar
        if (avatar != null) {
            Image(
                bitmap = avatar,
                contentDescription = null,
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape),
            )
        } else {
            Icon(
                imageVector = Icons.Filled.AccountCircle,
                contentDescription = null,
                modifier = Modifier.size(28.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.displayName,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "@${item.tag}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun FileAutocompleteRow(
    item: ComposerFileSuggestion,
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
    scope: SlashCommandScope,
): List<SlashAutocompleteItem> {
    val builtinNames = scope.builtins.mapTo(HashSet()) { it.name.lowercase() }
    val skillNames = state.skillSuggestions.mapTo(HashSet()) { it.slashName.lowercase() }
    val builtins = if (skillsOnly) {
        emptyList()
    } else {
        SlashCommandRanker.rank(scope.builtins, nameQuery) { command ->
            SlashRankableFields(name = command.name, description = command.description)
        }.map { command ->
            SlashAutocompleteItem(
                key = "command:${command.name.lowercase()}",
                title = "/${command.name}",
                subtitle = command.argHint,
                description = command.description,
                completion = ComposerAutocompleteSelection("/${command.name} "),
                rankName = command.name,
            )
        }
    }

    val skills = SlashSkillFormatter.matching(nameQuery, state.skillSuggestions)
        .filter { skill -> skill.slashName.isNotEmpty() && skill.slashName.lowercase() !in builtinNames }
        .map { skill ->
            val slug = skill.slashName
            SlashAutocompleteItem(
                key = "skill:${slug.lowercase()}",
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

    val agentCommands = if (skillsOnly) {
        emptyList()
    } else {
        AgentSlashCommandSuggestion.matching(
            query = nameQuery,
            commands = state.serverCommands,
            excludedNames = builtinNames + skillNames,
            matchingAliases = scope.isHermes,
        ).map { command ->
            SlashAutocompleteItem(
                key = "server:${command.id}",
                title = "/${command.name}",
                subtitle = command.argHint,
                description = command.description,
                completion = ComposerAutocompleteSelection("/${command.name} "),
                rankName = command.name,
            )
        }
    }
    return builtins + skills + agentCommands
}

private fun subArgumentItems(
    command: SlashCommand,
    argQuery: String,
    state: ChatUiState,
    reasoningLevels: List<String>?,
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

        SlashCommandSubArgs.REASONING_LEVELS ->
            (reasoningLevels ?: SlashCommandCatalog.reasoningLevels).map { effort ->
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

