package io.github.jcartu.hermex.ui.feature.workspace

import io.github.jcartu.hermex.model.WorkspaceEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FileTreeReducerTest {
    @Test
    fun `directory expansion is lazy and collapse retains its listing`() {
        var tree = LazyFileTree()
            .reduce(FileTreeAction.LoadStarted(".", 1))
            .reduce(
                FileTreeAction.Loaded(
                    ".",
                    1,
                    listOf(
                        WorkspaceEntry("src", "src", type = "dir"),
                        WorkspaceEntry("README.md", "README.md", type = "file"),
                    ),
                ),
            )
        assertFalse(tree.isLoaded("src"))
        assertEquals(listOf("src", "README.md"), tree.visibleNodes().map { it.node.path })

        tree = tree.reduce(FileTreeAction.ToggleDirectory("src"))
        assertTrue("src" in tree.expandedPaths)
        assertEquals(listOf("src", "README.md"), tree.visibleNodes().map { it.node.path })

        tree = tree.reduce(FileTreeAction.LoadStarted("src", 1))
            .reduce(FileTreeAction.Loaded("src", 1, listOf(WorkspaceEntry("Main.kt", "src/Main.kt", type = "file"))))
        assertEquals(listOf("src", "src/Main.kt", "README.md"), tree.visibleNodes().map { it.node.path })

        tree = tree.reduce(FileTreeAction.ToggleDirectory("src"))
        assertFalse("src" in tree.expandedPaths)
        assertTrue(tree.isLoaded("src"))
        assertEquals(listOf("src", "README.md"), tree.visibleNodes().map { it.node.path })

        tree = tree.reduce(FileTreeAction.ToggleDirectory("src"))
        assertEquals(listOf("src", "src/Main.kt", "README.md"), tree.visibleNodes().map { it.node.path })
    }

    @Test
    fun `stale directory result cannot overwrite latest listing`() {
        val tree = LazyFileTree()
            .reduce(FileTreeAction.LoadStarted("src", 1))
            .reduce(FileTreeAction.LoadStarted("src", 2))
            .reduce(FileTreeAction.Loaded("src", 1, listOf(WorkspaceEntry("Old.kt", "src/Old.kt"))))
            .reduce(FileTreeAction.Loaded("src", 2, listOf(WorkspaceEntry("New.kt", "src/New.kt"))))
        assertEquals(listOf("src/New.kt"), tree.childrenOf("src")?.map { it.path })
    }

    @Test
    fun `only latest navigation completion selects a file`() {
        val tree = LazyFileTree()
            .reduce(FileTreeAction.NavigationRequested("old/File.kt", 10))
            .reduce(FileTreeAction.NavigationRequested("new/File.kt", 11))
            .reduce(FileTreeAction.NavigationCompleted("old/File.kt", 10))
        assertNull(tree.selectedPath)
        assertEquals("new/File.kt", tree.pendingNavigationPath)

        val completed = tree.reduce(FileTreeAction.NavigationCompleted("new/File.kt", 11))
        assertEquals("new/File.kt", completed.selectedPath)
        assertNull(completed.pendingNavigationPath)
        assertTrue("new" in completed.expandedPaths)
    }

    @Test
    fun `refresh removes descendants of a directory that disappeared`() {
        var tree = LazyFileTree()
            .reduce(FileTreeAction.LoadStarted(".", 1))
            .reduce(FileTreeAction.Loaded(".", 1, listOf(WorkspaceEntry("src", "src", type = "dir"))))
            .reduce(FileTreeAction.LoadStarted("src", 1))
            .reduce(FileTreeAction.Loaded("src", 1, listOf(WorkspaceEntry("Main.kt", "src/Main.kt"))))
            .reduce(FileTreeAction.ToggleDirectory("src"))
        assertTrue(tree.isLoaded("src"))

        tree = tree.reduce(FileTreeAction.LoadStarted(".", 2))
            .reduce(FileTreeAction.Loaded(".", 2, emptyList()))
        assertFalse(tree.isLoaded("src"))
        assertFalse("src" in tree.expandedPaths)
    }
}
