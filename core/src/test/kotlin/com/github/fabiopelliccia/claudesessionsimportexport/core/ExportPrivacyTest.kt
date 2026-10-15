package com.github.fabiopelliccia.claudesessionsimportexport.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * Text that is not plain UTF-8, and the account identity outside the lines that carry it (WP4/WP5 review,
 * findings 4 and 5): what the export must not copy as it found it, and what the import must give back in the
 * encoding it was written in.
 */
class ExportPrivacyTest : ExportingTest() {

    private val aliceRoot = """C:\Users\alice\proj"""
    private val aliceHome = """C:\Users\alice"""
    private val archive: Path get() = root.resolve("export.zip")

    private fun export(sessions: List<SessionInfo>, home: Path): ExportOutcome =
        SessionArchive.export(sessions, archive, home = home, identity = SourceIdentity(aliceHome))

    // ----------------------------------------------------------------------- text encodings (4) --

    private val utf16LeBom = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
    private val utf16BeBom = byteArrayOf(0xFE.toByte(), 0xFF.toByte())

    private fun writeAux(home: Path, name: String, bytes: ByteArray) {
        Files.write(auxDirOf(home, aliceRoot, "s-1").resolve(name), bytes)
    }

    private fun entry(name: String): ByteArray = entriesOf(archive).getValue("sessions/s-1/aux/tool-results/$name")

    @Test
    fun `UTF-8 with NULs, UTF-16 with a BOM, either way round, and UTF-8 with a BOM are text, anonymized in their own encoding`() {
        val home = claudeHomeOf("encodings")
        val session = writeSession(home, "s-1", aliceRoot)
        // `find -print0`: valid UTF-8, with NULs between the names.
        writeAux(home, "find0.txt", "C:\\Users\\alice\\proj\\a.kt\u0000C:\\Users\\alice\\secret.txt\u0000".toByteArray())
        // PowerShell's `>`: UTF-16LE with a BOM; and the same the other way round.
        writeAux(home, "ps.txt", utf16LeBom + "C:\\Users\\alice\\secret.txt\r\nline 2\r\n".toByteArray(Charsets.UTF_16LE))
        writeAux(home, "be.txt", utf16BeBom + "C:\\Users\\alice\\secret.txt\n".toByteArray(Charsets.UTF_16BE))
        writeAux(home, "bom.txt", byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "C:\\Users\\alice\\secret.txt".toByteArray())

        val outcome = export(listOf(session), home)

        assertEquals("{{SP:ROOT:win}}\\a.kt\u0000{{SP:HOME:win}}\\secret.txt\u0000", String(entry("find0.txt"), Charsets.UTF_8))
        assertEquals(
            utf16LeBom.toList() + "{{SP:HOME:win}}\\secret.txt\r\nline 2\r\n".toByteArray(Charsets.UTF_16LE).toList(),
            entry("ps.txt").toList(),
        )
        assertEquals(
            utf16BeBom.toList() + "{{SP:HOME:win}}\\secret.txt\n".toByteArray(Charsets.UTF_16BE).toList(),
            entry("be.txt").toList(),
        )
        assertEquals("\uFEFF{{SP:HOME:win}}\\secret.txt", String(entry("bom.txt"), Charsets.UTF_8))
        assertEquals(emptyList<String>(), outcome.binaryEntriesWithHits)
        assertFalse(outcome.hasPrivacyFindings)
    }

    @Test
    fun `a file that is not text and holds an anchor or the bare name is reported, and so counts for a strict privacy check`() {
        val home = claudeHomeOf("binaries")
        val session = writeSession(home, "s-1", aliceRoot)
        // 0xFF is not valid in UTF-8 and the length is odd: not UTF-16 either.
        writeAux(home, "anchor.bin", byteArrayOf(0xFF.toByte(), 0x00, 0x01) + "C:\\Users\\alice\\x".toByteArray())
        writeAux(home, "name.bin", byteArrayOf(0xFF.toByte(), 0x00, 0x01) + "built by alice".toByteArray())
        writeAux(home, "clean.bin", byteArrayOf(0xFF.toByte(), 0x00, 0x01, 0x02))

        val outcome = export(listOf(session), home)

        assertEquals(
            listOf("sessions/s-1/aux/tool-results/anchor.bin", "sessions/s-1/aux/tool-results/name.bin"),
            outcome.binaryEntriesWithHits.sorted(),
        )
        assertTrue(outcome.hasPrivacyFindings)
    }

    @Test
    fun `the import gives text back in the encoding it was written in, with the destination's paths`() {
        val home = claudeHomeOf("roundtrip")
        val session = writeSession(home, "s-1", aliceRoot)
        writeAux(home, "find0.txt", "C:\\Users\\alice\\secret.txt\u0000x\u0000".toByteArray())
        writeAux(home, "ps.txt", utf16LeBom + "C:\\Users\\alice\\secret.txt\r\n".toByteArray(Charsets.UTF_16LE))
        writeAux(home, "be.txt", utf16BeBom + "C:\\Users\\alice\\secret.txt\n".toByteArray(Charsets.UTF_16BE))
        export(listOf(session), home)

        val bobHome = claudeHomeOf("bob")
        val bobUserHome = root.resolve("bob").toString()
        val bobProject = Files.createDirectories(root.resolve("bob").resolve("work")).toString()
        val outcome = SessionArchive.import(archive, home = bobHome, targetProjectPath = bobProject, userHome = bobUserHome)
        assertEquals(emptyList<FailedCheck>(), outcome.failedChecks)
        assertEquals(emptyList<ImportFailure>(), outcome.failures)

        val written = bobHome.resolve("projects").resolve(ClaudePaths.encodeProjectPath(ClaudePaths.normalizeProjectPath(bobProject)))
            .resolve("s-1").resolve("tool-results")
        val find0 = String(Files.readAllBytes(written.resolve("find0.txt")), Charsets.UTF_8)
        assertTrue(find0, find0.startsWith(bobUserHome) && find0.endsWith("secret.txt\u0000x\u0000"))
        val ps = Files.readAllBytes(written.resolve("ps.txt"))
        assertEquals(utf16LeBom.toList(), ps.take(2))
        val psText = String(ps, 2, ps.size - 2, Charsets.UTF_16LE)
        assertTrue(psText, psText.startsWith(bobUserHome) && psText.endsWith("secret.txt\r\n"))
        val be = Files.readAllBytes(written.resolve("be.txt"))
        assertEquals(utf16BeBom.toList(), be.take(2))
        val beText = String(be, 2, be.size - 2, Charsets.UTF_16BE)
        assertTrue(beText, beText.startsWith(bobUserHome) && beText.endsWith("secret.txt\n"))
    }

    // ---------------------------------------------------------------- the account identity (5) --

    private val email = "alice.rossi@pc1.example"
    private val gitName = "Alice Rossi"

    private fun identityLine(id: String): String = jsonLine(
        mapOf(
            "type" to "attachment", "sessionId" to id, "cwd" to aliceRoot, "timestamp" to "2024-03-10T09:00:01.000Z",
            "attachment" to mapOf(
                "type" to "session_context",
                "context" to mapOf("userEmail" to "The user's email address is $email.", "gitStatus" to "Current branch: main\n\nGit user: $gitName\nStatus: clean"),
            ),
        ),
    )

    private fun gitLogResult(id: String): String = jsonLine(
        mapOf(
            "type" to "user", "sessionId" to id, "cwd" to aliceRoot, "timestamp" to "2024-03-10T09:00:02.000Z",
            "message" to mapOf("role" to "user", "content" to "ok"),
            "toolUseResult" to mapOf("stdout" to "commit 1a2b3c\nAuthor: $gitName <$email>\n\n    fix\n\n    Co-Authored-By: $gitName <$email>\n"),
            "gitBranch" to "fix-by-$email",
        ),
    )

    /** Every place of [archive] - names, UTF-8 and UTF-16LE views of the content - that holds [needle], whatever the case. */
    private fun holders(needle: String): List<String> = entriesOf(archive).filter { (name, bytes) ->
        listOf(name, String(bytes, Charsets.UTF_8), String(bytes, Charsets.UTF_16LE)).any { it.contains(needle, ignoreCase = true) }
    }.keys.toList()

    @Test
    fun `the email and the git name found in a session context are removed from every text of every session, and counted`() {
        val home = claudeHomeOf("identity")
        val first = writeSession(home, "s-1", aliceRoot, extraLines = listOf(identityLine("s-1"), gitLogResult("s-1")))
        // A session that has no identity line of its own: it is known from the first one.
        val second = writeSession(home, "s-2", aliceRoot, extraLines = listOf(gitLogResult("s-2")))
        Files.writeString(auxDirOf(home, aliceRoot, "s-1").resolve("git.txt"), "Author: $gitName <$email>\r\n")
        Files.writeString(auxDirOf(home, aliceRoot, "s-2").resolve("git.txt"), "Reviewed-by: $gitName\n")
        val subagents = Files.createDirectories(projectDirOf(home, aliceRoot).resolve("s-1").resolve("subagents"))
        Files.writeString(subagents.resolve("agent-a1.jsonl"), jsonLine(mapOf("type" to "user", "message" to mapOf("content" to "mail to $email from ${gitName.uppercase()}"))) + "\n")
        val backups = Files.createDirectories(home.resolve("file-history").resolve("s-1"))
        Files.write(backups.resolve("main@v1"), ("// written by $email\n").toByteArray(Charsets.UTF_16LE).let { utf16LeBom + it })

        val outcome = export(listOf(first, second), home)

        assertEquals(emptyList<String>(), holders(email))
        assertEquals(emptyList<String>(), holders(gitName))
        assertEquals(emptyList<String>(), holders("alice"))
        assertEquals(0, outcome.residualUserNameHits)
        // Two stdout lines holding it twice, the branch of two lines, three more files and the history backup.
        assertTrue(outcome.identityValuesScrubbed.toString(), outcome.identityValuesScrubbed >= 10)
        assertEquals(emptyList<String>(), outcome.warnings)
        // The rest of a replaced line is as it was.
        val transcript = textOf(archive, "sessions/s-2/transcript.jsonl")
        assertTrue(transcript, transcript.contains("commit 1a2b3c\\nAuthor: ${SessionRedactor.PLACEHOLDER} <${SessionRedactor.PLACEHOLDER}>"))
        val manifest = SessionArchive.readArchiveManifest(archive)
        assertEquals("fix-by-${SessionRedactor.PLACEHOLDER}", manifest.sessions.first { it.id == "s-2" }.gitBranch)
    }

    @Test
    fun `a value that is too short to tell from a word, or that is only part of a longer one, is left alone`() {
        val home = claudeHomeOf("shortname")
        val line = jsonLine(
            mapOf(
                "type" to "attachment", "sessionId" to "s-1", "cwd" to aliceRoot, "timestamp" to "2024-03-10T09:00:01.000Z",
                "attachment" to mapOf("type" to "session_context", "context" to mapOf("gitStatus" to "Git user: Al\nStatus: clean")),
            ),
        )
        val session = writeSession(home, "s-1", aliceRoot, content = "Alibaba, Al Pacino and Alice Rossini", extraLines = listOf(line))

        val outcome = export(listOf(session), home)

        val transcript = textOf(archive, "sessions/s-1/transcript.jsonl")
        assertTrue(transcript, transcript.contains("Alibaba, Al Pacino and Alice Rossini"))
        assertEquals(0, outcome.identityValuesScrubbed)
    }

    @Test
    fun `an export of sessions that name nobody changes nothing about their lines`() {
        val home = claudeHomeOf("nobody")
        val session = writeSession(home, "s-1", aliceRoot, content = "Author: Somebody Else <somebody@else.example>")

        val outcome = export(listOf(session), home)

        assertEquals(0, outcome.identityValuesScrubbed)
        assertTrue(textOf(archive, "sessions/s-1/transcript.jsonl").contains("Author: Somebody Else <somebody@else.example>"))
    }
}
