package com.github.fabiopelliccia.claudesessionsimportexport.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * How a format 2 archive is read back on the destination machine (WP4/WP5 review, findings 8, 9 and 11): lines
 * that were not JSON on the way out stay raw on the way in, paths into the session's own files follow the id it
 * is written under, and the import table shows names instead of tokens.
 */
class ImportRenderingTest : ExportingTest() {

    private val aliceRoot = """C:\Users\alice\proj"""
    private val aliceHome = """C:\Users\alice"""
    private val archive: Path get() = root.resolve("export.zip")

    /** A destination that is spelled the Windows way whatever the machine running the test is. */
    private val bobHomeText = """C:\Users\bob"""
    private val bobProject = """C:\Users\bob\work"""

    private fun importInto(home: Path, policy: ConflictPolicy = ConflictPolicy.DUPLICATE, userHome: String = bobHomeText): ImportOutcome =
        SessionArchive.import(archive, conflictPolicy = policy, targetProjectPath = bobProject, home = home, userHome = userHome, shiftTimestampsToNow = false)

    private fun importedTranscript(home: Path, id: String): String =
        Files.readString(home.resolve("projects").resolve("C--Users-bob-work").resolve("$id.jsonl"))

    // ----------------------------------------------------------------------------- finding 8 --

    @Test
    fun `a line that is valid only for a lenient parser stays raw on the way out and on the way in`() {
        // The reviewer's line: unquoted keys, so not JSON; its paths are written as in a JSON text, doubled.
        val home = claudeHomeOf("lenient")
        val lenient = """{type:"user",cwd:"C:\\Users\\alice\\proj",sessionId:"s-1",message:{content:"see C:\\Users\\alice\\n.txt"}}"""
        val session = writeSession(home, "s-1", aliceRoot, extraLines = listOf(lenient))
        SessionArchive.export(listOf(session), archive, home = home, identity = SourceIdentity(aliceHome))
        assertTrue(textOf(archive, "sessions/s-1/transcript.jsonl").contains("""{type:"user",cwd:"{{SP:ROOT:esc1}}","""))

        importInto(claudeHomeOf("bob"))

        // Not re-serialized as JSON and not rendered a level too deep: only alice's names changed.
        val line = importedTranscript(claudeHomeOf("bob"), "s-1").lines().last { it.isNotBlank() }
        assertEquals("""{type:"user",cwd:"C:\\Users\\bob\\work",sessionId:"s-1",message:{content:"see C:\\Users\\bob\\n.txt"}}""", line)
    }

    // ----------------------------------------------------------------------------- finding 9 --

    private fun ownFiles(id: String, folder: String): String = """C:\Users\alice\.claude\projects\$folder\$id\tool-results\toolu_1.txt"""

    private fun sessionWithPathsIntoItsOwnFiles(home: Path): SessionInfo {
        val folder = ClaudePaths.encodeProjectPath(aliceRoot)
        val path = ownFiles("s-1", folder)
        val session = writeSession(home, "s-1", aliceRoot, content = "output saved to $path")
        Files.writeString(auxDirOf(home, aliceRoot, "s-1").resolve("out.txt"), "see $path\r\nand ${aliceHome}\\.claude\\file-history\\s-1\\main@v1\r\n")
        Files.writeString(home.resolve("file-history").resolve("s-1").also { Files.createDirectories(it) }.resolve("main@v1"), "// from $path\n")
        return session
    }

    private fun exportWithClaudeHome(home: Path) {
        val session = sessionWithPathsIntoItsOwnFiles(home)
        val identity = SourceIdentity(home = aliceHome, claudeHome = """C:\Users\alice\.claude""")
        SessionArchive.export(listOf(session), archive, home = home, identity = identity)
    }

    /** A path that holds [id] as one of its folders, the way the destination machine spells it. */
    private fun holdsFolder(text: String, id: String): Boolean = Regex("[\\\\/]" + Regex.escape(id) + "[\\\\/]").containsMatchIn(text)

    @Test
    fun `a path into the session's own files follows the new id of a duplicate, in the transcript and in every text file`() {
        val home = claudeHomeOf("alice")
        exportWithClaudeHome(home)
        val bobHome = claudeHomeOf("bob")
        importInto(bobHome) // the session is new here
        val duplicate = importInto(bobHome).imported.single()
        assertEquals("duplicated", duplicate.action)

        val newId = duplicate.writtenId
        val project = bobHome.resolve("projects").resolve("C--Users-bob-work")
        val transcript = importedTranscript(bobHome, newId)
        assertTrue(transcript, holdsFolder(transcript, newId))
        assertFalse(transcript, holdsFolder(transcript, "s-1"))
        // The Claude home of the destination is where the path starts, wherever that is.
        assertTrue(transcript, transcript.contains(bobHome.toAbsolutePath().toString().replace("\\", "\\\\")) || transcript.contains(bobHome.toAbsolutePath().toString()))

        val aux = Files.readString(project.resolve(newId).resolve("tool-results").resolve("out.txt"))
        assertTrue(aux, holdsFolder(aux, newId))
        assertFalse(aux, holdsFolder(aux, "s-1"))
        val history = Files.readString(bobHome.resolve("file-history").resolve(newId).resolve("main@v1"))
        assertTrue(history, holdsFolder(history, newId))
        assertFalse(history, holdsFolder(history, "s-1"))
    }

    @Test
    fun `a session that keeps its id keeps the paths into its files as they are`() {
        val home = claudeHomeOf("alice")
        exportWithClaudeHome(home)
        val bobHome = claudeHomeOf("bob")

        val outcome = importInto(bobHome).imported.single()

        assertEquals("s-1", outcome.writtenId)
        val transcript = importedTranscript(bobHome, "s-1")
        assertTrue(transcript, holdsFolder(transcript, "s-1"))
        assertTrue(transcript, Regex("projects[\\\\/]+C--Users-bob-work[\\\\/]+s-1[\\\\/]+tool-results").containsMatchIn(transcript))
    }

    @Test
    fun `a Claude home that is not under the home is rendered as the destination's own, whatever it is called`() {
        val home = claudeHomeOf("alice")
        exportWithClaudeHome(home)
        // Bob keeps his Claude Code files somewhere that is not <home>\.claude at all.
        val relocated = root.resolve("relocated").resolve("claude-data")

        importInto(relocated, userHome = bobHomeText)

        val transcript = importedTranscript(relocated, "s-1")
        assertTrue(transcript, transcript.contains("claude-data"))
        assertFalse(transcript, transcript.contains(".claude"))
        assertFalse(transcript, transcript.contains("alice", ignoreCase = true))
    }

    // ----------------------------------------------------------------------------- finding 11 --

    @Test
    fun `the import table shows names instead of tokens`() {
        val home = claudeHomeOf("names")
        val title = jsonLine(mapOf("type" to "summary", "summary" to "Fix $aliceRoot\\Main.kt and $aliceHome\\.claude\\x for the team"))
        val session = writeSession(home, "s-1", aliceRoot, extraLines = listOf(title))
        SessionArchive.export(listOf(session), archive, home = home, identity = SourceIdentity(home = aliceHome, claudeHome = """C:\Users\alice\.claude"""))
        // What the archive holds is what the import renders; only the table is made readable.
        assertTrue(textOf(archive, "manifest.json").contains("{{SP:ROOT:win}}"))

        val listed = SessionArchive.readManifest(archive).single()

        assertEquals("Fix proj\\Main.kt and ~/.claude\\x for the team", listed.displayName)
        assertEquals("proj", listed.projectName)
        // The values the import works with are still the tokens.
        assertEquals("{{SP:ROOT:win}}", listed.projectPath)
    }

    @Test
    fun `a token of the home reads as a tilde, and a root without a name as well`() {
        val home = claudeHomeOf("tilde")
        val title = jsonLine(mapOf("type" to "summary", "summary" to "Read $aliceHome\\notes.txt"))
        // The project is the home itself: its name is the user's own, which the manifest does not carry.
        val session = writeSession(home, "s-1", aliceHome, extraLines = listOf(title))
        SessionArchive.export(listOf(session), archive, home = home, identity = SourceIdentity(aliceHome))

        val listed = SessionArchive.readManifest(archive).single()

        assertEquals(null, listed.projectName)
        assertEquals("Read ~\\notes.txt", listed.displayName)
    }

    @Test
    fun `an archive of the first format is shown as it was`() {
        val fixture = root.resolve("format1-alice.zip")
        javaClass.getResourceAsStream("/fixtures/format1-alice.zip").use { Files.copy(it!!, fixture) }

        val listed = SessionArchive.readManifest(fixture).single()

        assertFalse(listed.displayName.contains("{{SP"))
        assertEquals("s-v1", listed.id)
    }
}
