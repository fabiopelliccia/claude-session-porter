package com.github.fabiopelliccia.claudesessionsimportexport.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * What the import does with an archive somebody built by hand (WP4/WP5 review, findings 1 and 12): a
 * session id, a folder name or an entry name must never lead a file outside the Claude home, and a
 * manifest that cannot be rendered is refused by name.
 */
class ArchiveSafetyTest : CraftedArchiveTest() {

    private fun assertRefused(archive: Path, message: String = ClaudeSessionsBundle.message("error.unsafeArchive")) {
        val read = assertThrows(IOException::class.java) { SessionArchive.readArchiveManifest(archive) }
        assertEquals(message, read.message)
        val imported = assertThrows(IOException::class.java) { importCrafted(archive) }
        assertEquals(message, imported.message)
        assertEquals("nothing is written", emptyList<Path>(), filesWritten(archive))
    }

    // ----------------------------------------------------------------------------- finding 1 --

    @Test
    fun `a session id that climbs out of the Claude home refuses the whole archive`() {
        // The reviewer's scenario: with matching entries the import used to write escaped.jsonl and
        // escaped/payload.bat four folders above the Claude home.
        assertRefused(archiveOf("../../../../escaped"))
    }

    @Test
    fun `an absolute session id, Windows or POSIX, refuses the archive`() {
        for (id in listOf("C:\\escaped", "C:/escaped", "\\\\server\\share\\escaped", "/tmp/escaped", "/escaped", "D:escaped")) {
            assertRefused(archiveOf(id))
        }
    }

    @Test
    fun `an id that is not a plain file name refuses the archive`() {
        // Anything but letters, digits, dot, hyphen and underscore, and the two names that mean a folder.
        for (id in listOf("..", ".", "a/b", "a\\b", "", " ", "id with space", "id\u0000", "x:y", "a*b", "..\\..\\x")) {
            assertRefused(archiveOf("harmless", manifest = manifestJson(id)))
        }
    }

    @Test
    fun `a project folder name that climbs out of projects refuses a format 1 archive`() {
        // Format 1 without a target folder writes into `projects/<projectFolderName>`.
        for (folder in listOf("..", ".", "../../escaped", "..\\..\\escaped", "C:\\escaped", "/escaped", "")) {
            val manifest = manifestJson("s-1", folder = folder, formatVersion = 1, anonymization = null)
            val archive = archiveOf("s-1", manifest = manifest)
            val read = assertThrows(IOException::class.java) { SessionArchive.readArchiveManifest(archive) }
            assertEquals(ClaudeSessionsBundle.message("error.unsafeArchive"), read.message)
            val imported = assertThrows(IOException::class.java) {
                SessionArchive.import(archive, home = victimHome, targetProjectPath = null)
            }
            assertEquals(ClaudeSessionsBundle.message("error.unsafeArchive"), imported.message)
            assertEquals(emptyList<Path>(), filesWritten(archive))
        }
    }

    @Test
    fun `the tokens a format 2 archive uses as folder name and the ordinary ids are accepted`() {
        val archive = archiveOf("0b3a6f0e-1c52-4a53-9b35-4f9f3a0d7c11.v2_x")
        val session = SessionArchive.readArchiveManifest(archive).sessions.single()
        assertEquals("0b3a6f0e-1c52-4a53-9b35-4f9f3a0d7c11.v2_x", session.id)
        assertEquals("{{SP:ROOT:enc}}", session.projectFolderName)
    }

    @Test
    fun `an entry name that renders to an absolute path or climbs up is left out, the session still imports`() {
        val archive = archiveOf(
            "s-1",
            extra = mapOf(
                // HOME renders to the victim's home: an absolute path, which resolve() would take as it is.
                "sessions/s-1/aux/{{SP:HOME:win}}/pwned-home.txt" to "x",
                "sessions/s-1/aux/{{SP:HOME:fwd}}/pwned-fwd.txt" to "x",
                "sessions/s-1/aux/{{SP:CLAUDE_HOME:win}}/projects/pwned-claude.txt" to "x",
                "sessions/s-1/aux/../../../pwned-up.txt" to "x",
                "sessions/s-1/aux/..\\..\\pwned-back.txt" to "x",
                "sessions/s-1/file-history/../../pwned-history.txt" to "x",
                "sessions/s-1/aux/C:/pwned-drive.txt" to "x",
            ),
        )
        val outcome = importCrafted(archive)

        assertEquals(emptyList<ImportFailure>(), outcome.failures)
        assertEquals(1, outcome.imported.size)
        val written = filesWritten(archive).map { root.relativize(it).toString().replace('\\', '/') }
        assertTrue(written.toString(), written.none { "pwned" in it })
        // Everything that was written is in the Claude home.
        assertTrue(written.toString(), written.all { it.startsWith("victim/.claude/") })
    }

    @Test
    fun `the containment check refuses any path that is not strictly below its base once normalized`() {
        val base = root.resolve("home").resolve("projects")
        val outside = listOf(
            base.resolve("..").resolve("escaped.jsonl"),
            base.resolve("a").resolve("..").resolve("..").resolve("x"),
            base,
            root.resolve("home").resolve("projects-sibling").resolve("x"),
            root.resolve("other"),
        )
        for (path in outside) assertThrows(path.toString(), IOException::class.java) { SessionArchive.requireInside(base, path) }
        SessionArchive.requireInside(base, base.resolve("a").resolve("b").resolve("..").resolve("c.jsonl"))
    }

    @Test
    fun `a refused archive leaves an existing session alone, even under REPLACE`() {
        val existing = victimHome.resolve("projects").resolve("C--old").also { Files.createDirectories(it) }
        Files.writeString(existing.resolve("s-1.jsonl"), transcriptLine("s-1"))
        val archive = archiveOf("../s-1")
        assertThrows(IOException::class.java) { importCrafted(archive, ConflictPolicy.REPLACE) }
        assertTrue(Files.exists(existing.resolve("s-1.jsonl")))
    }

    // ---------------------------------------------------------------------------- finding 12 --

    @Test
    fun `a token prefix that is not a prefix refuses the archive as unreadable`() {
        for (prefix in listOf("x:y}}", "{{", "", "SP SP", "1SP", "S\\\\P")) {
            val manifest = manifestJson(
                "s-1",
                anonymization = """"anonymization":{"scheme":1,"tokenPrefix":${jsonString(prefix)},"kinds":[],"sourceOs":"windows"}""",
            )
            val archive = archiveOf("s-1", manifest = manifest)
            val error = assertThrows(prefix, IOException::class.java) { SessionArchive.readArchiveManifest(archive) }
            assertEquals(ClaudeSessionsBundle.message("error.unreadableManifest"), error.message)
            val imported = assertThrows(prefix, IOException::class.java) { importCrafted(archive) }
            assertEquals(ClaudeSessionsBundle.message("error.unreadableManifest"), imported.message)
        }
    }

    @Test
    fun `a null token prefix and a missing anonymization block are unreadable too`() {
        val nullPrefix = manifestJson("s-1", anonymization = """"anonymization":{"scheme":1,"tokenPrefix":null,"kinds":[],"sourceOs":"windows"}""")
        val missing = manifestJson("s-1", anonymization = null)
        for (manifest in listOf(nullPrefix, missing)) {
            val archive = archiveOf("s-1", manifest = manifest)
            val error = assertThrows(IOException::class.java) { SessionArchive.readArchiveManifest(archive) }
            assertEquals(ClaudeSessionsBundle.message("error.unreadableManifest"), error.message)
        }
    }

    @Test
    fun `an anonymization block with only some fields is read with the defaults`() {
        val manifest = manifestJson("s-1", anonymization = """"anonymization":{}""")
        val info = SessionArchive.readArchiveManifest(archiveOf("s-1", manifest = manifest)).anonymization!!
        assertEquals("SP", info.tokenPrefix)
    }
}
