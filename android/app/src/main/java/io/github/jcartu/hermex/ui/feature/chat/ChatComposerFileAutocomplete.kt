package io.github.jcartu.hermex.ui.feature.chat

/**
 * Port of `ComposerFilePathSearch.Match` (`FilePathSearch.swift`): one row of
 * the composer's `@` panel — the workspace-relative path that gets inserted,
 * plus what the row draws. ComposerUpgrades owns this file's panel and
 * controller; `ChatViewModel.loadComposerFileSuggestions` produces these rows.
 */
data class ComposerFileSuggestion(
    val path: String,
    val name: String,
    /** The folder the entry sits in, empty at the workspace root. */
    val parentPath: String,
    val isDirectory: Boolean,
)
