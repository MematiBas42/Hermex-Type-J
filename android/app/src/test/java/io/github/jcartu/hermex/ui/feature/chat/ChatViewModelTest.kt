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
    private lateinit var draftStore: io.github.jcartu.hermex.data.ChatDraftStore
    private lateinit var snapshots: io.github.jcartu.hermex.data.ActiveChatStreamSnapshotStore
    private lateinit var storeScope: kotlinx.coroutines.CoroutineScope
    private val viewModels = androidx.lifecycle.ViewModelStore()
    private var viewModelCount = 0
    @get:org.junit.Rule
    val timeout: org.junit.rules.Timeout = org.junit.rules.Timeout.seconds(15)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        directory = Files.createTempDirectory("chat-vm-test").toFile()
        cacheStore = CacheStore(directory, json)
        storeScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + dispatcher)
        snapshots = io.github.jcartu.hermex.data.ActiveChatStreamSnapshotStore(directory, json, storeScope)
        draftStore = io.github.jcartu.hermex.data.ChatDraftStore(
            io.github.jcartu.hermex.data.ChatDraftFilePersistence(directory, json, dispatcher),
            io.github.jcartu.hermex.data.ChatDraftAttachmentStore(directory), storeScope, ioDispatcher = dispatcher,
        )
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
        every { sse.connectApprovalStream(any()) } returns MutableSharedFlow(extraBufferCapacity = 16)
        every { sse.connectClarifyStream(any()) } returns MutableSharedFlow(extraBufferCapacity = 16)
        coEvery { api.sessionYolo(any()) } returns io.github.jcartu.hermex.model.SessionYoloResponse(yoloEnabled = false)

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
        viewModels.clear()
        storeScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        Dispatchers.resetMain()
        directory.deleteRecursively()
    }

    private fun viewModel(sessionId: String? = "s1", newChat: Boolean = false,
                          network: io.github.jcartu.hermex.net.NetworkAvailability? = null,
                          onStarted: suspend () -> Unit = {}): ChatViewModel =
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
            drafts = draftStore,
            network = network,
            streamSnapshots = snapshots,
            attachmentPreview = { _, _, _, _, _ -> byteArrayOf(9, 8, 7) },
            onRunStarted = onStarted,
        ).also { viewModels.put("vm-${viewModelCount++}", it) }

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
    fun `switchToSession stores composer under its original session`() = runTest(dispatcher) {
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
        assertEquals("", state.draft)
        assertTrue(state.pendingAttachments.isEmpty())
        vm.switchToSession("s1", newChat = false)
        assertEquals("keep me", vm.uiState.value.draft)
        assertEquals(listOf("/up/a.png"), vm.uiState.value.pendingAttachments.map { it.path })
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
        assertEquals("Steering hint delivered.", vm.uiState.value.steeringConfirmation)
        assertTrue(vm.uiState.value.messages.last().isSteerMessage)
    }

    @Test
    fun `rejected steer keeps draft and does not stop active run`() = runTest(dispatcher) {
        coEvery { api.steerChat(any(), any()) } returns ChatSteerResponse(accepted = false)
        val vm = startedViewModel()
        vm.updateDraft("go left")
        vm.send()
        coVerify(exactly = 0) { api.cancelChat(any()) }
        assertEquals(listOf("hello"), sentMessages)
        assertEquals("stream-1", vm.uiState.value.activeStreamId)
        assertEquals("go left", vm.uiState.value.draft)
        assertEquals("Couldn't steer", vm.uiState.value.steerFailureMessage)
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
        assertEquals("/terminal top", vm.uiState.value.draft)
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
    fun `slash clear confirms before clearing server and cache`() = runTest(dispatcher) {
        coEvery { api.session(any(), any(), any(), any(), any()) } returns SessionResponse(
            SessionDetail(sessionId = "s1", title = "T", messages = serverTranscript()),
        )
        coEvery { api.clearSession("s1") } returns io.github.jcartu.hermex.model.SessionMutationResponse(ok = true)
        val vm = viewModel()
        vm.switchToSession("s1", newChat = false)
        assertEquals(2, vm.uiState.value.messages.size)

        vm.updateDraft("/clear")
        vm.send()
        assertTrue(vm.uiState.value.pendingClearConfirmation)
        coVerify(exactly = 0) { api.clearSession(any()) }
        vm.confirmClearSessionHistory()

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

        assertEquals("Queued, sends when this run finishes", vm.uiState.value.queuedMessagesReceipt)
        assertEquals(listOf("hello"), sentMessages)

        events.emit(ChatStreamEvent.StreamEnd)

        assertEquals(listOf("hello", "later message"), sentMessages)
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

    @Test
    fun `live reconnect uses last journal sequence without a new POST`() = runTest(dispatcher) {
        val vm = startedViewModel()
        every { sse.lastEventId } returns "run:27"
        coEvery { api.chatStreamStatus("stream-1") } returns ChatStreamStatusResponse(active = true, replayAvailable = true)
        coEvery { api.session(any(), any(), any(), any(), any()) } returns SessionResponse(
            SessionDetail(sessionId = "s1", activeStreamId = "stream-1", messages = serverTranscript()),
        )
        events.emit(ChatStreamEvent.TransportError("drop"))
        verify(exactly = 1) { sse.connect("stream-1", 27) }
        assertEquals(listOf("hello"), sentMessages)
        vm.suspendStreamForBackground()
    }

    @Test
    fun `late events cannot reopen a completed response`() = runTest(dispatcher) {
        val vm = startedViewModel()
        events.emit(ChatStreamEvent.Done(DoneStreamEvent(session = SessionDetail(sessionId = "s1", messages = serverTranscript()))))
        events.emit(ChatStreamEvent.Token("late before stream end"))
        events.emit(ChatStreamEvent.StreamEnd)
        events.emit(ChatStreamEvent.Token("late after stream end"))
        events.emit(ChatStreamEvent.Error("late failure"))
        advanceTimeBy(20)
        assertEquals("Hi!", vm.uiState.value.messages.last().content)
        assertEquals(1, vm.uiState.value.responseCompletionTrigger)
        assertNull(vm.uiState.value.sendErrorMessage)
    }

    @Test
    fun `offline recovery waits then resumes on network callback`() = runTest(dispatcher) {
        val signal = kotlinx.coroutines.flow.MutableStateFlow(0)
        var online = false
        val network = object : io.github.jcartu.hermex.net.NetworkAvailability {
            override val isSatisfied: Boolean get() = online
            override val changes: kotlinx.coroutines.flow.StateFlow<Int> = signal
        }
        val vm = viewModel(network = network)
        vm.switchToSession("s1", false)
        vm.resumeForForeground()
        vm.updateDraft("hello")
        vm.send()
        events.emit(ChatStreamEvent.TransportError("offline"))
        assertEquals(ActiveStreamRecoveryState.WAITING_FOR_NETWORK, vm.uiState.value.recoveryState)
        coVerify(exactly = 0) { api.chatStreamStatus(any()) }
        coEvery { api.chatStreamStatus(any()) } returns ChatStreamStatusResponse(active = false, replayAvailable = true)
        online = true
        signal.value += 1
        coVerify(exactly = 1) { api.chatStreamStatus("stream-1") }
        verify { sse.connect("stream-1", 0) }
        vm.suspendStreamForBackground()
    }

    @Test
    fun `queued message survives ViewModel clear and reattach as a draft`() = runTest(dispatcher) {
        val vm = startedViewModel()
        vm.updateDraft("review this next")
        vm.send(SendMode.QUEUE)
        val owner = androidx.lifecycle.ViewModelStore()
        owner.put("chat", vm)
        owner.clear()
        draftStore.flush()
        val reopened = viewModel()
        reopened.switchToSession("s1", false)
        assertEquals("review this next", reopened.uiState.value.draft)
        assertEquals(listOf("hello"), sentMessages)
    }

    @Test
    fun `session rotation moves rejected draft without resending`() = runTest(dispatcher) {
        coEvery { api.startChat(any(), any(), any(), any(), any(), any(), any(), any()) } throws
            ApiError.Http(409, """{"code":"session_rotated","continuation_session_id":"s2","error":"sealed"}""")
        val vm = viewModel()
        vm.switchToSession("s1", false)
        vm.updateDraft("review before resend")
        vm.send()
        assertEquals("s2", vm.uiState.value.sessionId)
        assertEquals("review before resend", vm.uiState.value.draft)
        assertFalse(vm.uiState.value.messages.any { it.role == "user" })
        coVerify(exactly = 1) { api.startChat(any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `invalid rotation target keeps source draft and seals POST`() = runTest(dispatcher) {
        coEvery { api.startChat(any(), any(), any(), any(), any(), any(), any(), any()) } throws
            ApiError.Http(409, """{"code":"session_rotated","continuation_session_id":"../bad","error":"sealed"}""")
        val vm = viewModel()
        vm.switchToSession("s1", false)
        vm.updateDraft("keep me")
        vm.send()
        vm.send()
        assertEquals("s1", vm.uiState.value.sessionId)
        assertEquals("keep me", vm.uiState.value.draft)
        assertTrue(vm.uiState.value.isCompressionRotated)
        coVerify(exactly = 1) { api.startChat(any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `rotation never overwrites nonempty destination draft`() = runTest(dispatcher) {
        draftStore.setText("other work", io.github.jcartu.hermex.data.ChatDraftKey(serverUrl, "s2"))
        coEvery { api.startChat(any(), any(), any(), any(), any(), any(), any(), any()) } throws
            ApiError.Http(409, """{"code":"session_rotated","continuation_session_id":"s2","error":"sealed"}""")
        val vm = viewModel()
        vm.switchToSession("s1", false)
        vm.updateDraft("rejected work")
        vm.send()
        assertEquals("s1", vm.uiState.value.sessionId)
        assertEquals("rejected work", vm.uiState.value.draft)
        assertEquals("other work", draftStore.draft(io.github.jcartu.hermex.data.ChatDraftKey(serverUrl, "s2"))?.text)
    }

    @Test
    fun `process recreation restores live reasoning tools and assistant prefix then resumes cursor`() = runTest(dispatcher) {
        val vm = startedViewModel()
        every { sse.lastEventId } returns "run:27"
        events.emit(ChatStreamEvent.Token("Hello world"))
        events.emit(ChatStreamEvent.Reasoning("Still thinking"))
        events.emit(ChatStreamEvent.ToolStarted(ToolStreamEvent(name = "bash", stableId = "tool-1", preview = "pwd")))
        vm.pinLocalNoticeMessage("Keep notice")
        vm.suspendStreamForBackground()
        viewModels.clear()
        snapshots.flush()
        snapshots = io.github.jcartu.hermex.data.ActiveChatStreamSnapshotStore(directory, json, storeScope)
        coEvery { api.chatStreamStatus("stream-1") } returns ChatStreamStatusResponse(active = true, replayAvailable = true)
        coEvery { api.session(any(), any(), any(), any(), any()) } returns SessionResponse(
            SessionDetail(sessionId = "s1", activeStreamId = "stream-1", messages = listOf(
                ChatMessage(role = "user", content = "hello", messageId = "u"),
                ChatMessage(role = "assistant", content = "Hello", messageId = "server-assistant"),
            )))
        val restored = viewModel()
        restored.switchToSession("s1", false)
        assertEquals("Hello world", restored.uiState.value.messages.last { it.role == "assistant" }.content)
        assertEquals("Still thinking", restored.uiState.value.liveReasoningText)
        assertEquals("tool-1", restored.uiState.value.liveToolCalls.single().id)
        assertFalse(restored.uiState.value.liveToolCalls.single().isCompleted)
        assertEquals("server-assistant", restored.uiState.value.streamingAssistantMessageId)
        assertEquals(listOf("Keep notice"), restored.uiState.value.pinnedLocalNotices)
        verify { sse.connect("stream-1", 27) }
        coVerify(exactly = 1) { api.startChat(any(), any(), any(), any(), any(), any(), any(), any()) }
        events.emit(ChatStreamEvent.Cancelled)
        assertNull(snapshots.snapshot(serverUrl, "s1", "stream-1"))
    }

    @Test
    fun `share staging failure on second copy leaves original draft and no partial attachment`() = runTest(dispatcher) {
        val actual = io.github.jcartu.hermex.data.ChatDraftAttachmentStore(directory)
        var copies = 0
        val failing = object : io.github.jcartu.hermex.data.ChatDraftAttachmentStoring by actual {
            override fun save(data: ByteArray, suggestedFilename: String): String {
                if (++copies == 2) throw java.io.IOException("disk full")
                return actual.save(data, suggestedFilename)
            }
        }
        draftStore = io.github.jcartu.hermex.data.ChatDraftStore(
            io.github.jcartu.hermex.data.ChatDraftFilePersistence(directory, json, dispatcher), failing, storeScope, dispatcher)
        val shared = SharedImport("transaction", "Incoming", listOf("one.txt", "two.txt").map {
            SharedImportAttachment(it, "text/plain", 4, File(directory, it).apply { writeText("text") })
        }, emptyList())
        val vm = viewModel()
        vm.switchToSession("s1", false)
        vm.updateDraft("Original")
        assertFalse(vm.applySharedImport(shared))
        assertEquals("Original", vm.uiState.value.draft)
        assertTrue(vm.uiState.value.pendingAttachments.isEmpty())
        val saved = draftStore.draft(io.github.jcartu.hermex.data.ChatDraftKey(serverUrl, "s1"))!!
        assertEquals("Original", saved.text)
        assertTrue(saved.attachments.isEmpty())
        assertTrue(saved.sharedImportIds.isEmpty())
        assertTrue(actual.retainedFileBytes().isEmpty())
        coVerify(exactly = 0) { api.uploadFile(any(), any(), any()) }
    }

    @Test
    fun `document restore and shared import both regenerate composer thumbnails`() = runTest(dispatcher) {
        coEvery { api.uploadFile(any(), any(), any()) } answers {
            UploadResponse(path = "/up/${arg<String>(2)}", mime = "text/plain", isImage = false, size = 4)
        }
        val vm = viewModel()
        vm.switchToSession("s1", false)
        vm.uploadAttachment("text".toByteArray(), "saved.txt")
        vm.switchToSession("s2", false)
        vm.switchToSession("s1", false)
        assertTrue(vm.uiState.value.pendingAttachments.single().thumbnailData!!.contentEquals(byteArrayOf(9, 8, 7)))
        val shared = SharedImport("preview", "", listOf(SharedImportAttachment("shared.txt", "text/plain", 4,
            File(directory, "shared.txt").apply { writeText("text") })), emptyList())
        assertTrue(vm.applySharedImport(shared))
        assertEquals(2, vm.uiState.value.pendingAttachments.size)
        assertTrue(vm.uiState.value.pendingAttachments.all { it.thumbnailData!!.contentEquals(byteArrayOf(9, 8, 7)) })
    }

    @Test
    fun `late rotation after navigating away retains attachments only in source draft`() = runTest(dispatcher) {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        coEvery { api.uploadFile(any(), any(), any()) } returns UploadResponse(path = "/up/report.txt", mime = "text/plain", isImage = false, size = 4)
        coEvery { api.startChat(any(), any(), any(), any(), any(), any(), any(), any()) } coAnswers {
            gate.await()
            throw ApiError.Http(409, """{"code":"session_rotated","continuation_session_id":"s2"}""")
        }
        val vm = viewModel()
        vm.switchToSession("s1", false)
        vm.updateDraft("Rejected work")
        vm.uploadAttachment("text".toByteArray(), "report.txt")
        vm.send()
        vm.switchToSession("s3", false)
        vm.updateDraft("New work")
        gate.complete(Unit)
        assertEquals("s3", vm.uiState.value.sessionId)
        assertEquals("New work", vm.uiState.value.draft)
        assertTrue(vm.uiState.value.pendingAttachments.isEmpty())
        val source = draftStore.draft(io.github.jcartu.hermex.data.ChatDraftKey(serverUrl, "s1"))!!
        assertEquals("Rejected work", source.text)
        assertEquals("report.txt", source.attachments.single().name)
        assertEquals("text", draftStore.attachmentData(source.attachments.single().file!!).decodeToString())
        assertNull(draftStore.draft(io.github.jcartu.hermex.data.ChatDraftKey(serverUrl, "s2")))
    }

    @Test
    fun `cancelled continuation lookup retains rejected attachment copies through recreation`() = runTest(dispatcher) {
        val target = kotlinx.coroutines.CompletableDeferred<SessionResponse>()
        coEvery { api.uploadFile(any(), any(), any()) } returns UploadResponse(path = "/up/report.txt", mime = "text/plain", isImage = false, size = 4)
        coEvery { api.startChat(any(), any(), any(), any(), any(), any(), any(), any()) } throws
            ApiError.Http(409, """{"code":"session_rotated","continuation_session_id":"s2"}""")
        coEvery { api.session("s2", false, null, any(), any()) } coAnswers { target.await() }
        val vm = viewModel()
        vm.switchToSession("s1", false)
        vm.updateDraft("Rejected work")
        vm.uploadAttachment("text".toByteArray(), "report.txt")
        vm.send()
        viewModels.clear()
        draftStore.flush()
        val disk = io.github.jcartu.hermex.data.ChatDraftFilePersistence(directory, json, dispatcher).load()
        val source = disk[io.github.jcartu.hermex.data.ChatDraftKey(serverUrl, "s1")]!!
        assertEquals("Rejected work", source.text)
        assertEquals("text", draftStore.attachmentData(source.attachments.single().file!!).decodeToString())
        assertNull(disk[io.github.jcartu.hermex.data.ChatDraftKey(serverUrl, "s2")])
    }

    @Test
    fun `rotation Profile mismatch keeps original text quotes and attachments`() = runTest(dispatcher) {
        coEvery { api.session(any(), any(), any(), any(), any()) } answers {
            SessionResponse(SessionDetail(sessionId = firstArg(), profile = if (firstArg<String>() == "s1") "work" else "other"))
        }
        coEvery { api.uploadFile(any(), any(), any()) } returns UploadResponse(path = "/up/report.txt", mime = "text/plain", isImage = false, size = 4)
        coEvery { api.startChat(any(), any(), any(), any(), any(), any(), any(), any()) } throws
            ApiError.Http(409, """{"code":"session_rotated","continuation_session_id":"s2"}""")
        val vm = viewModel()
        vm.switchToSession("s1", false)
        vm.updateDraft("Keep here")
        vm.askAboutSelection("Quoted passage")
        vm.uploadAttachment("text".toByteArray(), "report.txt")
        vm.send()
        assertEquals("s1", vm.uiState.value.sessionId)
        assertEquals("Keep here", vm.uiState.value.draft)
        assertEquals("Quoted passage", vm.uiState.value.draftQuotes.single().text)
        assertEquals("report.txt", vm.uiState.value.pendingAttachments.single().name)
        assertNull(vm.uiState.value.compressionContinuation)
        assertTrue(vm.uiState.value.isCompressionRotated)
        assertNull(draftStore.draft(io.github.jcartu.hermex.data.ChatDraftKey(serverUrl, "s2")))
    }

    @Test
    fun `accepted run invokes first-run notification hook but rejected send never does`() = runTest(dispatcher) {
        var offers = 0
        val vm = viewModel(onStarted = { offers++ })
        vm.switchToSession("s1", false)
        vm.updateDraft("hello")
        vm.send()
        assertEquals(1, offers)
        events.emit(ChatStreamEvent.Cancelled)
        coEvery { api.startChat(any(), any(), any(), any(), any(), any(), any(), any()) } throws ApiError.Http(500, "failed")
        vm.updateDraft("again")
        vm.send()
        assertEquals(1, offers)
    }
}
