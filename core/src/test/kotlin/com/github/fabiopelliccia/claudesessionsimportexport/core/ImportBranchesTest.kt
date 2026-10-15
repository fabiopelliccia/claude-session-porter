package com.github.fabiopelliccia.claudesessionsimportexport.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.util.zip.ZipFile

/**
 * The branches of the import that no other test reaches (WP4/WP5 review, test gaps): the byte-for-byte copy of a format 1
 * transcript, a file name that carries a collision counter, and what each conflict policy does to the local session.
 */
class ImportBranchesTest : CraftedArchiveTest() {

    /** An [ImportLog] that keeps the lines it is given, so that a test can ask what the import said it did. */
    private class RecordingLog : ImportLog {
        val lines = mutableListOf<String>()

        override fun section(title: String) { lines += title }
        override fun line(message: String) { lines += message }
        override fun kv(key: String, value: Any?) { lines += "$key = $value" }
        override fun failure(message: String, error: Throwable) { lines += "FAILURE: $message" }
        override fun child(title: String): ImportLog = this
    }

    // ------------------------------------------------------------ format 1, byte for byte --

    private fun format1Fixture() = root.resolve("format1-alice.zip").also { fixture ->
        javaClass.getResourceAsStream("/fixtures/format1-alice.zip").use { Files.copy(it!!, fixture) }
    }

    private fun archivedTranscript(archive: java.nio.file.Path): ByteArray = ZipFile(archive.toFile()).use { zip ->
        zip.getInputStream(zip.getEntry("sessions/s-v1/transcript.jsonl")).readBytes()
    }

    @Test
    fun `a format 1 transcript that needs no change is copied byte for byte`() {
        val fixture = format1Fixture()
        val log = RecordingLog()

        // No target folder, no timestamp shift, no conflict: nothing the rewriter would change.
        val outcome = SessionArchive.import(fixture, home = victimHome, targetProjectPath = null, shiftTimestampsToNow = false, log = log)

        assertEquals("new", outcome.imported.single().action)
        val written = victimHome.resolve("projects").resolve(outcome.imported.single().projectFolderName).resolve("s-v1.jsonl")
        assertTrue(archivedTranscript(fixture).contentEquals(Files.readAllBytes(written)))
        assertTrue(log.lines.toString(), log.lines.contains("transcript copied byte for byte"))
    }

    @Test
    fun `a format 1 transcript that is attached to a folder, or shifted in time, is rewritten`() {
        val fixture = format1Fixture()
        val attachedHome = root.resolve("attached").resolve(".claude")
        val shiftedHome = root.resolve("shifted").resolve(".claude")
        val log = RecordingLog()

        SessionArchive.import(fixture, home = attachedHome, targetProjectPath = victimProject, shiftTimestampsToNow = false, log = log)
        SessionArchive.import(fixture, home = shiftedHome, targetProjectPath = null, shiftTimestampsToNow = true, log = log)

        assertFalse(log.lines.toString(), log.lines.contains("transcript copied byte for byte"))
        val attached = Files.walk(attachedHome.resolve("projects")).use { it.filter { f -> f.fileName.toString() == "s-v1.jsonl" }.findFirst().get() }
        assertFalse(archivedTranscript(fixture).contentEquals(Files.readAllBytes(attached)))
    }

    // ------------------------------------------------------------------ names with a counter --

    @Test
    fun `entry names that carry a collision counter come back as ordinary file names`() {
        // What an export of two files whose names anonymize alike writes: the second one is numbered.
        val archive = archiveOf(
            "s-1",
            extra = mapOf(
                "sessions/s-1/aux/tool-results/{{SP:HOME:enc}}-notes.txt" to "one",
                "sessions/s-1/aux/tool-results/{{SP:HOME:enc}}-notes~2.txt" to "two",
                "sessions/s-1/aux/tool-results/{{SP:HOME:enc}}-plain~3" to "three",
            ),
        )

        val outcome = importCrafted(archive)

        assertEquals(emptyList<ImportFailure>(), outcome.failures)
        val home = ClaudePaths.encodeProjectPath(ClaudePaths.normalizeProjectPath(victimUserHome.toString()))
        val results = victimHome.resolve("projects").resolve(ClaudePaths.encodeProjectPath(ClaudePaths.normalizeProjectPath(victimProject)))
            .resolve("s-1").resolve("tool-results")
        assertEquals("one", Files.readString(results.resolve("$home-notes.txt")))
        assertEquals("two", Files.readString(results.resolve("$home-notes~2.txt")))
        assertEquals("three", Files.readString(results.resolve("$home-plain~3")))
        // No token is left in any name.
        Files.walk(victimHome).use { paths -> paths.forEach { assertFalse("$it", it.fileName.toString().contains("{{")) } }
    }

    // -------------------------------------------------------------------- conflict policies --

    @Test
    fun `SKIP leaves the local session, its files and its backups as they were and writes nothing`() {
        importCrafted(exportedSession("s-1"))
        val before = treeOf(victimHome)

        val outcome = importCrafted(exportedSession("s-1", auxBody = "a newer tool result\n".repeat(50)), ConflictPolicy.SKIP)

        assertEquals(listOf("s-1"), outcome.skipped)
        assertEquals(emptyList<ImportedSession>(), outcome.imported)
        assertEquals(before, treeOf(victimHome))
        // Not even a staging folder.
        assertEquals(listOf("file-history", "projects"), Files.list(victimHome).use { it.map { f -> f.fileName.toString() }.toList().sorted() })
    }

    @Test
    fun `DUPLICATE keeps the local session as it was and gives the copy its own files and backups`() {
        importCrafted(exportedSession("s-1"))
        val before = treeOf(victimHome)

        val outcome = importCrafted(exportedSession("s-1", auxBody = "the copy's tool result\n".repeat(50)), ConflictPolicy.DUPLICATE)

        val copy = outcome.imported.single()
        assertEquals("duplicated", copy.action)
        val after = treeOf(victimHome)
        // Everything that was there is there, with the same content.
        for ((path, content) in before) assertEquals(path, content, after[path])
        // And the copy has a transcript, an auxiliary folder and backups under its own id.
        val added = after.keys - before.keys
        assertTrue(added.toString(), added.any { it.endsWith("${copy.writtenId}.jsonl") })
        assertTrue(added.toString(), added.any { it.endsWith("${copy.writtenId}/tool-results/one.txt") })
        assertTrue(added.toString(), added.any { it.endsWith("file-history/${copy.writtenId}/main@v1") })
        assertEquals(3, added.size)
        assertTrue(after.getValue(added.first { it.endsWith("tool-results/one.txt") }).startsWith("the copy's tool result"))
    }
}
