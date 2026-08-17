package io.github.jcartu.hermex.ui.feature.workspace

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.jcartu.hermex.model.FileResponse
import io.github.jcartu.hermex.model.SessionSummary
import io.github.jcartu.hermex.model.WorkspaceEntry
import io.github.jcartu.hermex.model.WorkspaceRoot
import io.github.jcartu.hermex.net.HermesApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Port of `Features/Workspace/FileBrowserViewModel.swift` +
 * `FilePreviewViewModel.swift`, adapted to a workspace-first entry point:
 * the iOS browser is always opened from a chat session and sends that
 * session's `session_id` to `/api/list` — the server resolves the session's
 * workspace and lists paths relative to it. Android has no ambient session on
 * the Files tab, so each registered workspace root (`GET /api/workspaces`) is
 * paired with its most recently active session and that session's id drives
 * `directoryList`/`file`/`rawFileUrl`, exactly like the Swift client. Roots
 * without any session cannot be listed (the endpoint requires `session_id`)
 * and are shown with a hint instead.
 */
class WorkspaceViewModel(private val api: HermesApi) : ViewModel() {

    private val _uiState = MutableStateFlow(WorkspaceUiState())
    val uiState: StateFlow<WorkspaceUiState> = _uiState.asStateFlow()

    private var hasLoadedRoots = false
    private var rootsRevision = 0
    private var entriesRevision = 0
    private var previewRevision = 0
    private var lastRequestedPath = "."

    fun loadRootsIfNeeded() {
        if (hasLoadedRoots) return
        hasLoadedRoots = true
        loadRoots()
    }

    fun loadRoots() {
        val revision = ++rootsRevision
        _uiState.update { it.copy(isLoadingRoots = true, rootsError = null) }
        viewModelScope.launch {
            try {
                val workspaces = api.workspaces()
                val sessions = api.sessions().sessions.orEmpty()
                if (revision != rootsRevision) return@launch
                val items = workspaceRootItems(workspaces.workspaces.orEmpty(), sessions)
                _uiState.update { state ->
                    state.copy(
                        isLoadingRoots = false,
                        hasLoadedRoots = true,
                        roots = items,
                        lastUsedPath = normalizedWorkspacePath(workspaces.last),
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (revision != rootsRevision) return@launch
                _uiState.update { it.copy(isLoadingRoots = false, rootsError = e.userMessage()) }
            }
        }
    }

    /** Opens a workspace root: browsing starts at the workspace's own root ("."). */
    fun selectRoot(root: WorkspaceRootItem) {
        val sessionId = root.sessionId ?: return
        _uiState.update {
            it.copy(
                selectedRootPath = root.path,
                browseSessionId = sessionId,
                currentPath = ".",
                entries = emptyList(),
                entriesError = null,
                searchQuery = "",
                selectedEntry = null,
                preview = null,
                isLoadingPreview = false,
            )
        }
        load(".")
    }

    /** Port of FileBrowserViewModel.load(path:). */
    fun load(path: String) {
        val sessionId = _uiState.value.browseSessionId ?: return
        lastRequestedPath = path
        val revision = ++entriesRevision
        _uiState.update { it.copy(isLoadingEntries = true, entriesError = null) }
        viewModelScope.launch {
            try {
                val response = api.directoryList(sessionId, path)
                if (revision != entriesRevision) return@launch
                _uiState.update { state ->
                    state.copy(
                        isLoadingEntries = false,
                        currentPath = response.path ?: path,
                        entries = response.entries.orEmpty(),
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (revision != entriesRevision) return@launch
                _uiState.update { it.copy(isLoadingEntries = false, entriesError = e.userMessage()) }
            }
        }
    }

    fun loadBrowseRoot() = load(".")

    fun reloadCurrentPath() = load(_uiState.value.currentPath)

    fun retryLastLoad() = load(lastRequestedPath)

    fun goUp() {
        val parent = _uiState.value.parentPath ?: return
        load(parent)
    }

    fun openBreadcrumb(path: String) = load(path)

    fun setSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
    }

    /** Tapping an entry: directories descend, files open a preview. */
    fun openEntry(entry: WorkspaceEntry) {
        if (entry.isBrowsableDirectory) {
            load(entry.path ?: ".")
        } else if (entry.path != null) {
            selectEntry(entry)
        }
    }

    /**
     * Port of FilePreviewViewModel.load(): raster images stream through the raw
     * endpoint, known binaries short-circuit to an unavailable notice, and
     * everything else is fetched as text via `/api/file`.
     */
    private fun selectEntry(entry: WorkspaceEntry) {
        val sessionId = _uiState.value.browseSessionId ?: return
        val path = entry.path ?: return
        val revision = ++previewRevision
        _uiState.update { it.copy(selectedEntry = entry, preview = null, isLoadingPreview = true) }
        viewModelScope.launch {
            try {
                val preview = when (previewKindFor(path)) {
                    FilePreviewKind.IMAGE ->
                        WorkspaceFilePreview.Image(api.rawFileUrl(sessionId, path).toString())
                    FilePreviewKind.BINARY ->
                        WorkspaceFilePreview.Unavailable("Preview is not available for this file type.")
                    FilePreviewKind.MARKDOWN ->
                        WorkspaceFilePreview.Text(api.file(sessionId, path), isMarkdown = true)
                    FilePreviewKind.TEXT ->
                        WorkspaceFilePreview.Text(api.file(sessionId, path), isMarkdown = false)
                }
                if (revision != previewRevision) return@launch
                _uiState.update { it.copy(preview = preview, isLoadingPreview = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (revision != previewRevision) return@launch
                _uiState.update {
                    it.copy(preview = WorkspaceFilePreview.Failed(e.userMessage()), isLoadingPreview = false)
                }
            }
        }
    }

    fun closePreview() {
        previewRevision++
        _uiState.update { it.copy(selectedEntry = null, preview = null, isLoadingPreview = false) }
    }

    fun backToRoots() {
        previewRevision++
        entriesRevision++
        _uiState.update {
            it.copy(
                selectedRootPath = null,
                browseSessionId = null,
                currentPath = ".",
                entries = emptyList(),
                isLoadingEntries = false,
                entriesError = null,
                searchQuery = "",
                selectedEntry = null,
                preview = null,
                isLoadingPreview = false,
            )
        }
    }

    /** Single-pane back navigation: preview → directory → parents → roots. */
    fun goBack() {
        val state = _uiState.value
        when {
            state.selectedEntry != null -> closePreview()
            !state.isAtRoot -> goUp()
            state.selectedRootPath != null -> backToRoots()
        }
    }

    /** Multi-pane back navigation: the preview pane stays, only the list walks up. */
    fun goBackInBrowser() {
        val state = _uiState.value
        when {
            !state.isAtRoot -> goUp()
            state.selectedRootPath != null -> backToRoots()
        }
    }
}

data class WorkspaceUiState(
    val isLoadingRoots: Boolean = false,
    val hasLoadedRoots: Boolean = false,
    val rootsError: String? = null,
    val roots: List<WorkspaceRootItem> = emptyList(),
    /** `last` from GET /api/workspaces — the most recently used workspace. */
    val lastUsedPath: String? = null,
    val selectedRootPath: String? = null,
    val browseSessionId: String? = null,
    val currentPath: String = ".",
    val entries: List<WorkspaceEntry> = emptyList(),
    val isLoadingEntries: Boolean = false,
    val entriesError: String? = null,
    val searchQuery: String = "",
    val selectedEntry: WorkspaceEntry? = null,
    val preview: WorkspaceFilePreview? = null,
    val isLoadingPreview: Boolean = false,
) {
    val selectedRoot: WorkspaceRootItem?
        get() = roots.firstOrNull { it.path == selectedRootPath }

    val isAtRoot: Boolean
        get() = currentPath == "."

    val displayPath: String
        get() = if (isAtRoot) "Root" else currentPath

    val parentPath: String?
        get() = parentPathFor(currentPath)

    val breadcrumbs: List<FileBreadcrumb>
        get() = breadcrumbsFor(currentPath)

    val visibleEntries: List<WorkspaceEntry>
        get() = filterEntries(entries, searchQuery)

    val canGoBack: Boolean
        get() = selectedRootPath != null
}

/** A registered workspace root paired with the session whose id can list it. */
data class WorkspaceRootItem(
    val path: String,
    val name: String?,
    val sessionId: String?,
    val sessionCount: Int,
) {
    val displayName: String
        get() = name?.takeIf { it.isNotBlank() }
            ?: path.substringAfterLast('/').ifEmpty { path }

    val isBrowsable: Boolean
        get() = sessionId != null
}

sealed class WorkspaceFilePreview {
    data class Text(val file: FileResponse, val isMarkdown: Boolean) : WorkspaceFilePreview()
    data class Image(val url: String) : WorkspaceFilePreview()
    data class Unavailable(val message: String) : WorkspaceFilePreview()
    data class Failed(val message: String) : WorkspaceFilePreview()
}

/** Port of FileBreadcrumb in FileBrowserViewModel.swift. */
data class FileBreadcrumb(val title: String, val path: String)

internal enum class FilePreviewKind { IMAGE, BINARY, MARKDOWN, TEXT }

/** Raster formats the iOS preview loads via the raw endpoint (FilePreviewViewModel.swift). */
private val RASTER_IMAGE_EXTENSIONS =
    setOf("png", "jpg", "jpeg", "gif", "webp", "ico", "bmp")

/** Known-unsupported binary formats (FilePreviewViewModel.swift). */
private val UNSUPPORTED_BINARY_EXTENSIONS = setOf(
    "7z", "a", "aiff", "avi", "bin", "bz2", "class", "db", "dmg", "doc",
    "docx", "dylib", "exe", "flac", "gz", "jar", "m4a", "mov", "mp3",
    "mp4", "o", "pdf", "pkg", "ppt", "pptx", "pyc", "rar", "sqlite",
    "svg", "tar", "tgz", "wav", "xls", "xlsx", "xz", "zip",
)

/** Markdown extensions rendered rich instead of monospace (FilePreviewView.swift). */
private val MARKDOWN_EXTENSIONS = setOf("md", "markdown", "mdown", "mkd")

/** Lowercased extension of the last path component; "" for dotfiles, matching Swift's pathExtension. */
internal fun pathExtension(path: String): String {
    val name = path.substringAfterLast('/')
    val dot = name.lastIndexOf('.')
    if (dot <= 0 || dot == name.length - 1) return ""
    return name.substring(dot + 1).lowercase()
}

internal fun previewKindFor(path: String): FilePreviewKind {
    val extension = pathExtension(path)
    return when {
        extension in RASTER_IMAGE_EXTENSIONS -> FilePreviewKind.IMAGE
        extension in UNSUPPORTED_BINARY_EXTENSIONS -> FilePreviewKind.BINARY
        extension in MARKDOWN_EXTENSIONS -> FilePreviewKind.MARKDOWN
        else -> FilePreviewKind.TEXT
    }
}

/** Port of FileBrowserViewModel.breadcrumbs. */
internal fun breadcrumbsFor(currentPath: String): List<FileBreadcrumb> {
    if (currentPath == ".") return listOf(FileBreadcrumb("Root", "."))
    val parts = currentPath.split('/').filter { it.isNotEmpty() }
    val crumbs = ArrayList<FileBreadcrumb>(parts.size + 1)
    crumbs.add(FileBreadcrumb("Root", "."))
    var prefix = ""
    for (part in parts) {
        prefix = if (prefix.isEmpty()) part else "$prefix/$part"
        crumbs.add(FileBreadcrumb(part, prefix))
    }
    return crumbs
}

/** Port of FileBrowserViewModel.parentPath. */
internal fun parentPathFor(currentPath: String): String? {
    if (currentPath == ".") return null
    val parts = currentPath.split('/').filter { it.isNotEmpty() }
    if (parts.size <= 1) return "."
    return parts.dropLast(1).joinToString("/")
}

/** Port of the FileBrowserView search filter (name/path/type, case-insensitive). */
internal fun filterEntries(entries: List<WorkspaceEntry>, query: String): List<WorkspaceEntry> {
    val trimmed = query.trim()
    if (trimmed.isEmpty()) return entries
    return entries.filter { entry ->
        listOfNotNull(entry.name, entry.path, entry.type)
            .any { it.contains(trimmed, ignoreCase = true) }
    }
}

internal fun normalizedWorkspacePath(path: String?): String? =
    path?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() }

private val SessionSummary.browseTimestamp: Double?
    get() = lastMessageAt ?: updatedAt ?: createdAt

/**
 * Pairs each registered workspace root with the most recently active session
 * whose `workspace` resolves to that root — the server derives the listing
 * root from `session_id`, so any session in the folder sees the same files.
 */
internal fun workspaceRootItems(
    roots: List<WorkspaceRoot>,
    sessions: List<SessionSummary>,
): List<WorkspaceRootItem> {
    val byWorkspace = sessions
        .filter { it.sessionId != null }
        .groupBy { normalizedWorkspacePath(it.workspace) }
    return roots.mapNotNull { root ->
        val path = normalizedWorkspacePath(root.path) ?: return@mapNotNull null
        val matches = byWorkspace[path].orEmpty()
        val best = matches.maxByOrNull { it.browseTimestamp ?: Double.NEGATIVE_INFINITY }
        WorkspaceRootItem(
            path = path,
            name = root.name,
            sessionId = best?.sessionId,
            sessionCount = matches.size,
        )
    }
}

internal fun Exception.userMessage(): String =
    message?.takeIf { it.isNotBlank() } ?: "Something went wrong."
