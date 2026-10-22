package io.github.jcartu.hermex.ui.feature.chat

import androidx.lifecycle.ViewModelStore
import io.github.jcartu.hermex.core.prefs.ServerConfig
import io.github.jcartu.hermex.core.prefs.ServerConfigRepository
import io.github.jcartu.hermex.core.prefs.SettingsRepository
import io.github.jcartu.hermex.data.ActiveChatStreamSnapshotStore
import io.github.jcartu.hermex.data.CacheStore
import io.github.jcartu.hermex.data.ChatDraftAttachmentStore
import io.github.jcartu.hermex.data.ChatDraftFilePersistence
import io.github.jcartu.hermex.data.ChatDraftStore
import io.github.jcartu.hermex.model.ChatMessage
import io.github.jcartu.hermex.model.ChatStartResponse
import io.github.jcartu.hermex.model.ChatStreamStatusResponse
import io.github.jcartu.hermex.model.CommandsResponse
import io.github.jcartu.hermex.model.ModelFavoritesStore
import io.github.jcartu.hermex.model.ModelRecentsStore
import io.github.jcartu.hermex.model.ModelsResponse
import io.github.jcartu.hermex.model.ProfilesResponse
import io.github.jcartu.hermex.model.ReasoningStatusResponse
import io.github.jcartu.hermex.model.SessionDetail
import io.github.jcartu.hermex.model.SessionResponse
import io.github.jcartu.hermex.model.SkillsResponse
import io.github.jcartu.hermex.model.WorkspacesResponse
import io.github.jcartu.hermex.net.ChatStreamEvent
import io.github.jcartu.hermex.net.HermesApi
import io.github.jcartu.hermex.net.SseChatStream
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatStreamReplaySuffixTest {
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
    private lateinit var draftStore: ChatDraftStore
    private lateinit var snapshots: ActiveChatStreamSnapshotStore
    private lateinit var storeScope: kotlinx.coroutines.CoroutineScope
    private lateinit var api: HermesApi
    private lateinit var sse: SseChatStream
    private lateinit var serverConfig: ServerConfigRepository
    private lateinit var settings: SettingsRepository
    private lateinit var favorites: ModelFavoritesStore
    private lateinit var recents: ModelRecentsStore
    private lateinit var events: MutableSharedFlow<ChatStreamEvent>
    private val viewModels = ViewModelStore()

    private var lastEventId: String? = null
    private var loadedActiveStreamId: String? = null
    private var loadedMessages: List<ChatMessage> = emptyList()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        directory = Files.createTempDirectory("chat-replay-suffix-test").toFile()
        cacheStore = CacheStore(directory, json)
        storeScope = kotlinx.coroutines.CoroutineScope(SupervisorJob() + dispatcher)
        draftStore = ChatDraftStore(
            ChatDraftFilePersistence(directory, json, dispatcher),
            ChatDraftAttachmentStore(directory),
            storeScope,
            ioDispatcher = dispatcher,
        )
        snapshots = ActiveChatStreamSnapshotStore(directory, json, storeScope)
        api = mockk()
        sse = mockk()
        serverConfig = mockk()
        settings = mockk()
        favorites = mockk()
        recents = mockk()
        events = MutableSharedFlow(extraBufferCapacity = 32)

        every { settings.settings } returns flowOf(SettingsRepository.Settings())
        every { favorites.favoriteKeys } returns flowOf(emptyList())
        every { recents.recentKeys } returns flowOf(emptyList())
        coEvery { serverConfig.current() } returns ServerConfig(serverUrl, "password")
        every { sse.lastEventId } answers { lastEventId }
        every { sse.connect(any(), any()) } returns events
        every { sse.connectApprovalStream(any()) } returns MutableSharedFlow(extraBufferCapacity = 4)
        every { sse.connectClarifyStream(any()) } returns MutableSharedFlow(extraBufferCapacity = 4)

        coEvery { api.session(any(), any(), any(), any(), any()) } answers {
            SessionResponse(
                SessionDetail(
                    sessionId = firstArg(),
                    title = "Replay test",
                    activeStreamId = loadedActiveStreamId,
                    messages = loadedMessages,
                ),
            )
        }
        coEvery { api.sessionYolo(any()) } returns io.github.jcartu.hermex.model.SessionYoloResponse(yoloEnabled = false)
        coEvery { api.profiles() } returns ProfilesResponse()
        coEvery { api.models() } returns ModelsResponse()
        coEvery { api.reasoning(any(), any()) } returns ReasoningStatusResponse()
        coEvery { api.workspaces() } returns WorkspacesResponse()
        coEvery { api.commands() } returns CommandsResponse()
        coEvery { api.skills() } returns SkillsResponse()
        coEvery { api.startChat(any(), any(), any(), any(), any(), any(), any(), any()) } returns
            ChatStartResponse(streamId = "stream-1", sessionId = "s1")
    }

    @After
    fun tearDown() {
        viewModels.clear()
        storeScope.coroutineContext[Job]?.cancel()
        Dispatchers.resetMain()
        directory.deleteRecursively()
    }

    @Test
    fun `trusted cursor appends a repeated newline suffix`() = runTest(dispatcher) {
        val viewModel = startedViewModel("abc\n")
        reconnectAfterTrustedCursor(viewModel)

        events.emit(ChatStreamEvent.Token("\n"))
        viewModel.flushPendingStreamingContent()

        assertEquals("abc\n\n", viewModel.uiState.value.messages.last().content)
    }

    @Test
    fun `trusted cursor appends a repeated word suffix`() = runTest(dispatcher) {
        val viewModel = startedViewModel("echo")
        reconnectAfterTrustedCursor(viewModel)

        events.emit(ChatStreamEvent.Token("echo"))
        viewModel.flushPendingStreamingContent()

        assertEquals("echoecho", viewModel.uiState.value.messages.last().content)
    }

    @Test
    fun `full replay still removes history before appending a repeated live word`() = runTest(dispatcher) {
        val viewModel = startedViewModel("echo")
        lastEventId = null
        coEvery { api.chatStreamStatus("stream-1") } returns
            ChatStreamStatusResponse(active = false, replayAvailable = true)

        events.emit(ChatStreamEvent.TransportError("connection reset"))
        verify(exactly = 1) { sse.connect("stream-1", 0) }

        events.emit(ChatStreamEvent.Token("echo"))
        events.emit(ChatStreamEvent.Token("echo"))
        viewModel.flushPendingStreamingContent()

        assertEquals("echoecho", viewModel.uiState.value.messages.last().content)
    }

    private suspend fun startedViewModel(prefix: String): ChatViewModel {
        val viewModel = ChatViewModel(
            api = api,
            sse = sse,
            cacheStore = cacheStore,
            serverConfig = serverConfig,
            settings = settings,
            favorites = favorites,
            recents = recents,
            initialSessionId = "s1",
            initialNewChat = false,
            ioDispatcher = dispatcher,
            drafts = draftStore,
            streamSnapshots = snapshots,
        )
        viewModels.put("vm", viewModel)
        viewModel.switchToSession("s1", newChat = false)
        viewModel.updateDraft("hello")
        viewModel.send()
        events.emit(ChatStreamEvent.Token(prefix))
        viewModel.flushPendingStreamingContent()
        assertEquals(prefix, viewModel.uiState.value.messages.last().content)
        return viewModel
    }

    private suspend fun reconnectAfterTrustedCursor(viewModel: ChatViewModel) {
        lastEventId = "run:27"
        loadedActiveStreamId = "stream-1"
        loadedMessages = viewModel.uiState.value.messages
        coEvery { api.chatStreamStatus("stream-1") } returns
            ChatStreamStatusResponse(active = true, replayAvailable = true)

        events.emit(ChatStreamEvent.TransportError("connection reset"))

        verify(exactly = 1) { sse.connect("stream-1", 27) }
        assertEquals("stream-1", viewModel.uiState.value.activeStreamId)
    }
}
