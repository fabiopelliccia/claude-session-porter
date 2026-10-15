package com.github.fabiopelliccia.claudesessionsimportexport.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * How the messages and the progress of an export and an import name a session (WP6 review, finding 10), and the
 * reasons an archive is refused (finding 11): a front end that must not forward conversation text asks for ids,
 * and tells the refusals apart without reading their messages.
 */
class SessionLabelerTest : CraftedArchiveTest() {

    private val aliceRoot = """C:\Users\alice\proj"""
    private val aliceIdentity = SourceIdentity("""C:\Users\alice""")
    private val secret = "SECRET-PROMPT-TEXT"

    /** An export of a session whose last line is cut: the export warns about it, and reports progress. */
    private fun exportWith(labeler: SessionLabeler?): Triple<List<String>, List<String>, Path> {
        val home = root.resolve("alice").resolve(".claude")
        val projectDir = Files.createDirectories(home.resolve("projects").resolve(ClaudePaths.encodeProjectPath(aliceRoot)))
        val line = """{"type":"user","sessionId":"s-1","cwd":"C:\\Users\\alice\\proj","timestamp":"2024-03-10T09:00:00.000Z","message":{"role":"user","content":"$secret"}}"""
        Files.writeString(projectDir.resolve("s-1.jsonl"), line + "\n" + """{"type":"user","cw""")
        val session = SessionScanner.listSessions(home).single()
        val progress = mutableListOf<String>()
        val archive = root.resolve("e.zip")
        val reporter = TransferProgress { _, text -> progress += text }
        val outcome = if (labeler == null) {
            SessionArchive.export(listOf(session), archive, home, reporter, identity = aliceIdentity)
        } else {
            SessionArchive.export(listOf(session), archive, home, reporter, identity = aliceIdentity, labeler = labeler)
        }
        return Triple(outcome.warnings, progress, archive)
    }

    @Test
    fun `by default the export names a session by its title`() {
        val (warnings, progress, _) = exportWith(null)

        assertTrue(warnings.toString(), warnings.any { it.contains(secret) })
        assertTrue(progress.toString(), progress.any { it.contains(secret) })
    }

    @Test
    fun `with ids only the export names a session by its id and says nothing of its conversation`() {
        val (warnings, progress, _) = exportWith(SessionLabeler.ID)

        assertEquals(1, warnings.size)
        assertTrue(warnings.single(), warnings.single().startsWith("s-1:"))
        assertTrue(progress.toString(), progress.any { it.contains("s-1") })
        assertFalse((warnings + progress).toString(), (warnings + progress).any { it.contains(secret) })
    }

    @Test
    fun `with ids only the import names a session by its id too`() {
        val (_, _, archive) = exportWith(SessionLabeler.ID)
        val progress = mutableListOf<String>()

        SessionArchive.import(
            archive, targetProjectPath = victimProject, home = victimHome, userHome = victimUserHome.toString(),
            progress = { _, text -> progress += text }, labeler = SessionLabeler.ID,
        )
        assertTrue(progress.toString(), progress.any { it.contains("s-1") })
        assertFalse(progress.toString(), progress.any { it.contains(secret) })

        val withTitles = mutableListOf<String>()
        SessionArchive.import(
            archive, targetProjectPath = victimProject, home = victimHome, userHome = victimUserHome.toString(),
            progress = { _, text -> withTitles += text },
        )
        assertTrue("the title is still what the default shows: $withTitles", withTitles.any { it.contains(secret) })
    }

    @Test
    fun `an import without a transcript warns by the label too`() {
        val archive = writeArchive(root.resolve("bare.zip"), manifestJson("s-bare"), emptyMap())
        val outcome = SessionArchive.import(
            archive, targetProjectPath = victimProject, home = victimHome, userHome = victimUserHome.toString(), labeler = SessionLabeler.ID,
        )
        assertTrue(outcome.warnings.toString(), outcome.warnings.single().startsWith("s-bare:"))
    }

    // ------------------------------------------------------------------------------------ reasons --

    private fun reasonOf(archive: Path): UnusableArchiveException.Reason =
        assertThrows(UnusableArchiveException::class.java) { SessionArchive.readArchiveManifest(archive) }.reason

    @Test
    fun `every refusal of an archive says why, and is still an IOException`() {
        val notZip = Files.writeString(root.resolve("notes.zip"), "just some text")
        assertEquals(UnusableArchiveException.Reason.NOT_A_ZIP, reasonOf(notZip))
        assertThrows(IOException::class.java) { SessionArchive.readArchiveManifest(notZip) }

        // A ZIP of one unrelated file.
        val noManifest = root.resolve("nomanifest.zip")
        java.util.zip.ZipOutputStream(Files.newOutputStream(noManifest)).use {
            it.putNextEntry(java.util.zip.ZipEntry("hello.txt"))
            it.write("hi".toByteArray())
            it.closeEntry()
        }
        assertEquals(UnusableArchiveException.Reason.NO_MANIFEST, reasonOf(noManifest))

        assertEquals(UnusableArchiveException.Reason.TOO_NEW, reasonOf(writeArchive(root.resolve("new.zip"), manifestJson("s-1", formatVersion = 99), emptyMap())))
        assertEquals(UnusableArchiveException.Reason.UNSAFE, reasonOf(writeArchive(root.resolve("unsafe.zip"), manifestJson("../x"), emptyMap())))
        val foreign = writeArchive(root.resolve("foreign.zip"), """{"producer":"Some other tool","sessions":[{"name":"x"}],"formatVersion":1,"exportedAt":"x"}""", emptyMap())
        assertEquals(UnusableArchiveException.Reason.NOT_CLAUDE, reasonOf(foreign))
        val unreadable = writeArchive(root.resolve("unreadable.zip"), manifestJson("s-1", anonymization = null), emptyMap())
        assertEquals(UnusableArchiveException.Reason.UNREADABLE_MANIFEST, reasonOf(unreadable))
    }
}
