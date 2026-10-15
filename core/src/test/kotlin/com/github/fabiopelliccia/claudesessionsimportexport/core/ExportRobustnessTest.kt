package com.github.fabiopelliccia.claudesessionsimportexport.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * What the export does with files that are not what it expects (WP4/WP5 review, findings 6, 7, 10 and 13):
 * a transcript still being written, a file too large to read whole, a file that cannot be read, a
 * destination that must not be left broken, and a file name that looks like a token.
 */
class ExportRobustnessTest : ExportingTest() {

    private val aliceRoot = """C:\Users\alice\proj"""
    private val aliceIdentity = SourceIdentity("""C:\Users\alice""")
    private val archive: Path get() = root.resolve("export.zip")

    private fun exporter(
        sessions: List<SessionInfo>,
        home: Path,
        largeFileBytes: Long = LARGE_FILE_BYTES,
        readBytes: (Path) -> ByteArray = Files::readAllBytes,
        progress: TransferProgress = TransferProgress.NOOP,
    ) = SessionExporter(sessions, archive, home, progress, ProducerInfo(), aliceIdentity, largeFileBytes, readBytes)

    private fun transcriptFile(home: Path, id: String): Path = projectDirOf(home, aliceRoot).resolve("$id.jsonl")

    /** Writes the session and then replaces its transcript with [bytes], which may end in the middle of a line. */
    private fun sessionWithTranscript(home: Path, id: String, bytes: ByteArray): SessionInfo {
        val session = writeSession(home, id, aliceRoot)
        Files.write(transcriptFile(home, id), bytes)
        return session
    }

    private val twoLines: String
        get() = userLine("s-1", aliceRoot, "ciao") + "\n" + userLine("s-1", aliceRoot, "ancora", second = 5) + "\n"

    /** [text] as the archive holds it: the project folder of every line is the token of the root. */
    private fun anonymized(text: String): String = text.replace("C:\\\\Users\\\\alice\\\\proj", "{{SP:ROOT:win}}")

    // ---------------------------------------------------------------------- partial lines (6, 10) --

    @Test
    fun `a transcript cut inside a multi-byte character exports its whole lines and drops the fragment, with a warning`() {
        // The reviewer's scenario: the writer was stopped half way through the ì of "così".
        val home = claudeHomeOf("cut")
        val fragment = """{"type":"user","message":{"content":"perché è così""".toByteArray(Charsets.UTF_8)
        val session = sessionWithTranscript(home, "s-1", twoLines.toByteArray(Charsets.UTF_8) + fragment.copyOf(fragment.size - 1))

        val outcome = exporter(listOf(session), home).run()

        assertEquals(1, outcome.exportedCount)
        assertEquals(0, outcome.unreadableFiles)
        assertEquals(1, outcome.partialLinesDropped)
        assertEquals(anonymized(twoLines), textOf(archive, "sessions/s-1/transcript.jsonl"))
        assertTrue(outcome.warnings.single(), outcome.warnings.single().contains("transcript.jsonl"))
    }

    @Test
    fun `a subagent transcript cut inside a character keeps the session and drops the fragment`() {
        val home = claudeHomeOf("subcut")
        val session = writeSession(home, "s-1", aliceRoot)
        val subagent = projectDirOf(home, aliceRoot).resolve("s-1").resolve("subagents")
        Files.createDirectories(subagent)
        val fragment = """{"type":"user","text":"così""".toByteArray(Charsets.UTF_8)
        Files.write(subagent.resolve("agent-a1.jsonl"), (userLine("s-1", aliceRoot, "uno") + "\n").toByteArray() + fragment.copyOf(fragment.size - 1))

        val outcome = exporter(listOf(session), home).run()

        assertEquals(1, outcome.exportedCount)
        assertEquals(0, outcome.unreadableFiles)
        assertEquals(1, outcome.partialLinesDropped)
        assertEquals(1, textOf(archive, "sessions/s-1/aux/subagents/agent-a1.jsonl").count { it == '\n' })
        assertTrue(outcome.warnings.single(), outcome.warnings.single().contains("subagents/agent-a1.jsonl"))
    }

    @Test
    fun `an unfinished line after CRLF lines is dropped and the CRLF of the others stays`() {
        val home = claudeHomeOf("crlf")
        val whole = twoLines.replace("\n", "\r\n")
        val session = sessionWithTranscript(home, "s-1", (whole + """{"type":"assistant","message":{"role":"assis""").toByteArray())

        val outcome = exporter(listOf(session), home).run()

        val archived = textOf(archive, "sessions/s-1/transcript.jsonl")
        assertEquals(1, outcome.partialLinesDropped)
        assertEquals(anonymized(whole), archived)
        assertFalse(archived.contains("\"assis"))
    }

    @Test
    fun `a blank tail after the last newline is not a partial line`() {
        val home = claudeHomeOf("blank")
        val session = sessionWithTranscript(home, "s-1", (twoLines + "  \t\r").toByteArray())

        val outcome = exporter(listOf(session), home).run()

        assertEquals(0, outcome.partialLinesDropped)
        assertEquals(emptyList<String>(), outcome.warnings)
    }

    @Test
    fun `a complete last line without a newline is kept, and gets its newline so that a later append does not glue to it`() {
        val home = claudeHomeOf("nonewline")
        val session = sessionWithTranscript(home, "s-1", (twoLines + userLine("s-1", aliceRoot, "ultima", second = 9)).toByteArray())

        val outcome = exporter(listOf(session), home).run()

        assertEquals(0, outcome.partialLinesDropped)
        assertEquals(emptyList<String>(), outcome.warnings)
        val archived = textOf(archive, "sessions/s-1/transcript.jsonl")
        assertTrue(archived.endsWith("\n"))
        assertEquals(3, archived.count { it == '\n' })
        assertTrue(archived.contains("ultima"))
    }

    @Test
    fun `a transcript of one complete line without a newline is exported`() {
        val home = claudeHomeOf("oneline")
        val session = sessionWithTranscript(home, "s-1", userLine("s-1", aliceRoot, "solo").toByteArray())

        val outcome = exporter(listOf(session), home).run()

        assertEquals(1, outcome.exportedCount)
        assertEquals(userLine("s-1", "{{SP:ROOT:win}}", "solo") + "\n", textOf(archive, "sessions/s-1/transcript.jsonl"))
    }

    @Test
    fun `a session left with no complete line is skipped with a warning, the others are exported`() {
        val home = claudeHomeOf("empty")
        val unfinished = sessionWithTranscript(home, "s-1", """{"type":"user","message":{"role":"us""".toByteArray())
        val empty = sessionWithTranscript(home, "s-2", ByteArray(0))
        val blank = sessionWithTranscript(home, "s-3", "\n  \n".toByteArray())
        val fine = writeSession(home, "s-4", aliceRoot)

        val outcome = exporter(listOf(unfinished, empty, blank, fine), home).run()

        assertEquals(1, outcome.exportedCount)
        assertEquals(listOf("s-4"), SessionArchive.readManifest(archive).map { it.id })
        assertEquals(setOf("manifest.json", "sessions/s-4/transcript.jsonl"), entriesOf(archive).keys.filter { "s-4" in it || it == "manifest.json" }.toSet())
        assertTrue(entriesOf(archive).keys.none { "s-1" in it || "s-2" in it || "s-3" in it })
        assertEquals(listOf(unfinished, empty, blank).map { ClaudeSessionsBundle.message("export.warning.emptySession", it.displayName) }, outcome.warnings)
        // A skipped session is not a failure to read, and its fragment is not reported on its own.
        assertEquals(0, outcome.unreadableFiles)
        assertEquals(0, outcome.partialLinesDropped)
    }

    // ----------------------------------------------------------------------- large files (7) --

    private fun historyFile(home: Path, id: String, name: String): Path =
        Files.createDirectories(home.resolve("file-history").resolve(id)).resolve(name)

    @Test
    fun `a file above the limit is copied as it is and scanned in chunks, a hit across two chunks included`() {
        val home = claudeHomeOf("large")
        val session = writeSession(home, "s-1", aliceRoot)
        val limit = 1024 * 1024L
        // 3 MB of filler with the home of the user placed across the border of the first and the second chunk.
        val clean = ByteArray(3 * 1024 * 1024) { 'x'.code.toByte() }
        val leaking = clean.copyOf()
        val path = """C:\Users\alice\secret.txt""".toByteArray()
        System.arraycopy(path, 0, leaking, SessionExporter.SCAN_CHUNK_BYTES - 5, path.size)
        Files.write(historyFile(home, "s-1", "clean@v1"), clean)
        Files.write(historyFile(home, "s-1", "leaking@v1"), leaking)

        val outcome = exporter(listOf(session), home, largeFileBytes = limit).run()

        assertEquals(listOf("sessions/s-1/file-history/leaking@v1"), outcome.binaryEntriesWithHits)
        assertTrue(clean.contentEquals(entriesOf(archive).getValue("sessions/s-1/file-history/clean@v1")))
        assertTrue(leaking.contentEquals(entriesOf(archive).getValue("sessions/s-1/file-history/leaking@v1")))
        assertTrue(outcome.hasPrivacyFindings)
    }

    @Test
    fun `a file below the limit is still anonymized as text`() {
        val home = claudeHomeOf("small")
        val session = writeSession(home, "s-1", aliceRoot)
        Files.writeString(historyFile(home, "s-1", "main@v1"), "see C:\\Users\\alice\\x\n")

        val outcome = exporter(listOf(session), home, largeFileBytes = 1024 * 1024L).run()

        assertEquals("see {{SP:HOME:win}}\\x\n", textOf(archive, "sessions/s-1/file-history/main@v1"))
        assertEquals(emptyList<String>(), outcome.binaryEntriesWithHits)
    }

    // ----------------------------------------------------------------- unreadable entries (7) --

    @Test
    fun `a file that cannot be read, even by an error, is reported and left out while everything else is exported`() {
        val home = claudeHomeOf("unreadable")
        val session = writeSession(home, "s-1", aliceRoot)
        Files.writeString(historyFile(home, "s-1", "big@v1"), "a")
        Files.writeString(historyFile(home, "s-1", "odd@v1"), "b")
        Files.writeString(historyFile(home, "s-1", "fine@v1"), "c")
        val failing = mapOf<String, () -> Nothing>(
            "big@v1" to { throw OutOfMemoryError("Java heap space") },
            "odd@v1" to { throw IllegalStateException("surprise") },
        )

        val outcome = exporter(listOf(session), home, readBytes = { file ->
            failing[file.fileName.toString()]?.invoke()
            Files.readAllBytes(file)
        }).run()

        assertEquals(1, outcome.exportedCount)
        assertEquals(2, outcome.unreadableFiles)
        assertEquals(2, outcome.warnings.size)
        val names = entriesOf(archive).keys
        assertTrue(names.toString(), "sessions/s-1/file-history/fine@v1" in names)
        assertTrue(names.toString(), names.none { "big@v1" in it || "odd@v1" in it })
        assertEquals(listOf("s-1"), SessionArchive.readManifest(archive).map { it.id })
    }

    @Test
    fun `a transcript that cannot be read leaves the session out and the others in`() {
        val home = claudeHomeOf("badtranscript")
        val broken = writeSession(home, "s-1", aliceRoot)
        val fine = writeSession(home, "s-2", aliceRoot)

        val outcome = exporter(listOf(broken, fine), home, readBytes = { file ->
            if (file.fileName.toString() == "s-1.jsonl") throw OutOfMemoryError("Java heap space")
            Files.readAllBytes(file)
        }).run()

        assertEquals(1, outcome.exportedCount)
        assertEquals(1, outcome.unreadableFiles)
        assertEquals(listOf("s-2"), SessionArchive.readManifest(archive).map { it.id })
    }

    // ----------------------------------------------------------------------- atomic archive (7) --

    @Test
    fun `an export that fails leaves the destination as it was and no temporary file next to it`() {
        val home = claudeHomeOf("atomic")
        val session = writeSession(home, "s-1", aliceRoot)
        Files.writeString(archive, "the archive of yesterday")
        var reports = 0
        val progress = TransferProgress { _, _ -> if (++reports == 2) throw IllegalStateException("cancelled") }

        assertThrows(IllegalStateException::class.java) { exporter(listOf(session, session), home, progress = progress).run() }

        assertEquals("the archive of yesterday", Files.readString(archive))
        assertEquals(setOf("export.zip", "atomic"), Files.list(root).use { files -> files.map { it.fileName.toString() }.toList() }.toSet())
    }

    @Test
    fun `an export that succeeds replaces the destination in one move and leaves nothing else next to it`() {
        val home = claudeHomeOf("replace")
        val session = writeSession(home, "s-1", aliceRoot)
        Files.writeString(archive, "the archive of yesterday")

        exporter(listOf(session), home).run()

        assertEquals(listOf("s-1"), SessionArchive.readManifest(archive).map { it.id })
        assertEquals(setOf("export.zip", "replace"), Files.list(root).use { files -> files.map { it.fileName.toString() }.toList() }.toSet())
    }

    // ---------------------------------------------------------------------- token prefix (13) --

    @Test
    fun `a file called like the start of a token forces the nonce prefix, wherever it is written`() {
        val home = claudeHomeOf("prefix")
        val session = writeSession(home, "s-1", aliceRoot)
        // `{{SP` without a colon is a name every file system accepts, and the import would take it for a token.
        Files.writeString(auxDirOf(home, aliceRoot, "s-1").resolve("{{SP-notes.txt"), "x")

        exporter(listOf(session), home).run()

        val prefix = SessionArchive.readArchiveManifest(archive).anonymization!!.tokenPrefix
        assertTrue(prefix, Regex("SP[0-9a-f]{4}").matches(prefix))
        assertTrue(entriesOf(archive).keys.contains("sessions/s-1/aux/tool-results/{{SP-notes.txt"))
    }

    @Test
    fun `a name that holds no marker keeps the default prefix`() {
        val home = claudeHomeOf("plainprefix")
        val session = writeSession(home, "s-1", aliceRoot)
        Files.writeString(auxDirOf(home, aliceRoot, "s-1").resolve("notes.txt"), "x")

        exporter(listOf(session), home).run()

        assertEquals("SP", SessionArchive.readArchiveManifest(archive).anonymization!!.tokenPrefix)
    }

    // ------------------------------------------------------------------- colliding entry names --

    @Test
    fun `two names that anonymize alike get a numbered second one, whatever the file system`() {
        val used = HashSet<String>()
        assertEquals("sessions/s/aux/{{SP:HOME:enc}}-notes.txt", uniqueEntryName("sessions/s/aux/{{SP:HOME:enc}}-notes.txt", used))
        assertEquals("sessions/s/aux/{{SP:HOME:enc}}-notes~2.txt", uniqueEntryName("sessions/s/aux/{{SP:HOME:enc}}-notes.txt", used))
        assertEquals("sessions/s/aux/{{SP:HOME:enc}}-notes~3.txt", uniqueEntryName("sessions/s/aux/{{SP:HOME:enc}}-notes.txt", used))
        // No extension, a dot only in the folder, a name that starts with a dot.
        assertEquals("sessions/s.d/aux/log", uniqueEntryName("sessions/s.d/aux/log", used))
        assertEquals("sessions/s.d/aux/log~2", uniqueEntryName("sessions/s.d/aux/log", used))
        assertEquals("sessions/s/aux/.hidden", uniqueEntryName("sessions/s/aux/.hidden", used))
        assertEquals("sessions/s/aux/.hidden~2", uniqueEntryName("sessions/s/aux/.hidden", used))
    }
}
