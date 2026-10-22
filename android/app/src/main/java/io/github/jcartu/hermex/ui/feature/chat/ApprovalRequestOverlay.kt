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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import io.github.jcartu.hermex.model.JSONValue

/** Host-safe validation from `BotPendingRequest.swift` (#1104). */
object ApprovalChoiceValidation {
    /**
     * A present malformed value can never be rebuilt: Deny is the only safe
     * choice. An absent field uses [legacyChoices] for older hosts.
     */
    fun choices(
        fieldPresent: Boolean,
        rawValue: JSONValue?,
        legacyChoices: List<ApprovalChoice> = ApprovalChoice.entries,
    ): List<ApprovalChoice> {
        if (!fieldPresent) return legacyChoices.distinct()
        val array = rawValue?.arrayValue ?: return listOf(ApprovalChoice.DENY)
        val offered = array.mapNotNull { value ->
            value.string?.let(ApprovalChoice::fromRawValue)
        }.distinct()
        if (offered.isEmpty()) return listOf(ApprovalChoice.DENY)
        return if (ApprovalChoice.DENY in offered) offered else offered + ApprovalChoice.DENY
    }
}

data class ApprovalOverlayContent(
    val description: String?,
    val command: String?,
    val scopeLine: String?,
    val pendingCount: Int,
    val choices: List<ApprovalChoice>,
)

/** Port of `ApprovalRequestOverlay.swift`, including 1.9 scope copy. */
@Composable
fun ApprovalRequestOverlay(
    prompt: ApprovalPromptState,
    isResponding: Boolean,
    errorMessage: String?,
    onChoice: (ApprovalChoice) -> Unit,
    onSkipAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val content = ApprovalOverlayContent(
        description = prompt.pending.description,
        command = prompt.pending.command,
        scopeLine = ApprovalScopeFormatter.webUiLine(
            keys = prompt.patternKeys,
            description = prompt.pending.description,
            command = prompt.pending.command,
        ),
        pendingCount = prompt.pendingCount.coerceAtLeast(1),
        choices = ApprovalChoice.entries,
    )
    ApprovalRequestOverlay(content, isResponding, errorMessage, onChoice, onSkipAll, modifier)
}

@Composable
fun ApprovalRequestOverlay(
    content: ApprovalOverlayContent,
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
            .clickable(scrimInteraction, indication = null, onClick = {}),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 18.dp)
                .widthIn(max = 520.dp)
                .heightIn(max = 720.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                tonalElevation = 6.dp,
                shadowElevation = 12.dp,
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    ApprovalHeader(content.pendingCount)
                    ApprovalDetails(content, errorMessage)
                    ApprovalActions(content.choices, isResponding, onChoice, onSkipAll)
                }
            }
        }
    }
}

@Composable
private fun ApprovalHeader(pendingCount: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(Icons.Filled.Warning, null, tint = Color(0xFFFFCC00))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Approval required", style = MaterialTheme.typography.titleMedium)
            Text(
                "Pending approvals: $pendingCount",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ApprovalDetails(content: ApprovalOverlayContent, errorMessage: String?) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        content.description?.trim()?.takeIf(String::isNotEmpty)?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium)
        }
        content.command?.trim()?.takeIf(String::isNotEmpty)?.let { command ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(8.dp))
                    .horizontalScroll(rememberScrollState()),
            ) {
                Text(
                    command,
                    modifier = Modifier.padding(10.dp),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                )
            }
        }
        content.scopeLine?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (content.pendingCount > 1) {
            Text(
                "1 of ${content.pendingCount} pending",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        errorMessage?.trim()?.takeIf(String::isNotEmpty)?.let { message ->
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.Top) {
                Icon(Icons.Filled.Warning, null, tint = MaterialTheme.colorScheme.error)
                Text(
                    "$message\nThe host did not confirm that answer. You can try again.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun ApprovalActions(
    choices: List<ApprovalChoice>,
    isResponding: Boolean,
    onChoice: (ApprovalChoice) -> Unit,
    onSkipAll: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        choices.ifEmpty { listOf(ApprovalChoice.DENY) }.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { choice ->
                    ApprovalDecisionButton(
                        choice = choice,
                        enabled = !isResponding,
                        onClick = { onChoice(choice) },
                        modifier = Modifier.weight(1f),
                    )
                }
                if (row.size == 1) Box(Modifier.weight(1f))
            }
        }
        OutlinedButton(onClick = onSkipAll, enabled = !isResponding, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.Bolt, null, modifier = Modifier.padding(end = 6.dp))
            Text("Skip all this session")
        }
    }
}

@Composable
private fun ApprovalDecisionButton(
    choice: ApprovalChoice,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    val (title, icon) = when (choice) {
        ApprovalChoice.ONCE -> "Allow once" to Icons.Filled.CheckCircle
        ApprovalChoice.SESSION -> "Allow session" to Icons.Filled.LockOpen
        ApprovalChoice.ALWAYS -> "Always allow" to Icons.Filled.Star
        ApprovalChoice.DENY -> "Deny" to Icons.Filled.Cancel
    }
    if (choice == ApprovalChoice.ONCE) {
        Button(onClick, modifier, enabled = enabled) {
            DecisionLabel(title, icon, prominent = true)
        }
    } else {
        OutlinedButton(
            onClick = onClick,
            modifier = modifier,
            enabled = enabled,
            colors = if (choice == ApprovalChoice.DENY) {
                ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
            } else {
                ButtonDefaults.outlinedButtonColors()
            },
        ) {
            DecisionLabel(title, icon, prominent = false)
        }
    }
}

@Composable
private fun DecisionLabel(title: String, icon: ImageVector, prominent: Boolean) {
    Icon(icon, null, modifier = Modifier.padding(end = 6.dp))
    Text(title, fontWeight = if (prominent) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1)
}

/** Exact scope sentences from `Models/Approval.swift` for legacy webui hosts. */
object ApprovalScopeFormatter {
    private val oneTimeKeys = setOf("mcp_elicitation", "protected_instruction_file")

    fun webUiLine(keys: List<String>, description: String?, command: String?): String? {
        if (keys.isEmpty() || keys.any(oneTimeKeys::contains)) return null
        val subject = subject(keys, description, command)
        return when (subject) {
            is Subject.Shell -> "Allow session covers every “${subject.label}” in this session; Always allow covers it on this server from now on."
            is Subject.Plugin -> "Allow session covers every ${subject.tool} call for this reason in this session; Always allow covers it on this server from now on."
            Subject.Python -> "Allow session covers every Python script in this session; Always allow covers every Python script on this server from now on."
            Subject.Ssh -> "Allow session covers writes to SSH config in this session; Always allow covers them on this server from now on."
            is Subject.Computer -> "Allow session covers computer use: ${subject.action} (${subject.mode}) in this session; Always allow covers it on this server from now on."
            Subject.Security -> "Allow session covers this security finding in this session; Always allow covers it on this server from now on."
            is Subject.ShellAndSecurity -> "Allow session covers “${subject.label}” and this security finding in this session; Always allow covers both on this server from now on."
            Subject.Other -> "Allow session covers every action like this one in this session; Always allow covers it on this server from now on."
        }
    }

    private sealed interface Subject {
        data class Shell(val label: String) : Subject
        data class Plugin(val tool: String) : Subject
        data class Computer(val action: String, val mode: String) : Subject
        data class ShellAndSecurity(val label: String) : Subject
        data object Python : Subject
        data object Ssh : Subject
        data object Security : Subject
        data object Other : Subject
    }

    private fun subject(keys: List<String>, description: String?, command: String?): Subject {
        val findings = keys.filter { it.startsWith("tirith:") }
        val others = keys.filterNot { it.startsWith("tirith:") }
        if (findings.size == 1 && others.isEmpty()) return Subject.Security
        if (findings.isEmpty() && others.size == 1) return subject(others.single(), description, command)
        if (findings.size == 1 && others.size == 1) {
            val shell = subject(others.single(), description, null)
            if (shell is Subject.Shell) return Subject.ShellAndSecurity(shell.label)
        }
        return Subject.Other
    }

    private fun subject(key: String, description: String?, command: String?): Subject {
        if (key == "execute_code") return Subject.Python
        if (key == "ssh_config_write") return Subject.Ssh
        if (key.startsWith("plugin_rule:")) {
            val rule = key.removePrefix("plugin_rule:")
            val tool = Regex("^(.+):[0-9a-f]{12}$").matchEntire(rule)?.groupValues?.get(1)
                ?: Regex("^<([^<>\\s]+)>").find(command.orEmpty())?.groupValues?.get(1)
            return tool?.let(Subject::Plugin) ?: Subject.Other
        }
        Regex("^cua:([^:]+):([^:]+)$").matchEntire(key)?.let {
            return Subject.Computer(it.groupValues[1], it.groupValues[2])
        }
        val parts = description?.split("; ")?.map(String::trim).orEmpty()
        return if (key in parts) Subject.Shell(key) else Subject.Other
    }
}
