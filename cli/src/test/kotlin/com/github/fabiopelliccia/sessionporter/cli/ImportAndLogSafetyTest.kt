package com.github.fabiopelliccia.sessionporter.cli

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Paths

/** Findings 1 and 2 of the WP6 review: nothing of the user's is deleted or overwritten by a command that should not. */
class ImportAndLogSafetyTest : CliTest() {

    private val aliceWork = "C:\\Users\\alice\\work"

    private fun CliResult.errorCode(): String = json().getAsJsonObject("error").get("code").asString

    // ------------------------------------------------------------------------------- finding 1 --

    @Test
    fun `an empty --ids selects nothing, it never imports everything and never replaces anything`() {
        val alice = claudeHomeOf("alice")
        writeSession(alice, "s-1", aliceWork, title = "First")
        writeSession(alice, "s-2", aliceWork, title = "Second")
        val archive = exportArchive(alice, "s-1", "s-2")
        val bob = claudeHomeOf("bob")
        val target = Files.createDirectories(root.resolve("bob").resolve("work")).toString()
        val local = writeSession(bob, "s-1", target, title = "local only")

        for (ids in listOf("", " , ", " ")) {
            for (policy in listOf("replace", "duplicate", "skip")) {
                val result = run(
                    "--json", "--home", bob.toString(), "import", archive.toString(), "--ids", ids, "--policy", policy,
                    "--target", target, "--log-dir", logDir.toString(),
                )
                assertEquals("ids <$ids> policy $policy: ${result.out}", ExitCode.USAGE, result.exitCode)
                assertEquals("noSessions", result.errorCode())
            }
        }
        assertTrue("the local copy is still what it was", Files.readString(local).contains("local only"))
        assertEquals(listOf("s-1"), transcriptsIn(bob).map { it.fileName.toString().removeSuffix(".jsonl") })
    }

    @Test
    fun `an empty --ids on export is a usage error too`() {
        val alice = claudeHomeOf("alice")
        writeSession(alice, "s-1", aliceWork)
        val result = run("--json", "--home", alice.toString(), "export", "--ids", "", "--out", root.resolve("x.zip").toString(), "--log-dir", logDir.toString())
        assertEquals(ExitCode.USAGE, result.exitCode)
        assertEquals("noSessions", result.errorCode())
    }

    // ------------------------------------------------------------------------------- finding 2 --

    @Test
    fun `a log folder shared with other files loses none of them, and the new log stays`() {
        val alice = claudeHomeOf("alice")
        writeSession(alice, "s-1", aliceWork)
        Files.createDirectories(logDir)
        // 22 notes of the user whose names start like our logs, one of them sorting after the new log.
        val notes = (1..22).map { "export-notes-%02d.log".format(it) }
        notes.forEach { Files.writeString(logDir.resolve(it), "mine") }

        val result = run("--json", "--home", alice.toString(), "export", "--all", "--out", root.resolve("e.zip").toString(), "--log-dir", logDir.toString())

        assertEquals(0, result.exitCode)
        for (note in notes) assertEquals("$note was kept", "mine", Files.readString(logDir.resolve(note)))
        val logFile = Paths.get(result.data().get("logFile").asString)
        assertTrue("the log the JSON names exists", Files.isRegularFile(logFile))
        assertTrue(Files.readString(logFile).contains("exportedSessions = 1"))
    }

    @Test
    fun `two runs in the same second write two logs, the second never overwrites the first`() {
        val alice = claudeHomeOf("alice")
        writeSession(alice, "s-1", aliceWork)

        val first = run("--json", "--home", alice.toString(), "export", "--all", "--out", root.resolve("a.zip").toString(), "--log-dir", logDir.toString())
        val firstLog = Paths.get(first.data().get("logFile").asString)
        val firstContent = Files.readString(firstLog)
        val second = run("--json", "--home", alice.toString(), "export", "--all", "--out", root.resolve("b.zip").toString(), "--log-dir", logDir.toString())
        val secondLog = Paths.get(second.data().get("logFile").asString)

        assertEquals("export-20240312-083015.log", firstLog.fileName.toString())
        assertEquals("export-20240312-083015-2.log", secondLog.fileName.toString())
        assertEquals("the first log is untouched", firstContent, Files.readString(firstLog))
        assertTrue(Files.readString(secondLog).contains("exportedSessions = 1"))
    }

    @Test
    fun `the rotation keeps the newest logs of the same second in order`() {
        val alice = claudeHomeOf("alice")
        writeSession(alice, "s-1", aliceWork)
        Files.createDirectories(logDir)
        for (day in 1..19) Files.writeString(logDir.resolve("export-202401%02d-000000.log".format(day)), "old")

        val logs = (1..3).map {
            run("--json", "--home", alice.toString(), "export", "--all", "--out", root.resolve("e$it.zip").toString(), "--log-dir", logDir.toString())
                .data().get("logFile").asString
        }

        val kept = Files.list(logDir).use { it.map { p -> p.fileName.toString() }.toList() }.filter { it.startsWith("export-") }
        assertEquals(20, kept.size)
        // The newest log is there, and what went was the oldest of the old ones.
        assertTrue(Paths.get(logs.last()).fileName.toString() in kept)
        assertFalse("export-20240101-000000.log" in kept)
        assertFalse("export-20240102-000000.log" in kept)
    }
}
