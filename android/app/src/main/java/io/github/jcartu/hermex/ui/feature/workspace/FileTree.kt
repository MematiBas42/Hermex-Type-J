package io.github.jcartu.hermex.ui.feature.workspace

import io.github.jcartu.hermex.model.WorkspaceEntry

/**
 * Port of `Features/Workspace/FileTree.swift`. A missing key in [children]
 * means that directory has never been listed, so expanding a row can load only
 * that directory instead of rebuilding the whole tree.
 */
data class FileTreeNode(
    val path: String,
    val name: String,
    val isDirectory: Boolean,
    val isSymlink: Boolean,
    val entry: WorkspaceEntry,
    val searchSegments: List<String>,
    val searchWords: List<String>,
) {
    companion object {
        /** Port of `FileTreeNode.init(entry:parentPath:)`. */
        fun from(entry: WorkspaceEntry, parentPath: String): FileTreeNode? {
            val trimmedName = entry.name?.trim().orEmpty()
            val trimmedPath = entry.path?.trim().orEmpty()
            val resolvedPath = when {
                trimmedPath.isNotEmpty() && trimmedPath != LazyFileTree.ROOT_PATH -> trimmedPath
                trimmedName.isNotEmpty() && parentPath == LazyFileTree.ROOT_PATH -> trimmedName
                trimmedName.isNotEmpty() -> "$parentPath/$trimmedName"
                else -> return null
            }
            val resolvedName = trimmedName.ifEmpty { resolvedPath.substringAfterLast('/') }
            val segments = resolvedPath.split('/').filter(String::isNotEmpty).map { it.lowercase() }
            return FileTreeNode(
                path = resolvedPath,
                name = resolvedName,
                isDirectory = entry.isBrowsableDirectory,
                isSymlink = entry.type == "symlink",
                entry = entry.copy(name = resolvedName, path = resolvedPath),
                searchSegments = segments,
                searchWords = segments.flatMap(FileTreeSearch::splitWords),
            )
        }
    }
}

/** A flattened tree row with indentation depth. */
data class VisibleFileTreeNode(val node: FileTreeNode, val depth: Int)

/**
 * Pure reducer around the lazily loaded tree. Per-directory generations reject
 * stale responses, while navigation ids ensure an older file-link request can
 * never replace the newest requested destination.
 */
data class LazyFileTree(
    val children: Map<String, List<FileTreeNode>> = emptyMap(),
    val expandedPaths: Set<String> = emptySet(),
    val loadingPaths: Map<String, Long> = emptyMap(),
    val failedPaths: Map<String, String> = emptyMap(),
    val selectedPath: String? = null,
    val latestNavigationId: Long = 0,
    val pendingNavigationPath: String? = null,
) {
    val isRootLoaded: Boolean get() = ROOT_PATH in children
    val rootNodes: List<FileTreeNode> get() = children[ROOT_PATH].orEmpty()

    /** Port of `FileTree.children(of:)`. */
    fun childrenOf(directory: String): List<FileTreeNode>? = children[directory]

    /** Port of `FileTree.isLoaded(_:)`. */
    fun isLoaded(directory: String): Boolean = directory in children

    /** Port of `FileTree.node(at:)`. */
    fun nodeAt(path: String): FileTreeNode? =
        children[parentPath(path)]?.firstOrNull { it.path == path }

    /** Applies one deterministic tree/navigation action. */
    fun reduce(action: FileTreeAction): LazyFileTree = when (action) {
        FileTreeAction.Reset -> LazyFileTree(latestNavigationId = latestNavigationId)
        is FileTreeAction.LoadStarted -> copy(
            loadingPaths = loadingPaths + (action.path to action.generation),
            failedPaths = failedPaths - action.path,
        )
        is FileTreeAction.Loaded -> {
            if (loadingPaths[action.path] != action.generation) this
            else withChildren(action.path, action.entries).copy(
                loadingPaths = loadingPaths - action.path,
                failedPaths = failedPaths - action.path,
            )
        }
        is FileTreeAction.LoadFailed -> {
            if (loadingPaths[action.path] != action.generation) this
            else copy(
                loadingPaths = loadingPaths - action.path,
                failedPaths = failedPaths + (action.path to action.message),
            )
        }
        is FileTreeAction.ToggleDirectory -> {
            if (action.path in expandedPaths && action.path !in failedPaths) {
                copy(expandedPaths = expandedPaths - action.path)
            } else {
                copy(expandedPaths = expandedPaths + action.path)
            }
        }
        is FileTreeAction.NavigationRequested -> {
            if (action.id < latestNavigationId) this
            else copy(
                latestNavigationId = action.id,
                pendingNavigationPath = action.path,
                expandedPaths = expandedPaths + ancestorPaths(action.path),
            )
        }
        is FileTreeAction.NavigationCompleted -> {
            if (action.id != latestNavigationId || action.path != pendingNavigationPath) this
            else copy(selectedPath = action.path, pendingNavigationPath = null)
        }
        FileTreeAction.ClearSelection -> copy(selectedPath = null, pendingNavigationPath = null)
    }

    /** Port of `FileTree.visibleNodes(expanded:searchQuery:)`. */
    fun visibleNodes(searchQuery: String = ""): List<VisibleFileTreeNode> {
        val tokens = FileTreeSearch.tokens(searchQuery)
        val output = ArrayList<VisibleFileTreeNode>()
        for (node in rootNodes) appendVisible(node, 0, tokens, output)
        return output
    }

    private fun appendVisible(
        node: FileTreeNode,
        depth: Int,
        tokens: List<String>,
        output: MutableList<VisibleFileTreeNode>,
    ): Boolean {
        val searching = tokens.isNotEmpty()
        val matches = searching && FileTreeSearch.matches(node, tokens)
        var descendantMatches = false
        val childOutput = ArrayList<VisibleFileTreeNode>()
        if (node.isDirectory && (node.path in expandedPaths || searching)) {
            children[node.path]?.forEach { child ->
                if (appendVisible(child, depth + 1, tokens, childOutput)) descendantMatches = true
            }
        }
        if (searching && !matches && !descendantMatches) return false
        output += VisibleFileTreeNode(node, depth)
        output += childOutput
        return matches || descendantMatches || !searching
    }

    private fun withChildren(directory: String, entries: List<WorkspaceEntry>): LazyFileTree {
        val nodes = entries.mapNotNull { FileTreeNode.from(it, directory) }.sortedWith(FILE_NODE_COMPARATOR)
        val updated = children.toMutableMap()
        updated[directory] = nodes

        val surviving = nodes.asSequence().filter(FileTreeNode::isDirectory).map(FileTreeNode::path).toSet()
        val prefix = if (directory == ROOT_PATH) "" else "$directory/"
        val stale = updated.keys.filter { key ->
            if (key == directory || !key.startsWith(prefix)) return@filter false
            val first = key.removePrefix(prefix).substringBefore('/')
            "$prefix$first" !in surviving
        }
        stale.forEach(updated::remove)
        return copy(
            children = updated,
            expandedPaths = expandedPaths - stale.toSet(),
            loadingPaths = loadingPaths - stale.toSet(),
            failedPaths = failedPaths - stale.toSet(),
            selectedPath = selectedPath?.takeUnless { selected ->
                stale.any { selected == it || selected.startsWith("$it/") }
            },
        )
    }

    companion object {
        const val ROOT_PATH = "."

        /** Port of `FileTree.parentPath(of:)`. */
        fun parentPath(path: String): String = path.substringBeforeLast('/', ROOT_PATH)

        /** Port of `FileTree.ancestorPaths(of:)`. */
        fun ancestorPaths(path: String): List<String> {
            val parts = path.split('/').filter(String::isNotEmpty)
            if (parts.size <= 1) return emptyList()
            return (1 until parts.size).map { parts.take(it).joinToString("/") }
        }

        /** Port of `FileTree.defaultExpandedPaths(in:)`. */
        fun defaultExpandedPaths(nodes: List<FileTreeNode>): Set<String> =
            nodes.asSequence()
                .filter { it.isDirectory && !it.name.startsWith('.') }
                .map(FileTreeNode::path)
                .toSet()
    }
}

/** Inputs accepted by [LazyFileTree.reduce]. */
sealed interface FileTreeAction {
    data object Reset : FileTreeAction
    data class LoadStarted(val path: String, val generation: Long) : FileTreeAction
    data class Loaded(val path: String, val generation: Long, val entries: List<WorkspaceEntry>) : FileTreeAction
    data class LoadFailed(val path: String, val generation: Long, val message: String) : FileTreeAction
    data class ToggleDirectory(val path: String) : FileTreeAction
    data class NavigationRequested(val path: String, val id: Long) : FileTreeAction
    data class NavigationCompleted(val path: String, val id: Long) : FileTreeAction
    data object ClearSelection : FileTreeAction
}

/** Tokenized tiered matching from `FileTreeSearch` in `FileTree.swift`. */
object FileTreeSearch {
    /** Lowercased query split on whitespace and path punctuation. */
    fun tokens(query: String): List<String> = query.trim().lowercase()
        .split(Regex("[\\s/\\\\._-]+"))
        .filter(String::isNotEmpty)

    /** Splits camelCase/PascalCase identifiers while keeping acronym runs. */
    fun splitWords(value: String): List<String> {
        val words = ArrayList<String>()
        val current = StringBuilder()
        value.forEachIndexed { index, character ->
            if (!character.isLetterOrDigit()) {
                if (current.isNotEmpty()) words += current.toString()
                current.clear()
                return@forEachIndexed
            }
            if (current.isNotEmpty() && character.isUpperCase()) {
                val previous = value[index - 1]
                val next = value.getOrNull(index + 1)
                if (previous.isLowerCase() || previous.isDigit() || (previous.isUpperCase() && next?.isLowerCase() == true)) {
                    words += current.toString()
                    current.clear()
                }
            }
            current.append(character)
        }
        if (current.isNotEmpty()) words += current.toString()
        return words.map(String::lowercase)
    }

    /** True when every query token matches a path segment or identifier word. */
    fun matches(node: FileTreeNode, tokens: List<String>): Boolean = tokens.all { token ->
        node.searchSegments.any { score(it, token, fuzzy = false) != null } ||
            node.searchWords.any { score(it, token, fuzzy = true) != null }
    }

    /** Lower is better; exact, prefix, boundary, substring, then fuzzy subsequence. */
    fun score(value: String, query: String, fuzzy: Boolean): Int? {
        if (value.isEmpty() || query.isEmpty()) return null
        if (value == query) return 0
        val lengthPenalty = (value.length - query.length).coerceIn(0, 64)
        if (value.startsWith(query)) return 2 + lengthPenalty
        val boundary = listOf('/', '-', '_', '.').mapNotNull { marker ->
            value.indexOf("$marker$query").takeIf { it >= 0 }?.plus(1)
        }.minOrNull()
        if (boundary != null) return 4 + boundary * 2 + lengthPenalty
        val substring = value.indexOf(query)
        if (substring >= 0) return 6 + substring * 2 + lengthPenalty
        if (!fuzzy) return null

        var queryIndex = 0
        var first = -1
        var previous = -1
        var gaps = 0
        value.forEachIndexed { index, character ->
            if (queryIndex < query.length && character == query[queryIndex]) {
                if (first < 0) first = index
                if (previous >= 0) gaps += index - previous - 1
                previous = index
                queryIndex++
            }
        }
        if (queryIndex != query.length) return null
        val spanPenalty = previous - first + 1 - query.length
        return 100 + first * 2 + gaps * 3 + spanPenalty + lengthPenalty
    }
}

private val FILE_NODE_COMPARATOR = Comparator<FileTreeNode> { left, right ->
    when {
        left.isDirectory != right.isDirectory -> if (left.isDirectory) -1 else 1
        else -> naturalCompare(left.name, right.name)
    }
}

/** Case-insensitive, numeric-aware ordering matching Finder's standard comparison. */
private fun naturalCompare(left: String, right: String): Int {
    var li = 0
    var ri = 0
    while (li < left.length && ri < right.length) {
        val lc = left[li]
        val rc = right[ri]
        if (lc.isDigit() && rc.isDigit()) {
            val lStart = li
            val rStart = ri
            while (li < left.length && left[li].isDigit()) li++
            while (ri < right.length && right[ri].isDigit()) ri++
            val lDigits = left.substring(lStart, li).trimStart('0')
            val rDigits = right.substring(rStart, ri).trimStart('0')
            val significant = lDigits.length.compareTo(rDigits.length)
            if (significant != 0) return significant
            val digits = lDigits.compareTo(rDigits)
            if (digits != 0) return digits
            continue
        }
        val compared = lc.lowercaseChar().compareTo(rc.lowercaseChar())
        if (compared != 0) return compared
        li++
        ri++
    }
    return (left.length - li).compareTo(right.length - ri)
}
