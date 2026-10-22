package io.github.jcartu.hermex.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
        encodeDefaults = false
    }

    @Test
    fun `workspace roots decode string and object response shapes`() {
        val response = json.decodeFromString(
            WorkspacesResponse.serializer(),
            """
            {
              "workspaces": [
                "/srv/hermex",
                {"path": "/srv/clients/acme", "name": "Acme"}
              ],
              "last": "/srv/clients/acme"
            }
            """.trimIndent(),
        )

        val roots = response.workspaces!!
        assertEquals(2, roots.size)
        assertEquals("/srv/hermex", roots[0].path)
        assertNull(roots[0].name)
        assertEquals("/srv/clients/acme", roots[1].path)
        assertEquals("Acme", roots[1].name)
        assertEquals("/srv/clients/acme", response.last)
    }

    @Test
    fun `directory entries decode snake case directory flags and metadata`() {
        val response = json.decodeFromString(
            DirectoryListResponse.serializer(),
            """
            {
              "entries": [
                {
                  "name": "src",
                  "path": "/srv/hermex/src",
                  "type": "dir",
                  "size": 4096,
                  "modified": 1723456789.25,
                  "is_directory": true
                },
                {
                  "name": "README.md",
                  "path": "/srv/hermex/README.md",
                  "type": "file",
                  "size": 842,
                  "modified": 1723456799,
                  "is_dir": false,
                  "target_outside_workspace": true
                }
              ],
              "path": "/srv/hermex",
              "workspace": "/srv/hermex",
              "error": null
            }
            """.trimIndent(),
        )

        val entries = response.entries!!
        assertEquals(2, entries.size)

        val sourceDirectory = entries[0]
        assertEquals("src", sourceDirectory.name)
        assertEquals("/srv/hermex/src", sourceDirectory.id)
        assertEquals(4096, sourceDirectory.size)
        assertEquals(1723456789.25, sourceDirectory.modified!!, 0.0)
        assertEquals(true, sourceDirectory.isDirectory)
        assertTrue(sourceDirectory.isBrowsableDirectory)

        val readme = entries[1]
        assertEquals("README.md", readme.name)
        assertEquals(842, readme.size)
        assertEquals(1723456799.0, readme.modified!!, 0.0)
        assertEquals(false, readme.isDirectory)
        assertFalse(readme.isBrowsableDirectory)
        assertEquals(true, readme.targetOutsideWorkspace)
        assertEquals("/srv/hermex", response.path)
        assertEquals("/srv/hermex", response.workspace)
        assertNull(response.error)
    }

    @Test
    fun `file content response decodes content and metadata`() {
        val response = json.decodeFromString(
            FileResponse.serializer(),
            """
            {
              "content": "# Hermex\n\nNative client.\n",
              "path": "/srv/hermex/README.md",
              "name": "README.md",
              "language": "markdown",
              "size": 27,
              "lines": 3,
              "error": null
            }
            """.trimIndent(),
        )

        assertEquals("# Hermex\n\nNative client.\n", response.content)
        assertEquals("/srv/hermex/README.md", response.path)
        assertEquals("README.md", response.name)
        assertEquals("markdown", response.language)
        assertEquals(27, response.size)
        assertEquals(3, response.lines)
        assertNull(response.error)
    }

    @Test
    fun `unknown response fields are ignored`() {
        val response = json.decodeFromString(
            FileResponse.serializer(),
            """
            {
              "content": "print('ok')",
              "path": "/srv/hermex/check.py",
              "name": "check.py",
              "language": "python",
              "size": 11,
              "lines": 1,
              "etag": "sha256:abc123",
              "permissions": {"read": true}
            }
            """.trimIndent(),
        )

        assertEquals("print('ok')", response.content)
        assertEquals("check.py", response.name)
        assertEquals(1, response.lines)
    }

    @Test
    fun `skill linked files decode string array and recursive object unions`() {
        val stringResponse = json.decodeFromString(
            SkillDetailResponse.serializer(),
            """{"name":"single","linked_files":" README.md "}""",
        )
        val arrayResponse = json.decodeFromString(
            SkillDetailResponse.serializer(),
            """
            {
              "name": "array",
              "linked_files": [
                " docs/b.md ",
                ["docs/a.md", "docs/b.md"],
                null
              ]
            }
            """.trimIndent(),
        )
        val objectResponse = json.decodeFromString(
            SkillDetailResponse.serializer(),
            """
            {
              "name": "object",
              "linked_files": {
                "README.md": "root contents",
                "collection": [
                  " scripts/run.sh ",
                  ["notes.md", ""],
                  {"config.yml": "nested contents"}
                ],
                "nested": {"docs/setup.md": "setup contents"},
                "ignored": 7
              }
            }
            """.trimIndent(),
        )

        assertEquals(listOf("README.md"), stringResponse.linkedFiles)
        assertEquals(listOf("docs/a.md", "docs/b.md"), arrayResponse.linkedFiles)
        assertEquals(
            listOf("README.md", "config.yml", "docs/setup.md", "notes.md", "scripts/run.sh"),
            objectResponse.linkedFiles,
        )
    }
}
