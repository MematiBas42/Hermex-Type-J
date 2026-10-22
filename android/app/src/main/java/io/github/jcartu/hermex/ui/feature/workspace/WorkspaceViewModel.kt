package io.github.jcartu.hermex.ui.feature.workspace

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.jcartu.hermex.core.media.DocumentPreview
import io.github.jcartu.hermex.core.media.DocumentPreviewRoute
import io.github.jcartu.hermex.model.FileResponse
import io.github.jcartu.hermex.model.SessionSummary
import io.github.jcartu.hermex.model.WorkspaceEntry
import io.github.jcartu.hermex.model.WorkspaceRoot
import io.github.jcartu.hermex.net.HermesApi
import io.github.jcartu.hermex.net.WorkspacePreviewTooLargeException
import io.github.jcartu.hermex.net.rawFilePreviewData
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Port of `FileBrowserViewModel.swift` + `FilePreviewViewModel.swift`. Directory
 * listings are retained in [LazyFileTree] and fetched only as their row opens.
 * Per-directory generations reject stale refreshes; navigation ids ensure a
 * slow chat link can never replace a newer link.
 */
class WorkspaceViewModel(private val api: HermesApi) : ViewModel() {
    private val mutableUiState = MutableStateFlow(WorkspaceUiState())
    val uiState: StateFlow<WorkspaceUiState> = mutableUiState.asStateFlow()

    private var hasRequestedRoots = false
    private var rootsRevision = 0L
    private val directoryGenerations = mutableMapOf<String, Long>()
    private var previewRevision = 0L
    private val navigationSequence = AtomicLong()
    private var latestNavigationId = 0L
    private var sessionsById: Map<String, SessionSummary> = emptyMap()

    /** Loads the workspace registry once per ViewModel lifetime. */
    fun loadRootsIfNeeded() {
        if (hasRequestedRoots) return
        hasRequestedRoots = true
        loadRoots()
    }

    /** Refreshes workspace roots and their most recent browse sessions. */
    fun loadRoots(): Job {
        hasRequestedRoots = true
        val revision = ++rootsRevision
        mutableUiState.update { it.copy(isLoadingRoots = true, rootsError = null) }
        return viewModelScope.launch { loadRootsNow(revision) }
    }

    private suspend fun loadRootsNow(revision: Long): Boolean {
        return try {
            val response = api.workspaces()
            val sessions = api.sessions().sessions.orEmpty()
            if (revision != rootsRevision) return false
            sessionsById = sessions.mapNotNull { session -> session.sessionId?.let { it to session } }.toMap()
            val roots = workspaceRootItems(response.workspaces.orEmpty(), sessions)
            mutableUiState.update { state ->
                state.copy(
                    isLoadingRoots = false,
                    hasLoadedRoots = true,
                    roots = roots,
                    lastUsedPath = normalizedWorkspacePath(response.last),
                    rootsError = null,
                )
            }
            true
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (revision == rootsRevision) {
                mutableUiState.update {
                    it.copy(isLoadingRoots = false, rootsError = error.userMessage())
                }
            }
            false
        }
    }

    /** Selects one registered workspace and lazily lists only its root. */
    fun selectRoot(root: WorkspaceRootItem) {
        val sessionId = root.sessionId ?: return
        previewRevision++
        directoryGenerations.clear()
        mutableUiState.update {
            it.copy(
                selectedRootPath = root.path,
                browseSessionId = sessionId,
                currentPath = LazyFileTree.ROOT_PATH,
                entries = emptyList(),
                isLoadingEntries = false,
                entriesError = null,
                searchQuery = "",
                selectedEntry = null,
                selectedLine = null,
                preview = null,
                isLoadingPreview = false,
                tree = LazyFileTree(),
            )
        }
        requestDirectory(LazyFileTree.ROOT_PATH)
    }

    /** Explicit directory load retained for refresh/breadcrumb callers. */
    fun load(path: String) {
        mutableUiState.update { it.copy(currentPath = path) }
        requestDirectory(path)
    }

    fun loadBrowseRoot() = load(LazyFileTree.ROOT_PATH)
    fun reloadCurrentPath() = requestDirectory(mutableUiState.value.currentPath)
    fun retryLastLoad() = requestDirectory(mutableUiState.value.currentPath)

    fun goUp() {
        val parent = mutableUiState.value.parentPath ?: return
        load(parent)
    }

    fun openBreadcrumb(path: String) = load(path)

    fun setSearchQuery(query: String) {
        mutableUiState.update { it.copy(searchQuery = query) }
    }

    /** Opens/collapses a directory or previews a file. */
    fun openEntry(entry: WorkspaceEntry) {
        if (entry.targetOutsideWorkspace == true) return
        if (entry.isBrowsableDirectory) {
            val path = entry.path ?: return
            toggleDirectory(path)
        } else {
            selectEntry(entry)
        }
    }

    /** Opens/collapses one tree node; its children load only on first expansion. */
    fun toggleDirectory(path: String) {
        val before = mutableUiState.value.tree
        val after = before.reduce(FileTreeAction.ToggleDirectory(path))
        mutableUiState.update { it.copy(tree = after, currentPath = path) }
        if (path in after.expandedPaths && (!after.isLoaded(path) || path in after.failedPaths)) {
            requestDirectory(path)
        }
    }

    /** Retries one failed directory without disturbing other expanded rows. */
    fun retryDirectory(path: String) {
        val tree = mutableUiState.value.tree
        if (path !in tree.expandedPaths) {
            mutableUiState.update { it.copy(tree = tree.reduce(FileTreeAction.ToggleDirectory(path))) }
        }
        requestDirectory(path)
    }

    private fun requestDirectory(path: String) {
        val sessionId = mutableUiState.value.browseSessionId ?: return
        val generation = nextDirectoryGeneration(path)
        mutableUiState.update { state ->
            state.copy(
                isLoadingEntries = path == state.currentPath,
                entriesError = if (path == state.currentPath) null else state.entriesError,
                tree = state.tree.reduce(FileTreeAction.LoadStarted(path, generation)),
            )
        }
        viewModelScope.launch { fetchDirectory(sessionId, path, generation) }
    }

    private suspend fun fetchDirectory(sessionId: String, path: String, generation: Long): Boolean {
        return try {
            val response = api.directoryList(sessionId, path)
            if (response.error?.isNotBlank() == true) error(response.error)
            val entries = response.entries.orEmpty()
            if (directoryGenerations[path] != generation || mutableUiState.value.browseSessionId != sessionId) {
                return false
            }
            mutableUiState.update { state ->
                val resolvedPath = response.path ?: path
                state.copy(
                    currentPath = if (path == state.currentPath) resolvedPath else state.currentPath,
                    entries = if (path == state.currentPath || path == LazyFileTree.ROOT_PATH) entries else state.entries,
                    isLoadingEntries = if (path == state.currentPath) false else state.isLoadingEntries,
                    entriesError = if (path == state.currentPath) null else state.entriesError,
                    tree = state.tree.reduce(FileTreeAction.Loaded(path, generation, entries)),
                )
            }
            true
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (directoryGenerations[path] != generation) return false
            mutableUiState.update { state ->
                state.copy(
                    isLoadingEntries = if (path == state.currentPath) false else state.isLoadingEntries,
                    entriesError = if (path == state.currentPath) error.userMessage() else state.entriesError,
                    tree = state.tree.reduce(FileTreeAction.LoadFailed(path, generation, error.userMessage())),
                )
            }
            false
        }
    }

    private fun nextDirectoryGeneration(path: String): Long {
        val next = (directoryGenerations[path] ?: 0L) + 1L
        directoryGenerations[path] = next
        return next
    }

    /**
     * Resolves a transcript link against [preferredSessionId], opens every
     * ancestor in order, and previews only if this remains the latest request.
     */
    fun openWorkspaceFile(
        reference: WorkspaceFileReference,
        preferredSessionId: String? = null,
        requestId: Long = navigationSequence.incrementAndGet(),
    ) {
        latestNavigationId = maxOf(latestNavigationId, requestId)
        mutableUiState.update {
            it.copy(tree = it.tree.reduce(FileTreeAction.NavigationRequested(reference.path, requestId)))
        }
        viewModelScope.launch {
            if (!mutableUiState.value.hasLoadedRoots) {
                hasRequestedRoots = true
                val revision = ++rootsRevision
                mutableUiState.update { it.copy(isLoadingRoots = true, rootsError = null) }
                if (!loadRootsNow(revision)) return@launch
            }
            if (requestId != latestNavigationId) return@launch

            val preferred = preferredSessionId?.let(sessionsById::get)
            val normalizedPath = stripWorkspaceRoot(reference.path, preferred?.workspace)
            val session = preferred ?: sessionForReference(reference.path) ?: return@launch
            val workspace = normalizedWorkspacePath(session.workspace) ?: return@launch
            val sessionId = session.sessionId ?: return@launch
            val root = mutableUiState.value.roots.firstOrNull { it.path == workspace }
                ?: WorkspaceRootItem(workspace, workspace.substringAfterLast('/'), sessionId, 1)

            directoryGenerations.clear()
            mutableUiState.update { state ->
                state.copy(
                    roots = if (root in state.roots) state.roots else state.roots + root,
                    selectedRootPath = root.path,
                    browseSessionId = sessionId,
                    currentPath = LazyFileTree.ROOT_PATH,
                    entries = emptyList(),
                    selectedEntry = null,
                    selectedLine = reference.line,
                    preview = null,
                    tree = LazyFileTree().reduce(FileTreeAction.NavigationRequested(normalizedPath, requestId)),
                )
            }

            val directories = listOf(LazyFileTree.ROOT_PATH) + LazyFileTree.ancestorPaths(normalizedPath)
            for (directory in directories) {
                if (requestId != latestNavigationId) return@launch
                if (!mutableUiState.value.tree.isLoaded(directory)) {
                    val generation = nextDirectoryGeneration(directory)
                    mutableUiState.update {
                        it.copy(tree = it.tree.reduce(FileTreeAction.LoadStarted(directory, generation)))
                    }
                    if (!fetchDirectory(sessionId, directory, generation)) return@launch
                }
            }
            if (requestId != latestNavigationId) return@launch
            val node = mutableUiState.value.tree.nodeAt(normalizedPath)
            val entry = node?.entry ?: WorkspaceEntry(
                name = normalizedPath.substringAfterLast('/'),
                path = normalizedPath,
                type = "file",
            )
            mutableUiState.update {
                it.copy(tree = it.tree.reduce(FileTreeAction.NavigationCompleted(normalizedPath, requestId)))
            }
            selectEntry(entry, reference.line)
        }
    }

    private fun sessionForReference(path: String): SessionSummary? {
        if (path.startsWith('/')) {
            return sessionsById.values
                .filter { workspace ->
                    val root = normalizedWorkspacePath(workspace.workspace)
                    root != null && (path == root || path.startsWith("$root/"))
                }
                .maxByOrNull(SessionSummary::browseTimestampValue)
        }
        return mutableUiState.value.browseSessionId?.let(sessionsById::get)
            ?: sessionsById.values.maxByOrNull(SessionSummary::browseTimestampValue)
    }

    private fun selectEntry(entry: WorkspaceEntry, targetLine: Int? = null) {
        val sessionId = mutableUiState.value.browseSessionId ?: return
        val path = entry.path ?: return
        val revision = ++previewRevision
        mutableUiState.update {
            it.copy(
                selectedEntry = entry,
                selectedLine = targetLine,
                preview = null,
                isLoadingPreview = true,
                tree = it.tree.copy(selectedPath = path),
            )
        }
        viewModelScope.launch {
            try {
                val preview = loadPreview(sessionId, entry)
                if (revision != previewRevision) return@launch
                mutableUiState.update { it.copy(preview = preview, isLoadingPreview = false) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (revision != previewRevision) return@launch
                val message = if (error is WorkspacePreviewTooLargeException) {
                    DocumentPreview.TOO_LARGE_MESSAGE
                } else {
                    error.userMessage()
                }
                mutableUiState.update {
                    it.copy(preview = WorkspaceFilePreview.Failed(message), isLoadingPreview = false)
                }
            }
        }
    }

    private suspend fun loadPreview(sessionId: String, entry: WorkspaceEntry): WorkspaceFilePreview {
        val path = entry.path.orEmpty()
        return when (previewKindFor(path)) {
            FilePreviewKind.MARKDOWN -> {
                val response = api.file(sessionId, path)
                val content = response.content.orEmpty()
                val images = loadMarkdownImages(sessionId, path, content)
                WorkspaceFilePreview.Text(
                    file = response,
                    isMarkdown = true,
                    markdownChunks = MarkdownPreviewChunker.chunks(content),
                    markdownImages = images,
                )
            }
            FilePreviewKind.TEXT -> {
                val response = api.file(sessionId, path)
                if (response.isBinary) {
                    WorkspaceFilePreview.Unavailable("Preview is not available for this file type.")
                } else {
                    WorkspaceFilePreview.Text(response, isMarkdown = false)
                }
            }
            FilePreviewKind.IMAGE,
            FilePreviewKind.PDF,
            FilePreviewKind.EXTERNAL,
            -> {
                DocumentPreview.sizeRejection(entry.size?.toLong())?.let {
                    return WorkspaceFilePreview.Unavailable(it)
                }
                val bytes = api.rawFilePreviewData(sessionId, path)
                val mime = mimeTypeForPath(path)
                WorkspaceFilePreview.Document(
                    filename = entry.name ?: path.substringAfterLast('/'),
                    path = path,
                    mimeType = mime,
                    bytes = bytes,
                    route = DocumentPreview.route(path, mime),
                )
            }
        }
    }

    private suspend fun loadMarkdownImages(
        sessionId: String,
        markdownPath: String,
        content: String,
    ): Map<String, ByteArray> {
        val destinations = MarkdownImageParser.destinations(content)
        if (destinations.isEmpty()) return emptyMap()
        return buildMap {
            for (destination in destinations.take(MAX_MARKDOWN_IMAGES)) {
                val resolved = resolveMarkdownImagePath(markdownPath, destination) ?: continue
                val bytes = try {
                    api.rawFilePreviewData(sessionId, resolved)
                } catch (_: Exception) {
                    continue
                }
                put(destination, bytes)
            }
        }
    }

    fun closePreview() {
        previewRevision++
        mutableUiState.update {
            it.copy(
                selectedEntry = null,
                selectedLine = null,
                preview = null,
                isLoadingPreview = false,
                tree = it.tree.reduce(FileTreeAction.ClearSelection),
            )
        }
    }

    fun backToRoots() {
        previewRevision++
        directoryGenerations.clear()
        mutableUiState.update {
            it.copy(
                selectedRootPath = null,
                browseSessionId = null,
                currentPath = LazyFileTree.ROOT_PATH,
                entries = emptyList(),
                isLoadingEntries = false,
                entriesError = null,
                searchQuery = "",
                selectedEntry = null,
                selectedLine = null,
                preview = null,
                isLoadingPreview = false,
                tree = LazyFileTree(),
            )
        }
    }

    /** Single-pane back: preview → tree → roots. */
    fun goBack() {
        when {
            mutableUiState.value.selectedEntry != null -> closePreview()
            mutableUiState.value.selectedRootPath != null -> backToRoots()
        }
    }

    fun goBackInBrowser() {
        if (mutableUiState.value.selectedRootPath != null) backToRoots()
    }

    private companion object {
        const val MAX_MARKDOWN_IMAGES = 32
    }
}

data class WorkspaceUiState(
    val isLoadingRoots: Boolean = false,
    val hasLoadedRoots: Boolean = false,
    val rootsError: String? = null,
    val roots: List<WorkspaceRootItem> = emptyList(),
    val lastUsedPath: String? = null,
    val selectedRootPath: String? = null,
    val browseSessionId: String? = null,
    val currentPath: String = LazyFileTree.ROOT_PATH,
    val entries: List<WorkspaceEntry> = emptyList(),
    val isLoadingEntries: Boolean = false,
    val entriesError: String? = null,
    val searchQuery: String = "",
    val selectedEntry: WorkspaceEntry? = null,
    val selectedLine: Int? = null,
    val preview: WorkspaceFilePreview? = null,
    val isLoadingPreview: Boolean = false,
    val tree: LazyFileTree = LazyFileTree(),
) {
    val selectedRoot: WorkspaceRootItem? get() = roots.firstOrNull { it.path == selectedRootPath }
    val isAtRoot: Boolean get() = currentPath == LazyFileTree.ROOT_PATH
    val displayPath: String get() = if (isAtRoot) "Root" else currentPath
    val parentPath: String? get() = parentPathFor(currentPath)
    val breadcrumbs: List<FileBreadcrumb> get() = breadcrumbsFor(currentPath)
    val visibleEntries: List<WorkspaceEntry> get() = filterEntries(entries, searchQuery)
    val visibleTreeNodes: List<VisibleFileTreeNode> get() = tree.visibleNodes(searchQuery)
    val canGoBack: Boolean get() = selectedRootPath != null
}

/** A registered workspace root paired with the newest session able to list it. */
data class WorkspaceRootItem(
    val path: String,
    val name: String?,
    val sessionId: String?,
    val sessionCount: Int,
) {
    val displayName: String get() = name?.takeIf(String::isNotBlank) ?: path.substringAfterLast('/').ifEmpty { path }
    val isBrowsable: Boolean get() = sessionId != null
}

/** Port of `FilePreviewContent` with Android document routes. */
sealed class WorkspaceFilePreview {
    data class Text(
        val file: FileResponse,
        val isMarkdown: Boolean,
        val markdownChunks: List<MarkdownPreviewChunk>? = null,
        val markdownImages: Map<String, ByteArray> = emptyMap(),
    ) : WorkspaceFilePreview()

    data class Document(
        val filename: String,
        val path: String,
        val mimeType: String,
        val bytes: ByteArray,
        val route: DocumentPreviewRoute,
    ) : WorkspaceFilePreview()

    data class Unavailable(val message: String) : WorkspaceFilePreview()
    data class Failed(val message: String) : WorkspaceFilePreview()
}

data class FileBreadcrumb(val title: String, val path: String)

internal enum class FilePreviewKind { IMAGE, PDF, EXTERNAL, MARKDOWN, TEXT }

private val MARKDOWN_EXTENSIONS = setOf("md", "markdown", "mdown", "mkd")
private val TEXT_DOTFILES = setOf(
    ".babelrc", ".bash_profile", ".bashrc", ".browserslistrc", ".dockerignore",
    ".env", ".eslintignore", ".eslintrc", ".gitattributes", ".gitignore",
    ".gitkeep", ".gitmodules", ".prettierignore", ".prettierrc", ".stylelintignore",
    ".stylelintrc", ".zprofile", ".zshenv", ".zshrc", "dockerfile", "gemfile",
    "makefile", "rakefile",
)

/** Lowercased extension of the last path component. */
internal fun pathExtension(path: String): String {
    val name = path.substringAfterLast('/')
    val dot = name.lastIndexOf('.')
    if (dot <= 0 || dot == name.lastIndex) return ""
    return name.substring(dot + 1).lowercase()
}

internal fun previewKindFor(path: String): FilePreviewKind {
    val extension = pathExtension(path)
    val basename = path.substringAfterLast('/').lowercase()
    if (extension in MARKDOWN_EXTENSIONS) return FilePreviewKind.MARKDOWN
    if (extension.isEmpty() || basename in TEXT_DOTFILES) return FilePreviewKind.TEXT
    return when (DocumentPreview.route(path, mimeTypeForPath(path))) {
        DocumentPreviewRoute.IMAGE -> FilePreviewKind.IMAGE
        DocumentPreviewRoute.PDF -> FilePreviewKind.PDF
        DocumentPreviewRoute.TEXT -> FilePreviewKind.TEXT
        DocumentPreviewRoute.EXTERNAL -> FilePreviewKind.EXTERNAL
    }
}

/** Port of FileBrowser breadcrumbs retained for compact navigation. */
internal fun breadcrumbsFor(currentPath: String): List<FileBreadcrumb> {
    if (currentPath == LazyFileTree.ROOT_PATH) return listOf(FileBreadcrumb("Root", LazyFileTree.ROOT_PATH))
    val parts = currentPath.split('/').filter(String::isNotEmpty)
    val crumbs = ArrayList<FileBreadcrumb>(parts.size + 1)
    crumbs += FileBreadcrumb("Root", LazyFileTree.ROOT_PATH)
    var prefix = ""
    for (part in parts) {
        prefix = if (prefix.isEmpty()) part else "$prefix/$part"
        crumbs += FileBreadcrumb(part, prefix)
    }
    return crumbs
}

internal fun parentPathFor(currentPath: String): String? {
    if (currentPath == LazyFileTree.ROOT_PATH) return null
    return currentPath.substringBeforeLast('/', LazyFileTree.ROOT_PATH)
}

internal fun filterEntries(entries: List<WorkspaceEntry>, query: String): List<WorkspaceEntry> {
    val trimmed = query.trim()
    if (trimmed.isEmpty()) return entries
    return entries.filter { entry ->
        listOfNotNull(entry.name, entry.path, entry.type).any { it.contains(trimmed, ignoreCase = true) }
    }
}

internal fun normalizedWorkspacePath(path: String?): String? =
    path?.trim()?.trimEnd('/')?.takeIf(String::isNotEmpty)

private val SessionSummary.browseTimestamp: Double
    get() = lastMessageAt ?: updatedAt ?: createdAt ?: Double.NEGATIVE_INFINITY

private fun SessionSummary.browseTimestampValue(): Double = browseTimestamp

/** Pairs every registered root with the most recently active matching session. */
internal fun workspaceRootItems(
    roots: List<WorkspaceRoot>,
    sessions: List<SessionSummary>,
): List<WorkspaceRootItem> {
    val byWorkspace = sessions.filter { it.sessionId != null }.groupBy { normalizedWorkspacePath(it.workspace) }
    return roots.mapNotNull { root ->
        val path = normalizedWorkspacePath(root.path) ?: return@mapNotNull null
        val matches = byWorkspace[path].orEmpty()
        val best = matches.maxByOrNull(SessionSummary::browseTimestampValue)
        WorkspaceRootItem(path, root.name, best?.sessionId, matches.size)
    }
}

internal fun Exception.userMessage(): String = message?.takeIf(String::isNotBlank) ?: "Something went wrong."

internal fun mimeTypeForPath(path: String): String {
    return when (pathExtension(path)) {
        "pdf" -> "application/pdf"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "svg" -> "image/svg+xml"
        "json", "jsonc" -> "application/json"
        "html", "htm" -> "text/html"
        "css" -> "text/css"
        "js", "mjs", "cjs" -> "application/javascript"
        "xml", "plist" -> "application/xml"
        "yaml", "yml" -> "application/yaml"
        "md", "markdown", "mdown", "mkd" -> "text/markdown"
        "txt", "log", "csv", "tsv" -> "text/plain"
        else -> "application/octet-stream"
    }
}

private fun stripWorkspaceRoot(path: String, workspace: String?): String {
    val root = normalizedWorkspacePath(workspace) ?: return path.removePrefix("/")
    return when {
        path == root -> path.substringAfterLast('/')
        path.startsWith("$root/") -> path.removePrefix("$root/")
        else -> path.removePrefix("/")
    }
}

private fun resolveMarkdownImagePath(markdownPath: String, destination: String): String? {
    val clean = destination.substringBefore('#').substringBefore('?').trim().removeSurrounding("<", ">")
    if (clean.isEmpty() || clean.startsWith("data:", true) || Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:").containsMatchIn(clean)) {
        return null
    }
    val base = markdownPath.substringBeforeLast('/', "")
    val stack = ArrayDeque<String>()
    (if (clean.startsWith('/')) clean.removePrefix("/") else listOf(base, clean).filter(String::isNotEmpty).joinToString("/"))
        .split('/')
        .forEach { component ->
            when (component) {
                "", "." -> Unit
                ".." -> if (stack.isEmpty()) return null else stack.removeLast()
                else -> stack.addLast(component)
            }
        }
    return stack.joinToString("/").takeIf(String::isNotEmpty)
}
