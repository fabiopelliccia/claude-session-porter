package com.github.fabiopelliccia.claudesessionsimportexport.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/**
 * The identity an export anonymizes with is built from what is really exported (WP4/WP5 review, finding 3):
 * whose Claude home it is, and which operating system the paths of the sessions are written for.
 */
class ExportIdentityTest : ExportingTest() {

    private fun exportWith(sessions: List<SessionInfo>, home: java.nio.file.Path, identity: SourceIdentity): SessionArchive.AnonymizationInfo {
        val archive = root.resolve("export.zip")
        SessionArchive.export(sessions, archive, home = home, identity = identity)
        return SessionArchive.readArchiveManifest(archive).anonymization!!
    }

    @Test
    fun `the source OS is the one of the paths the sessions hold, not the one of the machine that exports`() {
        // A Windows account exporting the Claude home of a WSL session: its paths are POSIX paths.
        val home = claudeHomeOf("wslbox")
        val session = writeSession(home, "s-1", "/home/alice/proj")

        val info = exportWith(listOf(session), home, SourceIdentity(home = """C:\Users\svc-build""", roots = emptyList()))

        assertEquals("posix", info.sourceOs)
    }

    @Test
    fun `sessions of both kinds, or none that tells, fall back to the OS of the identity`() {
        val home = claudeHomeOf("mixed")
        val windows = writeSession(home, "s-w", """C:\Users\alice\proj""")
        val posix = writeSession(home, "s-p", "/home/alice/proj")

        assertEquals("windows", exportWith(listOf(windows, posix), home, SourceIdentity("""C:\Users\alice""")).sourceOs)
        assertEquals("posix", exportWith(listOf(windows, posix), home, SourceIdentity("/home/alice")).sourceOs)
        // A session that records no working directory tells nothing either.
        val noPath = windows.copy(projectPath = null)
        assertEquals("posix", exportWith(listOf(noPath), home, SourceIdentity("/home/alice")).sourceOs)
        assertEquals("windows", exportWith(listOf(noPath), home, SourceIdentity("""C:\Users\alice""")).sourceOs)
    }

    @Test
    fun `an export that detects its identity anonymizes the home the Claude home is in, not only the running account's`() {
        // The Claude home is alice's; the tests run as somebody else, so the real user.home is of no help.
        val owner = root.resolve("alice")
        val home = owner.resolve(".claude")
        val cwd = owner.resolve("proj").toString()
        val note = owner.resolve("notes.txt").toString()
        val session = writeSession(home, "s-1", cwd, content = "see $note and ${home.resolve("projects").resolve("x")}")
        Files.writeString(auxDirOf(home, cwd, "s-1").resolve("out.txt"), "wrote $note\n")

        val archive = root.resolve("export.zip")
        // The identity is detected from the Claude home, as an export without one does; the running account is
        // made up, so that nothing depends on the real user.home or on a TEMP written in its 8.3 form.
        val identity = SourceIdentity.detect(claudeHome = home, home = root.resolve("svc-runner").toString(), tempFolders = emptyList(), accountName = "svc-runner")
        val outcome = SessionArchive.export(listOf(session), archive, home = home, identity = identity)

        assertEquals(setOf("CLAUDE_HOME", "HOME", "ROOT"), outcome.replacedByKind.keys)
        for ((name, bytes) in entriesOf(archive)) {
            val text = String(bytes, Charsets.UTF_8)
            // Nothing of the folder the tests live in is left, which is where alice's home is.
            assertFalse("$name holds the path of the home", text.contains(root.toString()) || text.contains(root.toString().replace("\\", "\\\\")))
        }
        assertEquals("wrote {{SP:HOME:${styleOfPlatform()}}}${separator()}notes.txt\n", textOf(archive, "sessions/s-1/aux/tool-results/out.txt"))
        assertTrue(textOf(archive, "sessions/s-1/transcript.jsonl").contains("{{SP:CLAUDE_HOME:"))
    }

    private fun styleOfPlatform(): String = if (ClaudePaths.isWindowsStyle(root.toString())) "win" else "fwd"

    private fun separator(): String = if (ClaudePaths.isWindowsStyle(root.toString())) "\\" else "/"
}
