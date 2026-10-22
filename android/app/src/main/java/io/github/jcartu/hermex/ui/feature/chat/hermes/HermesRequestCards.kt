package io.github.jcartu.hermex.ui.feature.chat.hermes

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.jcartu.hermex.model.ApprovalChoice
import io.github.jcartu.hermex.model.hermes.BotApprovalRequest
import io.github.jcartu.hermex.model.hermes.BotConnectionOperation
import io.github.jcartu.hermex.model.hermes.BotCredentialRequest
import io.github.jcartu.hermex.model.hermes.BotDesktopTaskRequest
import io.github.jcartu.hermex.model.hermes.BotPendingRequest
import io.github.jcartu.hermex.model.hermes.BotQuestionAnswer
import io.github.jcartu.hermex.model.hermes.BotQuestionRequest
import io.github.jcartu.hermex.model.hermes.BotRequestResolution
import io.github.jcartu.hermex.model.hermes.BotRequestWithdrawal
import io.github.jcartu.hermex.model.hermes.HermesRequestSubject
import io.github.jcartu.hermex.net.hermes.BotApprovalChoice
import io.github.jcartu.hermex.net.hermes.BotConnectionAnswer
import io.github.jcartu.hermex.ui.feature.chat.ApprovalOverlayContent
import io.github.jcartu.hermex.ui.feature.chat.ApprovalRequestOverlay
import io.github.jcartu.hermex.ui.feature.chat.ApprovalScopeFormatter
import io.github.jcartu.hermex.ui.theme.SuccessDark
import io.github.jcartu.hermex.ui.theme.SuccessLight
import kotlinx.coroutines.delay

/**
 * Port of `BotPendingRequestCard` (`Features/Bots/BotPendingRequestCard.swift`): the request
 * blocking a Hermes session or Bot Mode room, rendered where the work stopped. Credential
 * and connection setup values live only in this composition, never enter saved instance
 * state, and are discarded before their callbacks run.
 */
@Composable
fun BotPendingRequestCard(
    request: BotPendingRequest,
    isEnabled: Boolean,
    canStop: Boolean,
    isAnswering: Boolean,
    resolution: BotRequestResolution?,
    onApprove: (BotApprovalChoice) -> Unit,
    onAnswer: (List<BotQuestionAnswer>) -> Unit,
    onSkip: () -> Unit,
    onCredential: (String) -> Unit,
    onStop: () -> Unit,
    onConnection: (BotConnectionAnswer) -> Unit,
    modifier: Modifier = Modifier,
    subject: HermesRequestSubject = HermesRequestSubject.HERMES,
) {
    val identity = when (subject) {
        HermesRequestSubject.HERMES -> "Hermes"
        HermesRequestSubject.BOT -> "Bot"
    }
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .widthIn(max = 560.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 5.dp,
        shadowElevation = 8.dp,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            when (request) {
                is BotPendingRequest.Approval -> BotApprovalRequestBody(
                    approval = request.request,
                    identity = identity,
                    isEnabled = isEnabled,
                    isAnswering = isAnswering,
                    onApprove = onApprove,
                )
                is BotPendingRequest.Question -> BotQuestionRequestBody(
                    question = request.request,
                    identity = identity,
                    isEnabled = isEnabled,
                    isAnswering = isAnswering,
                    onAnswer = onAnswer,
                    onSkip = onSkip,
                )
                is BotPendingRequest.Credential -> BotCredentialRequestBody(
                    credential = request.request,
                    identity = identity,
                    subject = subject,
                    isEnabled = isEnabled,
                    isAnswering = isAnswering,
                    onCredential = onCredential,
                )
                is BotPendingRequest.DesktopTask -> HermesDesktopTaskRequestBody(
                    task = request.request,
                    identity = identity,
                    subject = subject,
                    canStop = canStop,
                    onStop = onStop,
                )
                is BotPendingRequest.Connection -> HermesConnectionRequestBody(
                    operation = request.operation,
                    identity = identity,
                    subject = subject,
                    isEnabled = isEnabled,
                    isAnswering = isAnswering,
                    onConnection = onConnection,
                )
            }
            resolution?.let {
                Text(
                    text = it.message(subject),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (it.outcome == BotRequestResolution.Outcome.ANSWERED) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
            }
        }
    }
}

/**
 * Port of `BotApprovalRequest.overlayContent(pendingCount:)` in
 * `ApprovalRequestOverlay.swift`: adapts direct-Hermes choices and Profile scope copy to the
 * shared approval overlay without widening the host's offered choice set.
 */
fun BotApprovalRequest.overlayContent(pendingCount: Int): ApprovalOverlayContent = ApprovalOverlayContent(
    description = consequence,
    command = command,
    scopeLine = ApprovalScopeFormatter.hermesLine(
        keys = patternKeys,
        description = consequence,
        command = command,
        toolName = toolName,
        offersSession = offersSession,
        offersAlways = offersAlways,
    ),
    pendingCount = pendingCount.coerceAtLeast(1),
    choices = choices.map { it.approvalChoice() },
)

/**
 * The approval body from `BotPendingRequestCard.swift`, backed by the shared
 * `ApprovalRequestOverlay(content:...)` rendering so both server types keep one decision UI.
 */
@Composable
private fun BotApprovalRequestBody(
    approval: BotApprovalRequest,
    identity: String,
    isEnabled: Boolean,
    isAnswering: Boolean,
    onApprove: (BotApprovalChoice) -> Unit,
) {
    val content = approval.overlayContent(pendingCount = 1)
    ApprovalRequestOverlay(
        content = content,
        isResponding = isAnswering,
        errorMessage = null,
        onChoice = { choice -> onApprove(choice.botChoice()) },
        onSkipAll = {},
        embedded = true,
        identity = identity,
        isEnabled = isEnabled,
        showSkipAll = false,
    )
}

private fun BotApprovalChoice.approvalChoice(): ApprovalChoice = when (this) {
    BotApprovalChoice.ONCE -> ApprovalChoice.ONCE
    BotApprovalChoice.SESSION -> ApprovalChoice.SESSION
    BotApprovalChoice.ALWAYS -> ApprovalChoice.ALWAYS
    BotApprovalChoice.DENY -> ApprovalChoice.DENY
}

private fun ApprovalChoice.botChoice(): BotApprovalChoice = when (this) {
    ApprovalChoice.ONCE -> BotApprovalChoice.ONCE
    ApprovalChoice.SESSION -> BotApprovalChoice.SESSION
    ApprovalChoice.ALWAYS -> BotApprovalChoice.ALWAYS
    ApprovalChoice.DENY -> BotApprovalChoice.DENY
}

/** Port of `BotQuestionRequestBody` in `BotPendingRequestCard.swift`. */
@Composable
private fun BotQuestionRequestBody(
    question: BotQuestionRequest,
    identity: String,
    isEnabled: Boolean,
    isAnswering: Boolean,
    onAnswer: (List<BotQuestionAnswer>) -> Unit,
    onSkip: () -> Unit,
) {
    val picked = remember(question.requestID) { mutableStateMapOf<String, Set<Int>>() }
    val typed = remember(question.requestID) { mutableStateMapOf<String, String>() }
    val unanswered = question.questions.filterNot(BotQuestionRequest.Question::isAnswered)
    val submitsOnTap = !question.isBatch && question.questions.firstOrNull()?.allowsMultipleChoices == false
    val hasAnswer = unanswered.isNotEmpty() && unanswered.all { item ->
        picked[item.id].orEmpty().isNotEmpty() || typed[item.id].orEmpty().trim().isNotEmpty()
    }
    val canSubmit = isEnabled && !isAnswering && hasAnswer

    HermesRequestHeader(
        icon = Icons.Filled.HelpOutline,
        title = if (question.questions.size > 1) "${question.questions.size} questions" else "Clarification required",
        identity = if (question.questions.size > 1) {
            "${question.unansweredCount} still to answer · $identity"
        } else {
            identity
        },
    )

    question.questions.forEach { item ->
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = item.prompt,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(11.dp))
                    .padding(11.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
            item.lockedAnswer?.let { locked ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.CheckCircle, null, Modifier.size(17.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        "Already answered: $locked",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } ?: run {
                item.choices.forEach { choice ->
                    val selected = choice.id in picked[item.id].orEmpty()
                    OutlinedButton(
                        onClick = {
                            if (submitsOnTap) {
                                onAnswer(listOf(BotQuestionAnswer(item.wireID, choice.wireLabel)))
                            } else {
                                val selection = picked[item.id].orEmpty().toMutableSet()
                                if (item.allowsMultipleChoices) {
                                    if (!selection.add(choice.id)) selection.remove(choice.id)
                                } else if (choice.id in selection) {
                                    selection.clear()
                                } else {
                                    selection.clear()
                                    selection.add(choice.id)
                                }
                                picked[item.id] = selection
                            }
                        },
                        enabled = isEnabled && !isAnswering,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
                        shape = RoundedCornerShape(13.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                        ),
                    ) {
                        if (!submitsOnTap) {
                            Icon(
                                imageVector = selectionIcon(selected, item.allowsMultipleChoices),
                                contentDescription = null,
                                modifier = Modifier.padding(end = 8.dp),
                            )
                        }
                        Text(
                            text = choice.label,
                            modifier = Modifier.weight(1f),
                            fontWeight = FontWeight.SemiBold,
                        )
                        if (choice.isRecommended) {
                            Text(
                                "Recommended",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    OutlinedTextField(
                        value = typed[item.id].orEmpty(),
                        onValueChange = { typed[item.id] = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Type a response") },
                        enabled = isEnabled && !isAnswering,
                        minLines = 1,
                        maxLines = 5,
                        shape = RoundedCornerShape(13.dp),
                        keyboardOptions = KeyboardOptions(imeAction = if (submitsOnTap) ImeAction.Send else ImeAction.Default),
                        keyboardActions = KeyboardActions(
                            onSend = { if (canSubmit) onAnswer(questionAnswers(question, picked, typed)) },
                        ),
                    )
                    if (submitsOnTap) {
                        RequestSubmitButton(isAnswering, canSubmit) {
                            onAnswer(questionAnswers(question, picked, typed))
                        }
                    }
                }
            }
        }
    }

    if (!submitsOnTap) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { onAnswer(questionAnswers(question, picked, typed)) },
                enabled = canSubmit,
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Filled.Send, null, Modifier.padding(end = 6.dp))
                Text("Send answers")
            }
            OutlinedButton(
                onClick = onSkip,
                enabled = isEnabled && !isAnswering,
                modifier = Modifier.weight(1f),
            ) {
                Text("Skip")
            }
        }
    }
}

private fun selectionIcon(selected: Boolean, multiple: Boolean): ImageVector = when {
    multiple && selected -> Icons.Filled.CheckBox
    multiple -> Icons.Filled.CheckBoxOutlineBlank
    selected -> Icons.Filled.RadioButtonChecked
    else -> Icons.Filled.RadioButtonUnchecked
}

private fun questionAnswers(
    question: BotQuestionRequest,
    picked: Map<String, Set<Int>>,
    typed: Map<String, String>,
): List<BotQuestionAnswer> = question.questions.filterNot(BotQuestionRequest.Question::isAnswered).map { item ->
    val labels = item.choices.filter { it.id in picked[item.id].orEmpty() }.map(BotQuestionRequest.Choice::wireLabel)
    val free = typed[item.id].orEmpty().trim()
    if (item.allowsMultipleChoices) {
        BotQuestionAnswer.selections(item.wireID, labels + listOfNotNull(free.takeIf(String::isNotEmpty)))
    } else {
        BotQuestionAnswer(item.wireID, free.ifEmpty { labels.firstOrNull().orEmpty() })
    }
}

/** Port of `BotCredentialRequestBody` in `BotPendingRequestCard.swift`. */
@Composable
private fun BotCredentialRequestBody(
    credential: BotCredentialRequest,
    identity: String,
    subject: HermesRequestSubject,
    isEnabled: Boolean,
    isAnswering: Boolean,
    onCredential: (String) -> Unit,
) {
    // Deliberately not saveable: this secret belongs only to this mounted request card.
    var value by remember(credential.requestID) { mutableStateOf("") }
    val canEdit = isEnabled && !isAnswering
    val canSubmit = canEdit && value.isNotEmpty()
    val symbol = if (credential.kind == BotCredentialRequest.Kind.SUDO) Icons.Filled.Lock else Icons.Filled.Key

    HermesRequestHeader(symbol, credential.title, identity)
    Text(credential.detail(subject), style = MaterialTheme.typography.bodyMedium)
    credential.envVar?.let { envVar ->
        Box(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(8.dp))
                .horizontalScroll(rememberScrollState()),
        ) {
            Text(
                envVar,
                modifier = Modifier.padding(10.dp),
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            )
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Bottom) {
        OutlinedTextField(
            value = value,
            onValueChange = { value = it },
            modifier = Modifier.weight(1f),
            placeholder = {
                Text(if (credential.kind == BotCredentialRequest.Kind.SUDO) "Administrator password" else "Secret value")
            },
            enabled = canEdit,
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Send,
            ),
            keyboardActions = KeyboardActions(
                onSend = {
                    if (canSubmit) {
                        val outgoing = value
                        value = ""
                        onCredential(outgoing)
                    }
                },
            ),
            shape = RoundedCornerShape(13.dp),
        )
        RequestSubmitButton(isAnswering, canSubmit) {
            val outgoing = value
            value = ""
            onCredential(outgoing)
        }
    }
    Text(
        credential.handling(subject),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    OutlinedButton(onClick = { onCredential("") }, enabled = canEdit, modifier = Modifier.fillMaxWidth()) {
        Text("Skip")
    }
    Text(
        credential.kind.skipConsequence,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * Port of `HermesDesktopTaskRequestBody` (`Features/Chat/HermesRequestCards.swift`): a
 * Desktop-only request is shown but never answered from Android; Stop remains available.
 */
@Composable
internal fun HermesDesktopTaskRequestBody(
    task: BotDesktopTaskRequest,
    identity: String,
    subject: HermesRequestSubject,
    canStop: Boolean,
    onStop: () -> Unit,
) {
    HermesRequestHeader(Icons.Filled.DesktopWindows, "Hermes Desktop is handling this", identity)
    Text(task.kind.title(subject), style = MaterialTheme.typography.bodyMedium)
    Text(
        task.kind.detail(subject),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    OutlinedButton(
        onClick = onStop,
        enabled = canStop,
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
    ) {
        Icon(Icons.Filled.Stop, null, Modifier.padding(end = 6.dp))
        Text("Stop current work")
    }
}

/**
 * Port of `HermesConnectionRequestBody` (`Features/Chat/HermesRequestCards.swift`): every
 * operation row exposes only the moves the host currently permits and never persists setup
 * values beyond the mounted operation.
 */
@Composable
internal fun HermesConnectionRequestBody(
    operation: BotConnectionOperation,
    identity: String,
    subject: HermesRequestSubject,
    isEnabled: Boolean,
    isAnswering: Boolean,
    onConnection: (BotConnectionAnswer) -> Unit,
) {
    var nowEpochSeconds by remember(operation.opID) { mutableLongStateOf(System.currentTimeMillis() / 1_000L) }
    LaunchedEffect(operation.opID) {
        while (true) {
            nowEpochSeconds = System.currentTimeMillis() / 1_000L
            delay(60_000L)
        }
    }
    val minutes = BotConnectionOperation.minutesLeft(operation.deadline, nowEpochSeconds.toDouble())

    HermesRequestHeader(Icons.Filled.Link, "Connect apps", identity)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Waiting · ${minutes}m left", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        Text(
            if (subject == HermesRequestSubject.BOT) {
                "The bot is paused until each app is connected or skipped."
            } else {
                "Hermes is paused until each app is connected or skipped."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    operation.targets.forEach { target ->
        HermesConnectionTargetRow(operation.opID, target, subject, isEnabled, isAnswering, onConnection)
    }
    OutlinedButton(
        onClick = { onConnection(BotConnectionAnswer.ContinueWithout) },
        enabled = isEnabled && !isAnswering,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("Continue without")
    }
    Text(
        if (subject == HermesRequestSubject.BOT) {
            "Releases the bot now. Apps not connected stay off for this reply."
        } else {
            "Releases Hermes now. Apps not connected stay off for this reply."
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun HermesConnectionTargetRow(
    operationID: String,
    target: BotConnectionOperation.Target,
    subject: HermesRequestSubject,
    isEnabled: Boolean,
    isAnswering: Boolean,
    onConnection: (BotConnectionAnswer) -> Unit,
) {
    // Deliberately not saveable: setup values, including secrets, belong to this one row only.
    var values by remember(operationID, target.name) { mutableStateOf<Map<String, String>>(emptyMap()) }
    val canAct = isEnabled && !isAnswering
    val env = target.env(values)
    val canConnect = canAct && target.accepts(env)
    val uriHandler = LocalUriHandler.current

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(11.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(target.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    connectionSubtitle(target)?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                ConnectionStateLabel(target.state)
            }
            listOfNotNull(target.discoveryError, target.detail, target.instructions).distinct().forEach { note ->
                Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            when {
                target.finishesOnTheMac -> {
                    Text("Finish on the Mac.", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                    Text(
                        "This sign-in returns to a browser on the Mac, so it can’t complete on Android.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                target.linkToOpen != null -> Text(
                    "Opens in your browser. Hermes notices when you finish.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                target.canSkip && !target.canConnect &&
                    (target.state == BotConnectionOperation.Target.State.FAILED || target.state == BotConnectionOperation.Target.State.EXPIRED) -> Text(
                    if (subject == HermesRequestSubject.BOT) {
                        "Skip it and ask the bot to try again."
                    } else {
                        "Skip it and ask Hermes to try again."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (target.canConnect) {
                target.requiredEnv.forEach { field ->
                    ConnectionEnvField(
                        field = field,
                        value = field.value(values),
                        enabled = canAct,
                        onValueChange = { values = values + (field.name to it) },
                        onSubmit = {
                            val outgoing = target.env(values)
                            if (canAct && target.accepts(outgoing)) {
                                values = emptyMap()
                                onConnection(BotConnectionAnswer.Connect(target.name, outgoing))
                            }
                        },
                    )
                }
            }
            if (target.canSkip || target.canConnect || target.linkToOpen != null) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    target.linkToOpen?.let { link ->
                        Button(
                            onClick = { uriHandler.openUri(link) },
                            enabled = isEnabled,
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Filled.OpenInBrowser, null, Modifier.padding(end = 5.dp))
                            Text("Open link")
                        }
                    } ?: if (target.canConnect) {
                        Button(
                            onClick = {
                                val outgoing = target.env(values)
                                if (canAct && target.accepts(outgoing)) {
                                    values = emptyMap()
                                    onConnection(BotConnectionAnswer.Connect(target.name, outgoing))
                                }
                            },
                            enabled = canConnect,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("Connect")
                        }
                    } else {
                        Unit
                    }
                    if (target.canSkip) {
                        OutlinedButton(
                            onClick = { onConnection(BotConnectionAnswer.Skip(target.name)) },
                            enabled = canAct,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("Skip")
                        }
                    }
                }
            }
        }
    }
}

private fun connectionSubtitle(target: BotConnectionOperation.Target): String? {
    if (target.kind != BotConnectionOperation.Target.Kind.MCP) return null
    return when (target.action) {
        BotConnectionOperation.Target.Action.INSTALL -> "MCP server · Install"
        BotConnectionOperation.Target.Action.ENABLE -> "MCP server · Enable"
        BotConnectionOperation.Target.Action.AUTHORIZE -> "MCP server · Sign in"
        else -> "MCP server"
    }
}

@Composable
private fun ConnectionStateLabel(state: BotConnectionOperation.Target.State?) {
    val success = if (isSystemInDarkTheme()) SuccessDark else SuccessLight
    val title: String
    val icon: ImageVector?
    val tint: Color
    when (state) {
        BotConnectionOperation.Target.State.PENDING, BotConnectionOperation.Target.State.INITIATED -> {
            title = "Waiting"; icon = null; tint = MaterialTheme.colorScheme.onSurfaceVariant
        }
        BotConnectionOperation.Target.State.CONNECTED -> {
            title = "Connected"; icon = Icons.Filled.CheckCircle; tint = success
        }
        BotConnectionOperation.Target.State.SKIPPED -> {
            title = "Skipped"; icon = null; tint = MaterialTheme.colorScheme.onSurfaceVariant
        }
        BotConnectionOperation.Target.State.FAILED -> {
            title = "Failed"; icon = Icons.Filled.Error; tint = MaterialTheme.colorScheme.error
        }
        BotConnectionOperation.Target.State.EXPIRED -> {
            title = "Expired"; icon = Icons.Filled.Schedule; tint = MaterialTheme.colorScheme.error
        }
        BotConnectionOperation.Target.State.NOT_CONNECTED -> {
            title = "Not connected"; icon = null; tint = MaterialTheme.colorScheme.onSurfaceVariant
        }
        BotConnectionOperation.Target.State.UNAVAILABLE -> {
            title = "Unavailable"; icon = null; tint = MaterialTheme.colorScheme.onSurfaceVariant
        }
        null -> {
            title = "Unknown"; icon = null; tint = MaterialTheme.colorScheme.onSurfaceVariant
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        icon?.let { Icon(it, null, Modifier.size(15.dp), tint = tint) }
        Text(title, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = tint)
    }
}

@Composable
private fun ConnectionEnvField(
    field: BotConnectionOperation.EnvField,
    value: String,
    enabled: Boolean,
    onValueChange: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(field.name, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
            val badge = when {
                field.isSecret -> "secret"
                !field.isRequired -> "optional"
                else -> null
            }
            badge?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        field.prompt?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(if (field.isSecret) "Secret value" else "Value") },
            enabled = enabled,
            singleLine = true,
            visualTransformation = if (field.isSecret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                keyboardType = if (field.isSecret) KeyboardType.Password else KeyboardType.Text,
                imeAction = ImeAction.Go,
            ),
            keyboardActions = KeyboardActions(onGo = { onSubmit() }),
            shape = RoundedCornerShape(11.dp),
        )
    }
}

/** Port of `HermesRequestHeader` (`Features/Chat/HermesRequestCards.swift`). */
@Composable
internal fun HermesRequestHeader(icon: ImageVector, title: String, identity: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(identity, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun RequestSubmitButton(isBusy: Boolean, canSubmit: Boolean, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        enabled = canSubmit,
        modifier = Modifier.size(48.dp),
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ),
    ) {
        if (isBusy) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        } else {
            Icon(Icons.Filled.Send, "Send answer")
        }
    }
}

/** Port of `BotRequestWithdrawalNote` in `BotPendingRequestCard.swift`. */
@Composable
fun BotRequestWithdrawalNote(withdrawal: BotRequestWithdrawal, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (withdrawal.reason == BotRequestWithdrawal.Reason.TIMEOUT) Icons.Filled.Schedule else Icons.Filled.Cancel,
            contentDescription = null,
            modifier = Modifier.size(16.dp).padding(end = 4.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            withdrawal.message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
