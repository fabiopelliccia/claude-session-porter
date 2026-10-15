package com.github.fabiopelliccia.sessionporter.cli

import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class ImportCommandTest : CliTest() {

    private val aliceWork = "C:\\Users\\alice\\work"

    /** Alice's two sessions, exported; Bob's machine is where they are imported. */
    private fun archiveOfAlice(): Path {
        val alice = claudeHomeOf("alice")
        writeSession(alice, "s-1", aliceWork, title = "First")
        writeSession(alice, "s-2", aliceWork, title = "Second")
        return exportArchive(alice, "s-1", "s-2")
    }

    /** Bob's project folder, which exists, so that the diagnosis finds nothing to complain about. */
    private fun bobProject(): Path = Files.createDirectories(root.resolve("bob").resolve("work"))

    private fun import(archive: Path, bob: Path, vararg more: String, windows: Boolean = false) = run(
        "--home", bob.toString(), "import", archive.toString(), *more, "--log-dir", logDir.toString(), "--json", windows = windows,
    )

    private fun JsonObject.imported() = getAsJsonArray("imported").map { it.asJsonObject }

    @Test
    fun `import writes the sessions below the target folder and says how to resume them`() {
        val archive = archiveOfAlice()
        val bob = claudeHomeOf("bob")
        val project = bobProject()

        val result = import(archive, bob, "--target", project.toString())

        assertEquals(result.out + result.err, 0, result.exitCode)
        val json = result.json()
        assertTrue(json.get("ok").asBoolean)
        assertEquals("import", json.get("command").asString)
        val data = json.getAsJsonObject("data")
        val imported = data.imported().associateBy { it.get("sourceId").asString }
        assertEquals(setOf("s-1", "s-2"), imported.keys)
        val first = imported.getValue("s-1")
        assertEquals("s-1", first.get("writtenId").asString)
        assertEquals("new", first.get("action").asString)
        assertEquals(project.toString(), first.get("cwd").asString)
        assertEquals(2, first.get("messageCount").asInt)
        assertEquals(0, first.get("fileHistoryFiles").asInt)
        assertEquals("claude --resume s-1", first.get("resumeCommand").asString)
        assertEquals(project.toString(), data.get("target").asString)
        assertEquals("duplicate", data.get("policy").asString)
        assertEquals(0, data.getAsJsonArray("skipped").size())
        assertEquals(0, data.getAsJsonArray("failures").size())
        assertEquals(0, data.getAsJsonArray("failedChecks").size())

        val folder = bob.resolve("projects").resolve(first.get("projectFolderName").asString)
        assertTrue(Files.isRegularFile(folder.resolve("s-1.jsonl")))
        assertTrue("the transcript says where it now lives", Files.readString(folder.resolve("s-1.jsonl")).contains(project.toString().replace("\\", "\\\\")))
        assertFalse("no token is left", Files.readString(folder.resolve("s-1.jsonl")).contains("{{SP"))
    }

    @Test
    fun `the log of the import is written where --log-dir says and its path is reported`() {
        val archive = archiveOfAlice()
        val bob = claudeHomeOf("bob")

        val result = import(archive, bob, "--target", bobProject().toString())

        val logFile = java.nio.file.Paths.get(result.data().get("logFile").asString)
        assertEquals("import-20240312-083015.log", logFile.fileName.toString())
        assertEquals(logDir.toAbsolutePath(), logFile.parent)
        val log = Files.readString(logFile)
        assertTrue(log, log.contains("productName = Session Porter CLI"))
        assertTrue(log, log.contains("pluginVersion = ${CliVersion.value}"))
        assertTrue(log, log.contains("ideBuild = <null>"))
        assertFalse("no conversation text in the log", log.contains("First") || log.contains("Second"))
    }

    @Test
    fun `only the last 20 import logs are kept`() {
        val archive = archiveOfAlice()
        Files.createDirectories(logDir)
        for (day in 1..25) Files.writeString(logDir.resolve("import-202401%02d-000000.log".format(day)), "old")

        import(archive, claudeHomeOf("bob"), "--target", bobProject().toString())

        val logs = Files.list(logDir).use { it.map { p -> p.fileName.toString() }.toList() }.filter { it.startsWith("import-") }
        assertEquals(20, logs.size)
        assertTrue(logs.contains("import-20240312-083015.log"))
    }

    // ------------------------------------------------------------------------------- policies --

    @Test
    fun `the default policy imports a session the machine has as a copy under a new id`() {
        val archive = archiveOfAlice()
        val bob = claudeHomeOf("bob")
        val target = bobProject().toString()
        import(archive, bob, "--target", target)

        val second = import(archive, bob, "--target", target, "--ids", "s-1")

        assertEquals(second.out + second.err, 0, second.exitCode)
        val session = second.data().imported().single()
        assertEquals("duplicated", session.get("action").asString)
        assertTrue(session.get("writtenId").asString != "s-1")
        assertEquals(3, transcriptsIn(bob).size)
    }

    @Test
    fun `--policy skip leaves the local session alone and reports the id`() {
        val archive = archiveOfAlice()
        val bob = claudeHomeOf("bob")
        val target = bobProject().toString()
        import(archive, bob, "--target", target)

        val result = import(archive, bob, "--target", target, "--policy", "skip")

        assertEquals(result.out + result.err, 0, result.exitCode)
        val data = result.data()
        assertEquals(0, data.getAsJsonArray("imported").size())
        assertEquals(setOf("s-1", "s-2"), data.getAsJsonArray("skipped").map { it.asString }.toSet())
        assertEquals("skip", data.get("policy").asString)
        assertEquals(2, transcriptsIn(bob).size)
    }

    @Test
    fun `--policy replace puts the archived session where the local one was`() {
        val archive = archiveOfAlice()
        val bob = claudeHomeOf("bob")
        val target = bobProject().toString()
        // Bob already has a session with that id, which says something else.
        val transcript = writeSession(bob, "s-1", target, title = "local only")

        val result = import(archive, bob, "--target", target, "--policy", "REPLACE", "--ids", "s-1")

        assertEquals(result.out + result.err, 0, result.exitCode)
        assertEquals("replaced", result.data().imported().single().get("action").asString)
        val written = Files.readString(transcript)
        assertFalse("the local copy is gone", written.contains("local only"))
        assertTrue(written, written.contains("First"))
        assertEquals(1, transcriptsIn(bob).size)
    }

    @Test
    fun `--ids imports only those sessions, and an id the archive lacks is a usage error`() {
        val archive = archiveOfAlice()
        val bob = claudeHomeOf("bob")
        val target = bobProject().toString()

        val one = import(archive, bob, "--target", target, "--ids", "s-2")
        assertEquals(listOf("s-2"), one.data().imported().map { it.get("sourceId").asString })

        val wrong = import(archive, bob, "--target", target, "--ids", "s-2,nope")
        assertEquals(ExitCode.USAGE, wrong.exitCode)
        assertEquals("unknownSessions", wrong.json().getAsJsonObject("error").get("code").asString)
    }

    // --------------------------------------------------------------------------------- target --

    @Test
    fun `a Git Bash target becomes the folder Claude Code looks in`() {
        val archive = archiveOfAlice()
        val bob = claudeHomeOf("bob")

        // `$PWD` of Git Bash on Windows. The folder does not exist on the machine of the tests, so the diagnosis
        // reports that and nothing else.
        val result = import(archive, bob, "--target", "/c/Users/bob/proj", windows = true)

        val data = result.data()
        assertEquals("C:\\Users\\bob\\proj", data.get("target").asString)
        val session = data.imported().first()
        assertEquals("C--Users-bob-proj", session.get("projectFolderName").asString)
        assertEquals("C:\\Users\\bob\\proj", session.get("cwd").asString)
        assertTrue(Files.isRegularFile(bob.resolve("projects").resolve("C--Users-bob-proj").resolve("s-1.jsonl")))
        assertEquals(setOf(8), data.getAsJsonArray("failedChecks").map { it.asJsonObject.get("number").asInt }.toSet())
        assertEquals(ExitCode.PARTIAL, result.exitCode)
        // The caller is told about the missing folder before the import runs.
        assertTrue(result.json().getAsJsonArray("warnings").any { it.asString.contains("C:\\Users\\bob\\proj") })
    }

    @Test
    fun `a format 2 archive needs --target`() {
        val result = import(archiveOfAlice(), claudeHomeOf("bob"))

        assertEquals(ExitCode.USAGE, result.exitCode)
        assertEquals("targetRequired", result.json().getAsJsonObject("error").get("code").asString)
        assertEquals("nothing is written", emptyList<Path>(), transcriptsIn(root.resolve("bob").resolve(".claude")))
    }

    @Test
    fun `--keep-original-folder is for format 1 only, and not together with --target`() {
        val format2 = import(archiveOfAlice(), claudeHomeOf("bob"), "--keep-original-folder")
        assertEquals(ExitCode.USAGE, format2.exitCode)
        assertEquals("keepOriginalFolderNotAllowed", format2.json().getAsJsonObject("error").get("code").asString)

        val format1 = formatOneArchive("s-v1")
        val both = import(format1, claudeHomeOf("carol"), "--keep-original-folder", "--target", bobProject().toString())
        assertEquals(ExitCode.USAGE, both.exitCode)
        assertEquals("targetAndKeepOriginal", both.json().getAsJsonObject("error").get("code").asString)
    }

    @Test
    fun `a format 1 archive keeps the folder it was recorded in, or is attached to --target`() {
        val format1 = formatOneArchive("s-v1")

        val kept = import(format1, claudeHomeOf("carol"), "--keep-original-folder")
        val keptSession = kept.data().imported().single()
        assertEquals("C--Users-alice-work", keptSession.get("projectFolderName").asString)
        assertEquals("C:\\Users\\alice\\work", keptSession.get("cwd").asString)

        val project = bobProject()
        val attached = import(format1, claudeHomeOf("dave"), "--target", project.toString())
        assertEquals(attached.out + attached.err, 0, attached.exitCode)
        assertEquals(project.toString(), attached.data().imported().single().get("cwd").asString)
    }

    @Test
    fun `--no-shift keeps the timestamps of the archive`() {
        val archive = archiveOfAlice()
        val bob = claudeHomeOf("bob")

        import(archive, bob, "--target", bobProject().toString(), "--no-shift", "--ids", "s-1")

        val written = Files.readString(transcriptsIn(bob).single())
        assertTrue(written, written.contains("2024-03-10T09:00:00.000Z"))
    }

    @Test
    fun `without --no-shift the last message lands at the time of the import`() {
        val archive = archiveOfAlice()
        val bob = claudeHomeOf("bob")

        import(archive, bob, "--target", bobProject().toString(), "--ids", "s-1")

        val written = Files.readString(transcriptsIn(bob).single())
        assertFalse(written, written.contains("2024-03-10T09:00:00.000Z"))
    }

    @Test
    fun `import in plain mode lists what happened and how to resume`() {
        val archive = archiveOfAlice()
        val project = bobProject()

        val result = run("--home", claudeHomeOf("bob").toString(), "import", archive.toString(), "--target", project.toString(), "--log-dir", logDir.toString())

        assertEquals(result.err, 0, result.exitCode)
        assertTrue(result.out, result.out.contains("Imported 2, skipped 0, failed 0."))
        assertTrue(result.out, result.out.contains("claude --resume <id>"))
        assertTrue(result.out, result.out.contains("import-20240312-083015.log"))
    }
}
