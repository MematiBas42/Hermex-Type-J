package io.github.jcartu.hermex.ui.feature.chat

import io.github.jcartu.hermex.core.share.SharedImport
import io.github.jcartu.hermex.core.share.SharedImportAttachment
import io.github.jcartu.hermex.core.prefs.ServerConfig
import io.github.jcartu.hermex.core.prefs.ServerConfigRepository
import io.github.jcartu.hermex.core.prefs.SettingsRepository
import io.github.jcartu.hermex.data.CacheStore
import io.github.jcartu.hermex.model.ApprovalChoice
import io.github.jcartu.hermex.model.ApprovalPendingResponse
import io.github.jcartu.hermex.model.ApprovalRespondResponse
import io.github.jcartu.hermex.model.ChatCancelResponse
import io.github.jcartu.hermex.model.ChatMessage
import io.github.jcartu.hermex.model.ChatStartResponse
import io.github.jcartu.hermex.model.ChatSteerResponse
import io.github.jcartu.hermex.model.ChatStreamStatusResponse
import io.github.jcartu.hermex.model.ClarificationPendingResponse
import io.github.jcartu.hermex.model.ClarificationRespondResponse
import io.github.jcartu.hermex.model.CommandsResponse
import io.github.jcartu.hermex.model.ContextWindowSnapshot
import io.github.jcartu.hermex.model.ModelFavoritesStore
import io.github.jcartu.hermex.model.ModelRecentsStore
import io.github.jcartu.hermex.model.ModelsResponse
import io.github.jcartu.hermex.model.PendingApproval
import io.github.jcartu.hermex.model.PendingClarification
import io.github.jcartu.hermex.model.ProfilesResponse
import io.github.jcartu.hermex.model.ReasoningStatusResponse
import io.github.jcartu.hermex.model.SessionDetail
import io.github.jcartu.hermex.model.SessionResponse
import io.github.jcartu.hermex.model.SkillsResponse
import io.github.jcartu.hermex.model.TranscribeResponse
import io.github.jcartu.hermex.model.UploadResponse
import io.github.jcartu.hermex.model.WorkspacesResponse
import io.github.jcartu.hermex.net.ApiError
import io.github.jcartu.hermex.net.ChatStreamEvent
import io.github.jcartu.hermex.net.DoneStreamEvent
import io.github.jcartu.hermex.net.HermesApi
import io.github.jcartu.hermex.net.MeteringStreamEvent
import io.github.jcartu.hermex.net.SseChatStream
import io.github.jcartu.hermex.net.TitleStreamEvent
import io.github.jcartu.hermex.net.ToolStreamEvent
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val serverUrl = "https://hermes.example.com"

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
        encodeDefaults = false
    }

    private lateinit var directory: File
    private lateinit var cacheStore: CacheStore
    private lateinit var api: HermesApi
    private lateinit var sse: SseChatStream
    private lateinit var serverConfig: ServerConfigRepository
    private lateinit var settings: SettingsRepository
    private lateinit var favorites: ModelFavoritesStore
    private lateinit var recents: ModelRecentsStore
    private lateinit var events: MutableSharedFlow<ChatStreamEvent>
    private lateinit var sentMessages: MutableList<String>
    private var capturedAttachments: List<JsonElement>? = null

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        directory = Files.createTempDirectory("chat-vm-test").toFile()
        cacheStore = CacheStore(directory, json)
        api = mockk()
        sse = mockk()
        serverConfig = mockk()
        settings = mockk()
        favorites = mockk()
        recents = mockk()
        events = MutableSharedFlow(extraBufferCapacity = 64)
        sentMessages = mutableListOf()
        capturedAttachments = null

        every { settings.settings } returns flowOf(SettingsRepository.Settings())
        every { favorites.favoriteKeys } returns flowOf(emptyList())
        every { recents.recentKeys } returns flowOf(emptyList())
        coEvery { serverConfig.current() } returns ServerConfig(baseUrl = serverUrl, password = "pw")
        every { sse.lastEventId } returns null
        every { sse.connect(any(), any()) } returns events

        // Composer configuration endpoints (loadComposerConfiguration).
        coEvery { api.profiles() } returns ProfilesResponse()
        coEvery { api.models() } returns ModelsResponse()
        coEvery { api.reasoning(any(), any()) } returns ReasoningStatusResponse()
        coEvery { api.workspaces() } returns WorkspacesResponse()
        coEvery { api.commands() } returns CommandsResponse()
        coEvery { api.skills() } returns SkillsResponse()

        // Transcript + streaming defaults.
        coEvery { api.session(any(), any(), any(), any(), any()) } answers {
            SessionResponse(SessionDetail(sessionId = firstArg(), title = "Session ${firstArg<String>()}"))
        }
        coEvery { api.approvalPending(any()) } returns ApprovalPendingResponse()
        coEvery { api.clarifyPending(any()) } returns ClarificationPendingResponse()
        coEvery {
            api.startChat(any(), any(), any(), any(), any(), any(), any(), any())
        } answers {
            sentMessages.add(secondArg())
            @Suppress("UNCHECKED_CAST")
            val attachments = arg<Any?>(7) as? List<JsonElement>
            capturedAttachments = attachments
            ChatStartResponse(streamId = "stream-1", sessionId = "s1")
        }
        coEvery { api.cancelChat(any()) } returns ChatCancelResponse(ok = true)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        directory.deleteRecursively()
    }

    private fun viewModel(sessionId: String? = "s1", newChat: Boolean = false): ChatViewModel =
        ChatViewModel(
            api = api,
            sse = sse,
            cacheStore = cacheStore,
            serverConfig = serverConfig,
            settings = settings,
            favorites = favorites,
            recents = recents,
            initialSessionId = sessionId,
            initialNewChat = newChat,
            ioDispatcher = dispatcher,
        )

    /** Activates session "s1" and starts a stream by sending "hello". */
    private fun startedViewModel(): ChatViewModel {
        val vm = viewModel()
        vm.switchToSession("s1", newChat = false)
        vm.updateDraft("hello")
        vm.send()
        check(vm.uiState.value.activeStreamId == "stream-1") { "stream did not start" }
        return vm
    }

    private fun serverTranscript(now: Double = System.currentTimeMillis() / 1_000.0) = listOf(
        ChatMessage(role = "user", content = "hello", timestamp = now - 2, messageId = "m1"),
        ChatMessage(role = "assistant", content = "Hi!", timestamp = now - 1, messageId = "m2"),
    )

    // MARK: - Load + activation

    @Test
    fun `switchToSession loads the transcript and title`() = runTest(dispatcher) {
        coEvery { api.session(any(), any(), any(), any(), any()) } returns SessionResponse(
            SessionDetail(sessionId = "s1", title = "My Chat", messages = serverTranscript()),
        )

        val vm = viewModel()
        vm.switchToSession("s1", newChat = false)

        val state = vm.uiState.value
        assertEquals(listOf("m1", "m2"), state.messages.map { it.messageId })
        assertEquals(2, state.transcriptMessages.size)
        assertEquals("My Chat", state.displayTitle)
        assertFalse(state.isLoading)
        assertNull(state.errorMessage)
    }

    @Test
    fun `switchToSession is idempotent for the active session`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.switchToSession("s1", newChat = false)
        vm.switchToSession("s1", newChat = false)

        coVerify(exactly = 1) { api.session("s1", any(), any(), any(), any()) }
    }

    @Test
    fun `switchToSession preserves draft and staged attachments`() = runTest(dispatcher) {
        coEvery { api.uploadFile(any(), any(), any()) } returns UploadResponse(
            filename = "a.png",
            path = "/up/a.png",
            size = 1,
            mime = "image/png",
            isImage = true,
        )

        val vm = viewModel()
        vm.switchToSession("s1", newChat = false)
        vm.updateDraft("keep me")
        vm.uploadAttachment(byteArrayOf(1), "a.png")

        vm.switchToSession("s2", newChat = false)

        val state = vm.uiState.value
        assertEquals("s2", state.sessionId)
        assertEquals("keep me", state.draft)
        assertEquals(listOf("/up/a.png"), state.pendingAttachments.map { it.path })
    }

    @Test
    fun `load failure without cache surfaces the error and retryLoad recovers`() = runTest(dispatcher) {
        coEvery { api.session(any(), any(), any(), any(), any()) } throws ApiError.Http(500, "boom")

        val vm = viewModel()
        vm.switchToSession("s1", newChat = false)
        assertNotNull(vm.uiState.value.errorMessage)

        coEvery { api.session(any(), any(), any(), any(), any()) } returns SessionResponse(
            SessionDetail(sessionId = "s1", title = "Back", messages = serverTranscript()),
        )
        vm.retryLoad()

        assertNull(vm.uiState.value.errorMessage)
        assertEquals(2, vm.uiState.value.messages.size)
    }

    @Test
    fun `connectivity failure falls back to the offline cache`() = runTest(dispatcher) {
        cacheStore.cacheMessages(serverTranscript(), serverUrl, "s1")
        coEvery { api.session(any(), any(), any(), any(), any()) } throws
            ApiError.Network(java.net.UnknownHostException("offline"))

        val vm = viewModel()
        vm.switchToSession("s1", newChat = false)

        val state = vm.uiState.value
        assertTrue(state.isViewingCachedData)
        assertEquals(2, state.messages.size)
        assertNull(state.errorMessage)
    }

    // MARK: - Streaming lifecycle

    @Test
    fun `tokens accumulate into the streaming assistant message`() = runTest(dispatcher) {
        val vm = startedViewModel()

        events.emit(ChatStreamEvent.Token("Hel"))
        events.emit(ChatStreamEvent.Token("lo"))
        advanceTimeBy(20)

        val state = vm.uiState.value
        val assistant = state.messages.last()
        assertEquals("assistant", assistant.role)
        assertEquals("Hello", assistant.content)
        assertEquals(assistant.messageId, state.streamingAssistantMessageId)
        assertEquals("stream-1", state.activeStreamId)
        assertTrue(state.streamingContentTrigger > 0)
    }

    @Test
    fun `reasoning accumulates into liveReasoningText`() = runTest(dispatcher) {
        val vm = startedViewModel()

        events.emit(ChatStreamEvent.Reasoning("Thinking about"))
        events.emit(ChatStreamEvent.Reasoning(" it"))
        advanceTimeBy(20)

        assertEquals("Thinking about it", vm.uiState.value.liveReasoningText)
    }

    @Test
    fun `tool lifecycle produces live cards then archives to completed groups on done`() = runTest(dispatcher) {
        val vm = startedViewModel()

        events.emit(ChatStreamEvent.ToolStarted(ToolStreamEvent(name = "bash", preview = "ls")))
        assertEquals(1, vm.uiState.value.liveToolCalls.size)
        assertFalse(vm.uiState.value.liveToolCalls.single().isCompleted)

        events.emit(ChatStreamEvent.ToolCompleted(ToolStreamEvent(name = "bash", duration = 1.2)))
        assertTrue(vm.uiState.value.liveToolCalls.single().isCompleted)
        assertEquals(1.2, vm.uiState.value.liveToolCalls.single().duration!!, 0.0)

        events.emit(
            ChatStreamEvent.Done(
                DoneStreamEvent(
                    usage = ContextWindowSnapshot(inputTokens = 10, outputTokens = 5),
                    session = SessionDetail(sessionId = "s1", messages = serverTranscript()),
                ),
            ),
        )

        val state = vm.uiState.value
        assertTrue(state.liveToolCalls.isEmpty())
        assertEquals(
            listOf("bash"),
            state.completedToolCallGroups.flatMap { it.toolCalls }.map { it.name },
        )
        assertNull(state.activeStreamId)
        assertEquals(1, state.responseCompletionTrigger)
        assertEquals(10, state.contextWindow?.inputTokens)
    }

    @Test
    fun `title event updates the display title and ignores foreign sessions`() = runTest(dispatcher) {
        val vm = startedViewModel()

        events.emit(ChatStreamEvent.Title(TitleStreamEvent(sessionId = "s1", title = "New Title")))
        assertEquals("New Title", vm.uiState.value.displayTitle)

        events.emit(ChatStreamEvent.Title(TitleStreamEvent(sessionId = "other", title = "X")))
        assertEquals("New Title", vm.uiState.value.displayTitle)
    }

    @Test
    fun `metering sets tokens per second only when available`() = runTest(dispatcher) {
        val vm = startedViewModel()

        events.emit(
            ChatStreamEvent.Metering(
                MeteringStreamEvent(tokensPerSecond = 42.5, isTokensPerSecondAvailable = true, isEstimated = false),
            ),
        )
        assertEquals(42.5, vm.uiState.value.liveTokensPerSecond!!, 0.0)

        events.emit(
            ChatStreamEvent.Metering(
                MeteringStreamEvent(tokensPerSecond = 9.0, isTokensPerSecondAvailable = false),
            ),
        )
        assertNull(vm.uiState.value.liveTokensPerSecond)
    }

    @Test
    fun `approval pending sets the prompt and respond clears it`() = runTest(dispatcher) {
        coEvery { api.respondApproval(any(), any(), any()) } returns ApprovalRespondResponse(ok = true)
        val vm = startedViewModel()

        events.emit(
            ChatStreamEvent.ApprovalPending(
                ApprovalPendingResponse(
                    pending = PendingApproval.of(approvalId = "ap1", command = "rm -rf /tmp/x"),
                    pendingCount = 2,
                ),
            ),
        )

        val prompt = vm.uiState.value.approvalPrompt
        assertNotNull(prompt)
        assertEquals("s1", prompt!!.sessionId)
        assertEquals(2, prompt.pendingCount)

        vm.respondToApproval(ApprovalChoice.ONCE)

        coVerify { api.respondApproval("s1", ApprovalChoice.ONCE, "ap1") }
        assertNull(vm.uiState.value.approvalPrompt)
        assertFalse(vm.uiState.value.isRespondingToApproval)
    }

    @Test
    fun `clarification pending sets the prompt and respond clears it`() = runTest(dispatcher) {
        coEvery { api.respondClarification(any(), any(), any()) } returns ClarificationRespondResponse(ok = true)
        val vm = startedViewModel()

        events.emit(
            ChatStreamEvent.ClarificationPending(
                ClarificationPendingResponse(
                    pending = PendingClarification(clarifyId = "c1", question = "Which color?"),
                    pendingCount = 1,
                ),
            ),
        )

        assertNotNull(vm.uiState.value.clarificationPrompt)

        vm.respondToClarification("blue")

        coVerify { api.respondClarification("s1", "blue", "c1") }
        assertNull(vm.uiState.value.clarificationPrompt)
    }

    @Test
    fun `stream end finalizes the run and caches the transcript`() = runTest(dispatcher) {
        val vm = startedViewModel()

        events.emit(ChatStreamEvent.Token("Hi"))
        advanceTimeBy(20)
        events.emit(
            ChatStreamEvent.Done(
                DoneStreamEvent(session = SessionDetail(sessionId = "s1", messages = serverTranscript())),
            ),
        )
        events.emit(ChatStreamEvent.StreamEnd)

        val state = vm.uiState.value
        assertNull(state.activeStreamId)
        assertNull(state.streamingAssistantMessageId)
        assertEquals(1, state.responseCompletionTrigger)

        val cached = cacheStore.cachedMessages(serverUrl, "s1")
        assertTrue(cached.isNotEmpty())
        assertEquals(state.messages.map { it.messageId }, cached.map { it.messageId })
    }

    @Test
    fun `cancelled event finalizes without an error`() = runTest(dispatcher) {
        val vm = startedViewModel()

        events.emit(ChatStreamEvent.Cancelled)

        val state = vm.uiState.value
        assertNull(state.activeStreamId)
        assertNull(state.sendErrorMessage)
        assertNull(state.streamingAssistantMessageId)
    }

    @Test
    fun `error event surfaces an inline send error`() = runTest(dispatcher) {
        val vm = startedViewModel()

        events.emit(ChatStreamEvent.Error("model exploded"))

        assertEquals("model exploded", vm.uiState.value.sendErrorMessage)
        assertNull(vm.uiState.value.activeStreamId)
    }

    @Test
    fun `transport error reconnects via status check without resending the message`() = runTest(dispatcher) {
        coEvery { api.chatStreamStatus("stream-1") } returns ChatStreamStatusResponse(
            active = false,
            replayAvailable = true,
        )
        val vm = startedViewModel()

        events.emit(ChatStreamEvent.TransportError("connection reset"))

        coVerify(exactly = 1) { api.chatStreamStatus("stream-1") }
        // Reconnected with replay (never re-sending the user message).
        verify { sse.connect("stream-1", null) }
        verify { sse.connect("stream-1", 0) }
        coVerify(exactly = 1) { api.startChat(any(), any(), any(), any(), any(), any(), any(), any()) }
        assertEquals("stream-1", vm.uiState.value.activeStreamId)
        assertEquals(ActiveStreamRecoveryState.IDLE, vm.uiState.value.recoveryState)
    }

    @Test
    fun `replayed tokens after a reconnect are deduplicated`() = runTest(dispatcher) {
        coEvery { api.chatStreamStatus("stream-1") } returns ChatStreamStatusResponse(
            active = false,
            replayAvailable = true,
        )
        val vm = startedViewModel()

        events.emit(ChatStreamEvent.Token("Hello"))
        advanceTimeBy(20)
        events.emit(ChatStreamEvent.TransportError("connection reset"))

        // The replay re-delivers the already-shown prefix, then continues live.
        events.emit(ChatStreamEvent.Token("Hello"))
        events.emit(ChatStreamEvent.Token(" world"))
        advanceTimeBy(20)

        val assistant = vm.uiState.value.messages.last()
        assertEquals("Hello world", assistant.content)
    }

    // MARK: - Send + steer

    @Test
    fun `send while streaming steers instead of starting a new chat`() = runTest(dispatcher) {
        coEvery { api.steerChat(any(), any()) } returns ChatSteerResponse(accepted = true)
        val vm = startedViewModel()

        vm.updateDraft("go left")
        vm.send()

        coVerify(exactly = 1) { api.startChat(any(), any(), any(), any(), any(), any(), any(), any()) }
        coVerify { api.steerChat("s1", "go left") }
        assertEquals("", vm.uiState.value.draft)
        assertTrue(vm.uiState.value.pinnedLocalNotices.contains("Steering hint delivered."))
    }

    @Test
    fun `rejected steer queues the message and drains it after the stop`() = runTest(dispatcher) {
        coEvery { api.steerChat(any(), any()) } returns ChatSteerResponse(accepted = false)
        val vm = startedViewModel()

        vm.updateDraft("go left")
        vm.send()

        // cancel → finishStream → queued message drains as a fresh send.
        coVerify { api.cancelChat("stream-1") }
        assertEquals(listOf("hello", "go left"), sentMessages)
        assertEquals("stream-1", vm.uiState.value.activeStreamId)
    }

    @Test
    fun `send failure rolls back the optimistic message and restores the draft`() = runTest(dispatcher) {
        coEvery {
            api.startChat(any(), any(), any(), any(), any(), any(), any(), any())
        } throws ApiError.Http(500, "{}")

        val vm = viewModel()
        vm.switchToSession("s1", newChat = false)
        vm.updateDraft("hi there")
        vm.send()

        val state = vm.uiState.value
        assertEquals("hi there", state.draft)
        assertNotNull(state.sendErrorMessage)
        assertFalse(state.messages.any { it.role == "user" && it.content == "hi there" })
        assertNull(state.activeStreamId)
    }

    @Test
    fun `attachments ride the send as payloads and display marker text`() = runTest(dispatcher) {
        coEvery { api.uploadFile(any(), any(), any()) } returns UploadResponse(
            filename = "a.png",
            path = "/up/a.png",
            size = 1,
            mime = "image/png",
            isImage = true,
        )

        val vm = viewModel()
        vm.switchToSession("s1", newChat = false)
        vm.uploadAttachment(byteArrayOf(1), "a.png")
        assertEquals(1, vm.uiState.value.pendingAttachments.size)

        vm.updateDraft("look at this")
        vm.send()

        assertEquals("look at this\n\n[Attached files: /up/a.png]", sentMessages.single())
        assertEquals(1, capturedAttachments?.size)
        assertTrue(vm.uiState.value.pendingAttachments.isEmpty())

        // The optimistic bubble shows the bare draft plus structured attachments.
        val optimistic = vm.uiState.value.messages.first { it.role == "user" }
        assertEquals("look at this", optimistic.content)
        assertEquals("/up/a.png", optimistic.attachments?.single()?.path)
    }

    @Test
    fun `shared text merges once and shared file stages before acknowledgement`() = runTest(dispatcher) {
        coEvery { api.uploadFile("s1", any(), "report.txt") } returns UploadResponse(
            filename = "report.txt",
            path = "/up/report.txt",
            size = 6,
            mime = "text/plain",
            isImage = false,
        )
        val file = File(directory, "report.txt").apply { writeText("report") }
        val shared = SharedImport(
            id = "share-1",
            text = "Review this",
            attachments = listOf(SharedImportAttachment("report.txt", "text/plain", 6, file)),
            warnings = emptyList(),
        )
        val vm = viewModel()
        vm.switchToSession("s1", newChat = false)
        vm.updateDraft("Existing")

        assertTrue(vm.applySharedImport(shared))
        assertEquals("Existing\n\nReview this\n", vm.uiState.value.draft)
        assertEquals("/up/report.txt", vm.uiState.value.pendingAttachments.single().path)

        assertTrue(vm.applySharedImport(shared))
        assertEquals("Existing\n\nReview this\n", vm.uiState.value.draft)
        coVerify(exactly = 1) { api.uploadFile("s1", any(), "report.txt") }
    }

    @Test
    fun `voice note stages audio and appends server transcript`() = runTest(dispatcher) {
        coEvery { api.uploadFile("s1", any(), "voice-note.m4a") } returns UploadResponse(
            filename = "voice-note.m4a", path = "/up/voice-note.m4a", size = 5,
            mime = "audio/mp4", isImage = false,
        )
        coEvery { api.transcribeAudio(any(), "voice-note.m4a") } returns TranscribeResponse(
            ok = true, transcript = "Ship the Type-J build",
        )
        val vm = viewModel()
        vm.switchToSession("s1", newChat = false)
        vm.updateDraft("Context")
        vm.importVoiceNote(byteArrayOf(1, 2, 3), "voice-note.m4a")
        advanceUntilIdle()

        assertEquals("Context\n\nShip the Type-J build", vm.uiState.value.draft)
        assertEquals("/up/voice-note.m4a", vm.uiState.value.pendingAttachments.single().path)
        assertFalse(vm.uiState.value.isProcessingVoiceNote)
    }

    @Test
    fun `transcription failure keeps staged audio and surfaces precise error`() = runTest(dispatcher) {
        coEvery { api.uploadFile("s1", any(), "voice-note.m4a") } returns UploadResponse(
            filename = "voice-note.m4a", path = "/up/voice-note.m4a", size = 5,
            mime = "audio/mp4", isImage = false,
        )
        coEvery { api.transcribeAudio(any(), "voice-note.m4a") } returns TranscribeResponse(
            ok = false, error = "STT is not configured",
        )
        val vm = viewModel()
        vm.switchToSession("s1", newChat = false)
        vm.importVoiceNote(byteArrayOf(1), "voice-note.m4a")
        advanceUntilIdle()

        assertEquals("/up/voice-note.m4a", vm.uiState.value.pendingAttachments.single().path)
        assertTrue(vm.uiState.value.uploadAttachmentErrorMessage!!.contains("STT is not configured"))
        assertFalse(vm.uiState.value.isProcessingVoiceNote)
    }
    @Test
    fun `new chat mode creates the session lazily on first send`() = runTest(dispatcher) {
        coEvery { api.createSession(any(), any(), any(), any()) } returns SessionResponse(
            SessionDetail(sessionId = "new-1", title = "Fresh"),
        )

        val vm = viewModel(sessionId = null, newChat = true)
        vm.switchToSession(null, newChat = true)
        assertNull(vm.uiState.value.sessionId)

        vm.updateDraft("hi")
        vm.send()

        coVerify(exactly = 1) { api.createSession(any(), any(), any(), any()) }
        coVerify { api.startChat("new-1", "hi", any(), any(), any(), any(), any(), any()) }
        assertEquals("new-1", vm.uiState.value.sessionId)
        assertEquals("stream-1", vm.uiState.value.activeStreamId)
    }

    // MARK: - Slash commands

    @Test
    fun `unknown slash text falls through and is sent as a message`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.switchToSession("s1", newChat = false)

        vm.updateDraft("/zzz do it")
        vm.send()

        assertEquals(listOf("/zzz do it"), sentMessages)
    }

    @Test
    fun `known unsupported command surfaces a friendly error and never sends`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.switchToSession("s1", newChat = false)

        vm.updateDraft("/terminal top")
        vm.send()

        assertEquals(
            "Terminal is not available in the mobile app.",
            vm.uiState.value.sendErrorMessage,
        )
        assertEquals("", vm.uiState.value.draft)
        assertTrue(sentMessages.isEmpty())
    }

    @Test
    fun `bare slash asks for a sub argument and keeps the draft`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.switchToSession("s1", newChat = false)

        vm.updateDraft("/")
        vm.send()

        assertEquals("Choose a slash command or continue typing.", vm.uiState.value.sendErrorMessage)
        assertEquals("/", vm.uiState.value.draft)
        assertTrue(sentMessages.isEmpty())
    }

    @Test
    fun `slash clear empties the transcript`() = runTest(dispatcher) {
        coEvery { api.session(any(), any(), any(), any(), any()) } returns SessionResponse(
            SessionDetail(sessionId = "s1", title = "T", messages = serverTranscript()),
        )
        val vm = viewModel()
        vm.switchToSession("s1", newChat = false)
        assertEquals(2, vm.uiState.value.messages.size)

        vm.updateDraft("/clear")
        vm.send()

        assertTrue(vm.uiState.value.messages.isEmpty())
        assertTrue(vm.uiState.value.transcriptMessages.isEmpty())
        assertEquals("", vm.uiState.value.draft)
    }

    @Test
    fun `slash stop cancels the active stream`() = runTest(dispatcher) {
        val vm = startedViewModel()

        vm.updateDraft("/stop")
        vm.send()

        coVerify { api.cancelChat("stream-1") }
        assertNull(vm.uiState.value.activeStreamId)
    }

    @Test
    fun `slash queue while streaming queues and drains on stream end`() = runTest(dispatcher) {
        val vm = startedViewModel()

        vm.updateDraft("/queue later message")
        vm.send()

        // The notice is pinned while the stream runs...
        assertTrue(vm.uiState.value.pinnedLocalNotices.contains("Queued for next turn (#1)."))
        assertEquals(listOf("hello"), sentMessages)

        events.emit(ChatStreamEvent.StreamEnd)

        // ...then flushed to the transcript, and the queued message sends.
        assertEquals(listOf("hello", "later message"), sentMessages)
        assertTrue(
            vm.uiState.value.messages.any {
                it.role == "local_notice" && it.content == "Queued for next turn (#1)."
            },
        )
    }

    @Test
    fun `slash help renders a local assistant message`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.switchToSession("s1", newChat = false)

        vm.updateDraft("/help")
        vm.send()

        val help = vm.uiState.value.messages.last()
        assertEquals("local_assistant", help.role)
        assertTrue(help.content!!.contains("`/steer <message>`"))
    }
}
