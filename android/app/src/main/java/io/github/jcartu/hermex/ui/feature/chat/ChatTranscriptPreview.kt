package io.github.jcartu.hermex.ui.feature.chat

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import io.github.jcartu.hermex.model.ChatMessage
import io.github.jcartu.hermex.model.JSONValue
import io.github.jcartu.hermex.model.ToolCall
import io.github.jcartu.hermex.model.ToolCallGroup
import io.github.jcartu.hermex.ui.adaptive.FoldState
import io.github.jcartu.hermex.ui.adaptive.LocalFoldState
import io.github.jcartu.hermex.ui.adaptive.LocalPaneCount
import io.github.jcartu.hermex.ui.theme.HermexTheme

@Preview(name = "Transcript · 1 pane", widthDp = 390, heightDp = 844, showBackground = true)
@Composable
private fun TranscriptOnePanePreview() = TranscriptFixturePreview(1)

@Preview(name = "Transcript · 2 panes", widthDp = 760, heightDp = 900, showBackground = true)
@Composable
private fun TranscriptTwoPanePreview() = TranscriptFixturePreview(2)

@Preview(name = "Transcript · 3 panes", widthDp = 1280, heightDp = 900, showBackground = true)
@Composable
private fun TranscriptThreePanePreview() = TranscriptFixturePreview(3)

/** ~30 mixed rows exercise compact activity, folds, long messages and media-safe Markdown. */
@Composable
private fun TranscriptFixturePreview(paneCount: Int) {
    val fixture = previewFixture()
    HermexTheme {
        CompositionLocalProvider(
            LocalPaneCount provides paneCount,
            LocalFoldState provides FoldState(),
        ) {
            ChatTranscript(
                state = fixture,
                listState = rememberLazyListState(),
                attachmentUrlResolver = { null },
                attachmentDataResolver = { null },
                imageLoader = null,
                onListen = {},
                onLoadOlder = {},
                onRetry = {},
                onOpenWeb = {},
                onOpenWorkspaceFile = {},
                onAskHermex = {},
                hapticsEnabled = false,
                modifier = Modifier,
            )
        }
    }
}

private fun previewFixture(): ChatUiState {
    val messages = buildList {
        repeat(10) { turn ->
            add(
                ChatMessage(
                    role = "user",
                    content = if (turn == 4) {
                        "Please review this deliberately long request. ".repeat(18)
                    } else {
                        "Please inspect module $turn and explain the result."
                    },
                    timestamp = 1_700_000_000.0 + turn * 60,
                    messageId = "user-$turn",
                ),
            )
            add(
                ChatMessage(
                    role = "assistant",
                    content = "",
                    timestamp = 1_700_000_005.0 + turn * 60,
                    messageId = "tool-$turn",
                ),
            )
            add(
                ChatMessage(
                    role = "assistant",
                    content = if (turn % 3 == 0) {
                        "Finished turn $turn. The tuple ${(4 + turn)}, -2 is written as `${'$'}(${4 + turn},-2)${'$'}`.\n\n| File | State |\n|---|---|\n| Module.kt | Ready |"
                    } else {
                        "Finished turn $turn with a concise, selectable answer."
                    },
                    timestamp = 1_700_000_024.0 + turn * 60,
                    messageId = "reply-$turn",
                ),
            )
        }
    }
    val transcript = messages.mapIndexed { index, message ->
        TranscriptMessage(
            loadedIndex = index,
            renderID = "preview:$index",
            anchorID = message.messageId ?: "preview:$index",
            message = message,
        )
    }
    val toolGroups = (0 until 10).map { turn ->
        ToolCallGroup(
            id = "group-$turn",
            anchorMessageID = "tool-$turn",
            toolCalls = listOf(
                ToolCall(
                    id = "call-$turn",
                    name = if (turn % 2 == 0) "read_file" else "patch",
                    preview = if (turn % 2 == 0) "Read 42 lines" else "@@ -1 +1 @@\n-old\n+new",
                    args = if (turn % 2 == 0) {
                        mapOf("path" to JSONValue.string("src/Module$turn.kt"))
                    } else {
                        mapOf(
                            "path" to JSONValue.string("src/Module$turn.kt"),
                            "old_string" to JSONValue.string("old"),
                            "new_string" to JSONValue.string("new"),
                        )
                    },
                    duration = 0.4 + turn / 10.0,
                    isError = false,
                    isCompleted = true,
                    startedAt = 1_700_000_001.0 + turn * 60,
                ),
            ),
        )
    }
    return ChatUiState(
        sessionId = "preview",
        displayTitle = "Transcript preview",
        messages = messages,
        transcriptMessages = transcript,
        completedToolCallGroups = toolGroups,
        completedReasoningGroups = listOf(
            ReasoningGroup(id = "reasoning", anchorMessageID = "tool-2", text = "Compared the call sites and checked the protocol contract."),
        ),
        showsThinkingAndToolCards = true,
        hidesAttachmentPaths = true,
    )
}
