package io.github.jcartu.hermex.ui.feature.workspace.hermes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.jcartu.hermex.core.media.DocumentPreview
import io.github.jcartu.hermex.model.FileResponse
import io.github.jcartu.hermex.model.WorkspaceEntry
import io.github.jcartu.hermex.model.hermes.BotArtifactFailure
import io.github.jcartu.hermex.model.hermes.BotJSON
import io.github.jcartu.hermex.model.hermes.HermesWorkspaceFile
import io.github.jcartu.hermex.net.hermes.BotClient
import io.github.jcartu.hermex.net.hermes.HermesCall
import io.github.jcartu.hermex.net.hermes.HermesConnection
import io.github.jcartu.hermex.net.hermes.HermesFolderCompletion
import io.github.jcartu.hermex.net.hermes.HermesProjectTree
import io.github.jcartu.hermex.net.hermes.HermesWorkspaceContext
import io.github.jcartu.hermex.net.hermes.HermesWorkspaceFileClient
import io.github.jcartu.hermex.ui.feature.workspace.FilePreviewKind
import io.github.jcartu.hermex.ui.feature.workspace.FileTreeAction
import io.github.jcartu.hermex.ui.feature.workspace.LazyFileTree
import io.github.jcartu.hermex.ui.feature.workspace.MarkdownPreviewChunker
import io.github.jcartu.hermex.ui.feature.workspace.WorkspaceFilePreview
import io.github.jcartu.hermex.ui.feature.workspace.WorkspaceRootItem
import io.github.jcartu.hermex.ui.feature.workspace.WorkspaceUiState
import io.github.jcartu.hermex.ui.feature.workspace.mimeTypeForPath
import io.github.jcartu.hermex.ui.feature.workspace.previewKindFor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Direct-Hermes file/folder operations used by the shared Files composables. */
interface HermesWorkspaceDataSource {
    val context: HermesWorkspaceContext

    suspend fun directoryList(path: String): List<WorkspaceEntry>
    suspend fun file(path: String): HermesWorkspaceFile
    suspend fun previewData(path: String): ByteArray
    suspend fun folderChoices(): List<String>
    suspend fun completeFolder(word: String): HermesFolderCompletion.Suggestions
    suspend fun moveFolder(folder: String): HermesWorkspaceContext
}

/**
 * Production source over `HermesWorkspaceFileClient` and the gateway calls used by the
 * working-folder picker (#1112/#1117). A successful move rebuilds the fenced file client.
 */
class DefaultHermesWorkspaceDataSource(
    initialContext: HermesWorkspaceContext,
    private val http: HermesConnection,
) : HermesWorkspaceDataSource {
    override var context: HermesWorkspaceContext = initialContext
        private set
    private var files = HermesWorkspaceFileClient(context, http)

    override suspend fun directoryList(path: String): List<WorkspaceEntry> = files.directoryList(path)

    override suspend fun file(path: String): HermesWorkspaceFile = files.file(path)

    override suspend fun previewData(path: String): ByteArray = files.rawFilePreviewData(path)

    override suspend fun folderChoices(): List<String> = withClient { client ->
        coroutineScope {
            val tree = async {
                runCatching { client.call(HermesCall.ProjectsTree(context.profile)) }
                    .getOrNull()?.let(::HermesProjectTree)
            }
            val sessions = async {
                runCatching { client.sessionPage(context.profile, offset = 0, archived = false) }
                    .getOrNull()?.rows.orEmpty()
            }
            HermesFolderCompletion.choices(context.cwd, tree.await(), sessions.await(), context.profile)
        }
    }

    override suspend fun completeFolder(word: String): HermesFolderCompletion.Suggestions {
        if (!HermesFolderCompletion.completes(word)) return HermesFolderCompletion.Suggestions()
        return withClient { client ->
            val reply = client.call(HermesCall.CompleteFolder(word, context.profile))
            HermesFolderCompletion.suggestions(reply, word)
        }
    }

    override suspend fun moveFolder(folder: String): HermesWorkspaceContext = withClient { client ->
        val reply = client.call(
            HermesCall.SessionWorkspaceMove(
                profile = context.profile,
                storedKey = context.storedKey,
                cwd = folder,
            ),
        )
        val resolved = reply["cwd"].text?.takeIf(String::isNotBlank) ?: folder
        context = context.copy(cwd = resolved)
        files = HermesWorkspaceFileClient(context, http)
        context
    }

    private suspend fun <T> withClient(block: suspend (BotClient) -> T): T {
        val client = BotClient(http)
        return try {
            client.connect()
            block(client)
        } finally {
            client.close()
        }
    }
}

/** UI state for one Hermes session's current working folder. */
data class HermesWorkspaceUiState(
    val workspace: WorkspaceUiState,
    val context: HermesWorkspaceContext,
    val folderChoices: List<String> = emptyList(),
    val folderQuery: String = "",
    val folderSuggestions: List<String> = emptyList(),
    val needsMoreTyping: Boolean = false,
    val isLoadingFolders: Boolean = false,
    val isMovingFolder: Boolean = false,
    val folderError: String? = null,
)

/**
 * Port of `FileBrowserViewModel.swift` for `HermesWorkspaceFileClient`: lazy directory reads,
 * text/image/document previews, and the working-folder picker in one session scope.
 */
class HermesWorkspaceViewModel(
    private val source: HermesWorkspaceDataSource,
) : ViewModel() {
    private val _uiState = MutableStateFlow(initialState(source.context))
    val uiState: StateFlow<HermesWorkspaceUiState> = _uiState.asStateFlow()

    private val directoryGenerations = HashMap<String, Long>()
    private var previewGeneration = 0L

    fun load() {
        if (_uiState.value.workspace.tree.isRootLoaded) return
        loadDirectory(LazyFileTree.ROOT_PATH)
        loadFolderChoices()
    }

    fun refresh() {
        val expanded = _uiState.value.workspace.tree.expandedPaths.toList()
        loadDirectory(LazyFileTree.ROOT_PATH) {
            expanded.forEach(::loadDirectory)
        }
    }

    fun setSearchQuery(query: String) {
        _uiState.update { it.copy(workspace = it.workspace.copy(searchQuery = query)) }
    }

    fun toggleDirectory(path: String) {
        val before = _uiState.value.workspace.tree
        val tree = before.reduce(FileTreeAction.ToggleDirectory(path))
        _uiState.update { it.copy(workspace = it.workspace.copy(tree = tree)) }
        if (path in tree.expandedPaths && (!tree.isLoaded(path) || path in tree.failedPaths)) {
            loadDirectory(path)
        }
    }

    fun retryDirectory(path: String) = loadDirectory(path)

    fun openEntry(entry: WorkspaceEntry, line: Int? = null) {
        val path = entry.path?.takeIf(String::isNotBlank) ?: return
        if (entry.isBrowsableDirectory) {
            toggleDirectory(path)
            return
        }
        val generation = ++previewGeneration
        _uiState.update {
            it.copy(
                workspace = it.workspace.copy(
                    selectedEntry = entry,
                    selectedLine = line,
                    preview = null,
                    isLoadingPreview = true,
                ),
            )
        }
        viewModelScope.launch {
            val preview = try {
                loadPreview(path, entry)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: BotArtifactFailure.Folder) {
                WorkspaceFilePreview.Unavailable("Preview is not available for this file type.")
            } catch (error: Exception) {
                WorkspaceFilePreview.Failed(error.message ?: "Could not load file.")
            }
            if (generation != previewGeneration) return@launch
            _uiState.update {
                it.copy(workspace = it.workspace.copy(preview = preview, isLoadingPreview = false))
            }
        }
    }

    fun closePreview() {
        previewGeneration++
        _uiState.update {
            it.copy(
                workspace = it.workspace.copy(
                    selectedEntry = null,
                    selectedLine = null,
                    preview = null,
                    isLoadingPreview = false,
                ),
            )
        }
    }

    fun setFolderQuery(query: String) {
        _uiState.update {
            it.copy(
                folderQuery = query,
                folderSuggestions = emptyList(),
                needsMoreTyping = false,
                folderError = null,
            )
        }
        if (!HermesFolderCompletion.completes(query)) return
        viewModelScope.launch {
            try {
                val result = source.completeFolder(query)
                if (_uiState.value.folderQuery != query) return@launch
                _uiState.update {
                    it.copy(
                        folderSuggestions = result.folders,
                        needsMoreTyping = result.needsMoreTyping,
                    )
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                if (_uiState.value.folderQuery == query) {
                    _uiState.update { it.copy(folderError = error.message ?: "Could not complete this folder.") }
                }
            }
        }
    }

    fun moveFolder(folder: String) {
        val normalized = folder.trim().trimEnd('/').takeIf(String::isNotEmpty) ?: return
        _uiState.update { it.copy(isMovingFolder = true, folderError = null) }
        viewModelScope.launch {
            try {
                val context = source.moveFolder(normalized)
                directoryGenerations.clear()
                previewGeneration++
                _uiState.value = initialState(context).copy(
                    folderChoices = (_uiState.value.folderChoices + context.cwd).distinct(),
                    folderQuery = context.cwd,
                )
                loadDirectory(LazyFileTree.ROOT_PATH)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                _uiState.update {
                    it.copy(isMovingFolder = false, folderError = error.message ?: "Could not change working folder.")
                }
            }
        }
    }

    private fun loadFolderChoices() {
        _uiState.update { it.copy(isLoadingFolders = true, folderError = null) }
        viewModelScope.launch {
            try {
                val choices = source.folderChoices()
                _uiState.update { it.copy(folderChoices = choices, isLoadingFolders = false) }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                _uiState.update {
                    it.copy(isLoadingFolders = false, folderError = error.message ?: "Could not load folders.")
                }
            }
        }
    }

    private fun loadDirectory(path: String, after: (() -> Unit)? = null) {
        val generation = (directoryGenerations[path] ?: 0L) + 1L
        directoryGenerations[path] = generation
        _uiState.update {
            val tree = it.workspace.tree.reduce(FileTreeAction.LoadStarted(path, generation))
            it.copy(workspace = it.workspace.copy(tree = tree))
        }
        viewModelScope.launch {
            try {
                val entries = source.directoryList(path)
                if (directoryGenerations[path] != generation) return@launch
                _uiState.update {
                    val tree = it.workspace.tree.reduce(FileTreeAction.Loaded(path, generation, entries))
                    it.copy(workspace = it.workspace.copy(tree = tree))
                }
                after?.invoke()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                if (directoryGenerations[path] != generation) return@launch
                _uiState.update {
                    val tree = it.workspace.tree.reduce(
                        FileTreeAction.LoadFailed(path, generation, error.message ?: "Could not load files."),
                    )
                    it.copy(workspace = it.workspace.copy(tree = tree))
                }
            }
        }
    }

    private suspend fun loadPreview(path: String, entry: WorkspaceEntry): WorkspaceFilePreview =
        when (previewKindFor(path)) {
            FilePreviewKind.TEXT,
            FilePreviewKind.MARKDOWN,
            -> {
                val file = source.file(path)
                if (file.isBinary) {
                    WorkspaceFilePreview.Unavailable("Preview is not available for this file type.")
                } else {
                    val mapped = file.toFileResponse()
                    val markdown = previewKindFor(path) == FilePreviewKind.MARKDOWN
                    WorkspaceFilePreview.Text(
                        file = mapped,
                        isMarkdown = markdown,
                        markdownChunks = if (markdown) MarkdownPreviewChunker.chunks(mapped.content.orEmpty()) else null,
                    )
                }
            }

            FilePreviewKind.IMAGE,
            FilePreviewKind.PDF,
            FilePreviewKind.EXTERNAL,
            -> {
                val bytes = source.previewData(path)
                val filename = entry.name?.takeIf(String::isNotBlank) ?: path.substringAfterLast('/')
                val mime = mimeTypeForPath(path)
                WorkspaceFilePreview.Document(
                    filename = filename,
                    path = path,
                    mimeType = mime,
                    bytes = bytes,
                    route = DocumentPreview.route(filename, mime),
                )
            }
        }

    private fun HermesWorkspaceFile.toFileResponse(): FileResponse = FileResponse(
        content = content,
        path = path,
        name = name,
        language = language,
        size = size,
        lines = lines,
        isBinary = isBinary,
        isTruncated = isTruncated,
        isPreviewOnly = isPreviewOnly,
    )

    companion object {
        private fun initialState(context: HermesWorkspaceContext): HermesWorkspaceUiState {
            val root = WorkspaceRootItem(
                path = ROOT_ID,
                name = "Working folder",
                sessionId = context.storedKey,
                sessionCount = 1,
            )
            return HermesWorkspaceUiState(
                context = context,
                folderQuery = context.cwd,
                workspace = WorkspaceUiState(
                    hasLoadedRoots = true,
                    roots = listOf(root),
                    selectedRootPath = ROOT_ID,
                    browseSessionId = context.storedKey,
                ),
            )
        }

        private const val ROOT_ID = "hermes-working-folder"
    }
}
