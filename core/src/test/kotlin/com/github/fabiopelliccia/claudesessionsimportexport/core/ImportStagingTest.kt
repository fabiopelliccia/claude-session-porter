package com.github.fabiopelliccia.claudesessionsimportexport.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/**
 * A session that fails half way is not there at all, and a local copy it was to replace is still there
 * (WP4/WP5 review, finding 2).
 */
class ImportStagingTest : CraftedArchiveTest() {

    @Test
    fun `REPLACE with a corrupt entry leaves the local session as it was`() {
        val good = exportedSession("s-1")
        importCrafted(good)
        val before = treeOf(victimHome)
        assertTrue(before.toString(), before.keys.any { it.endsWith("s-1/tool-results/one.txt") })

        // The same session, as a newer archive whose auxiliary file cannot be read.
        val corrupt = corruptEntry(exportedSession("s-1", auxBody = "newer tool result\n".repeat(300)), "sessions/s-1/aux/tool-results/one.txt")
        val outcome = importCrafted(corrupt, ConflictPolicy.REPLACE)

        assertEquals(1, outcome.failures.size)
        assertEquals("s-1", outcome.failures.single().sessionId)
        assertEquals(emptyList<ImportedSession>(), outcome.imported)
        assertEquals("the local copy, the auxiliary folder and the backups survive", before, treeOf(victimHome))
    }

    @Test
    fun `a failure after the transcript was staged leaves nothing of the session behind`() {
        // The file history is the last thing a session writes: its corrupt entry used to leave the
        // transcript and the auxiliary folder in place, a session half there.
        val corrupt = corruptEntry(exportedSession("s-2"), "sessions/s-2/file-history/main@v1")
        val outcome = importCrafted(corrupt, ConflictPolicy.DUPLICATE)

        assertEquals(1, outcome.failures.size)
        assertEquals(emptyMap<String, String>(), treeOf(victimHome))
        assertEquals(emptyList<String>(), SessionScanner.listSessions(victimHome).map { it.id })
    }

    @Test
    fun `no staging folder is left in the Claude home after a failure or a success`() {
        val good = exportedSession("s-3")
        importCrafted(good)
        importCrafted(corruptEntry(exportedSession("s-3", auxBody = "again\n".repeat(400)), "sessions/s-3/aux/tool-results/one.txt"), ConflictPolicy.REPLACE)

        val children = Files.list(victimHome).use { entries -> entries.map { it.fileName.toString() }.toList().sorted() }
        assertEquals(listOf("file-history", "projects"), children)
    }

    @Test
    fun `REPLACE removes the old auxiliary files and file history, not only the transcript`() {
        // The local copy has files the archive does not: after REPLACE they are gone.
        importCrafted(exportedSession("s-4"))
        val projectDir = victimHome.resolve("projects").resolve(ClaudePaths.encodeProjectPath(ClaudePaths.normalizeProjectPath(victimProject)))
        Files.writeString(projectDir.resolve("s-4").resolve("tool-results").resolve("only-local.txt"), "x")
        Files.writeString(victimHome.resolve("file-history").resolve("s-4").resolve("only-local@v1"), "x")

        val outcome = importCrafted(exportedSession("s-4"), ConflictPolicy.REPLACE)

        assertEquals("replaced", outcome.imported.single().action)
        assertFalse(Files.exists(projectDir.resolve("s-4").resolve("tool-results").resolve("only-local.txt")))
        assertFalse(Files.exists(victimHome.resolve("file-history").resolve("s-4").resolve("only-local@v1")))
        assertTrue(Files.exists(projectDir.resolve("s-4").resolve("tool-results").resolve("one.txt")))
        assertTrue(Files.exists(victimHome.resolve("file-history").resolve("s-4").resolve("main@v1")))
    }
}
