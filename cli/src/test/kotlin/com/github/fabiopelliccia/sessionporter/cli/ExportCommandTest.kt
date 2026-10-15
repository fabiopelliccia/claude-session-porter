package com.github.fabiopelliccia.sessionporter.cli

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.util.zip.ZipFile
import kotlin.io.path.isRegularFile

class ExportCommandTest : CliTest() {

    private val aliceWork = "C:\\Users\\alice\\work"
    private val aliceOther = "C:\\Users\\alice\\other-project"

    private fun threeSessions() = claudeHomeOf("alice").also { home ->
        writeSession(home, "s-1", aliceWork, title = "First")
        writeSession(home, "s-2", aliceWork, title = "Second")
        writeSession(home, "s-3", aliceOther, title = "Third")
    }

    private fun export(vararg args: String, stdin: String = "", windows: Boolean = false) =
        run(*args, "--log-dir", logDir.toString(), "--json", stdin = stdin, windows = windows)

    @Test
    fun `export with --ids writes those sessions and reports what it did`() {
        val home = threeSessions()
        val archive = root.resolve("out.zip")

        val result = export("--home", home.toString(), "export", "--ids", "s-1,s-3", "--out", archive.toString())

        assertEquals(result.out + result.err, 0, result.exitCode)
        assertEquals(setOf("s-1", "s-3"), sessionIdsIn(archive))
        val data = result.data()
        assertEquals(archive.toAbsolutePath().toString(), data.get("archive").asString)
        assertEquals(2, data.get("exportedCount").asInt)
        assertEquals(2, data.get("requestedCount").asInt)
        assertEquals(0, data.get("unreadableFiles").asInt)
        assertEquals(Files.size(archive), data.get("sizeBytes").asLong)
        assertFalse(data.get("privacyFindings").asBoolean)
        val anonymization = data.getAsJsonObject("anonymization")
        assertEquals(setOf("replacedByKind", "replacedByStyle", "residualUserNameHits", "residualEntries", "binaryEntriesWithHits", "keyCollisions"), anonymization.keySet())
        assertTrue("the project roots were replaced", anonymization.getAsJsonObject("replacedByKind").get("ROOT").asInt > 0)
        assertEquals(0, anonymization.get("residualUserNameHits").asInt)
    }

    @Test
    fun `the archive says it was written by the CLI and names no path of the exporting user`() {
        val home = threeSessions()
        val archive = root.resolve("out.zip")
        export("--home", home.toString(), "export", "--all", "--out", archive.toString())

        ZipFile(archive.toFile()).use { zip ->
            val manifest = String(zip.getInputStream(zip.getEntry("manifest.json")).readBytes(), Charsets.UTF_8)
            assertTrue(manifest, manifest.contains("\"producerComponent\": \"cli\""))
            assertTrue(manifest, manifest.contains("\"producerVersion\": \"${CliVersion.value}\""))
            for (entry in zip.entries()) {
                val text = String(zip.getInputStream(entry).readBytes(), Charsets.UTF_8)
                assertFalse("${entry.name} names alice's folder", text.contains("Users\\\\alice") || text.contains("Users\\alice"))
            }
        }
    }

    @Test
    fun `export reads the ids from a file or from standard input`() {
        val home = threeSessions()
        val idsFile = root.resolve("ids.txt")
        Files.writeString(idsFile, "# the ones I want\ns-2\n\ns-3\n")

        val fromFile = export("--home", home.toString(), "export", "--ids-file", idsFile.toString(), "--out", root.resolve("a.zip").toString())
        assertEquals(setOf("s-2", "s-3"), sessionIdsIn(root.resolve("a.zip")))
        assertEquals(0, fromFile.exitCode)

        val fromStdin = export("--home", home.toString(), "export", "--ids-file", "-", "--out", root.resolve("b.zip").toString(), stdin = "s-1\ns-2\n")
        assertEquals(fromStdin.err, 0, fromStdin.exitCode)
        assertEquals(setOf("s-1", "s-2"), sessionIdsIn(root.resolve("b.zip")))
    }

    @Test
    fun `export --all and --project`() {
        val home = threeSessions()

        export("--home", home.toString(), "export", "--all", "--out", root.resolve("all.zip").toString())
        assertEquals(setOf("s-1", "s-2", "s-3"), sessionIdsIn(root.resolve("all.zip")))

        export("--home", home.toString(), "export", "--project", aliceWork, "--out", root.resolve("work.zip").toString())
        assertEquals(setOf("s-1", "s-2"), sessionIdsIn(root.resolve("work.zip")))

        // The Git Bash spelling of the folder, on Windows.
        export("--home", home.toString(), "export", "--project", "/c/Users/alice/other-project", "--out", root.resolve("other.zip").toString(), windows = true)
        assertEquals(setOf("s-3"), sessionIdsIn(root.resolve("other.zip")))
    }

    @Test
    fun `a folder as --out gets a time stamped name`() {
        val home = threeSessions()
        val folder = root.resolve("exports").also { Files.createDirectories(it) }

        val result = export("--home", home.toString(), "export", "--all", "--out", folder.toString())

        val expected = folder.resolve("claude-sessions-20240312-083015.zip")
        assertEquals(result.err, 0, result.exitCode)
        assertTrue(expected.isRegularFile())
        assertEquals(expected.toAbsolutePath().toString(), result.data().get("archive").asString)
        // A name that ends in a separator is a folder too, even when it does not exist yet.
        val created = export("--home", home.toString(), "export", "--all", "--out", root.resolve("new-folder").toString() + "/")
        assertEquals(created.err, 0, created.exitCode)
        assertTrue(root.resolve("new-folder").resolve("claude-sessions-20240312-083015.zip").isRegularFile())
    }

    @Test
    fun `an existing archive is never overwritten without --force`() {
        val home = threeSessions()
        val archive = root.resolve("out.zip")
        Files.writeString(archive, "precious")

        val refused = export("--home", home.toString(), "export", "--all", "--out", archive.toString())
        assertEquals(ExitCode.ENVIRONMENT, refused.exitCode)
        assertEquals("outputExists", refused.json().getAsJsonObject("error").get("code").asString)
        assertEquals("precious", Files.readString(archive))
        assertEquals("no leftovers", listOf("out.zip"), Files.list(root).use { it.map { p -> p.fileName.toString() }.toList() }.filter { it.endsWith(".zip") || it.startsWith(".session") })

        // The default name of a folder is just as protected.
        val folder = root.resolve("exports").also { Files.createDirectories(it) }
        Files.writeString(folder.resolve("claude-sessions-20240312-083015.zip"), "precious too")
        assertEquals(ExitCode.ENVIRONMENT, export("--home", home.toString(), "export", "--all", "--out", folder.toString()).exitCode)
        assertEquals("precious too", Files.readString(folder.resolve("claude-sessions-20240312-083015.zip")))

        val forced = export("--home", home.toString(), "export", "--all", "--out", archive.toString(), "--force")
        assertEquals(forced.err, 0, forced.exitCode)
        assertEquals(setOf("s-1", "s-2", "s-3"), sessionIdsIn(archive))
    }

    // ----------------------------------------------------------------------------- privacy --

    @Test
    fun `without --strict-privacy a leftover is reported and the archive is kept`() {
        val home = claudeHomeOf("alice")
        writeSession(home, "s-1", aliceWork, extraLines = listOf(lineWithBareUserName("s-1", aliceWork, "alice")))
        val archive = root.resolve("out.zip")

        val result = export("--home", home.toString(), "export", "--ids", "s-1", "--out", archive.toString())

        assertEquals(result.out + result.err, 0, result.exitCode)
        assertTrue(archive.isRegularFile())
        val data = result.data()
        assertTrue(data.get("privacyFindings").asBoolean)
        assertTrue(data.getAsJsonObject("anonymization").get("residualUserNameHits").asInt > 0)
        assertEquals(1, result.json().getAsJsonArray("warnings").size())
    }

    @Test
    fun `--strict-privacy also fails on a finding in a file that is not text, and only on that`() {
        val home = claudeHomeOf("alice")
        writeSession(home, "s-1", aliceWork)
        // 0xFF is not valid in UTF-8 and the length is odd, so it is not UTF-16 either: copied as it is, and scanned.
        val binary = byteArrayOf(0xFF.toByte(), 0x00, 0x01) + "built by alice".toByteArray()
        Files.write(Files.createDirectories(home.resolve("projects").resolve("C--Users-alice-work").resolve("s-1").resolve("tool-results")).resolve("name.bin"), binary)

        val strict = export("--home", home.toString(), "export", "--all", "--out", root.resolve("strict.zip").toString(), "--strict-privacy")

        assertEquals(strict.out, ExitCode.PRIVACY, strict.exitCode)
        assertEquals("privacyFindings", strict.json().getAsJsonObject("error").get("code").asString)
        val anonymization = strict.data().getAsJsonObject("anonymization")
        assertEquals("no bare user name in any text, the only hit is the binary file", 0, anonymization.get("residualUserNameHits").asInt)
        assertEquals(listOf("sessions/s-1/aux/tool-results/name.bin"), anonymization.getAsJsonArray("binaryEntriesWithHits").map { it.asString })
        assertFalse("no archive", Files.exists(root.resolve("strict.zip")))

        // Without the option the archive is written, and the finding is a warning.
        val lenient = export("--home", home.toString(), "export", "--all", "--out", root.resolve("lenient.zip").toString())
        assertEquals(lenient.out, 0, lenient.exitCode)
        assertTrue(lenient.data().get("privacyFindings").asBoolean)
        assertEquals(1, lenient.json().getAsJsonArray("warnings").size())
    }

    @Test
    fun `a file that cannot be read makes the export partial, exit 5, and the archive is still written`() {
        val home = claudeHomeOf("alice")
        writeSession(home, "s-1", aliceWork)
        val locked = writeAux(home, aliceWork, "s-1", "locked.txt", "content")
        val unreadable = makeUnreadable(locked)
        org.junit.Assume.assumeTrue("this system cannot make a file unreadable to its owner", unreadable != null)
        try {
            val archive = root.resolve("out.zip")

            val result = export("--home", home.toString(), "export", "--all", "--out", archive.toString())

            assertEquals(result.out + result.err, ExitCode.PARTIAL, result.exitCode)
            val json = result.json()
            assertEquals("partialExport", json.getAsJsonObject("error").get("code").asString)
            assertEquals(1, json.getAsJsonObject("data").get("unreadableFiles").asInt)
            assertEquals("the session itself is in", 1, json.getAsJsonObject("data").get("exportedCount").asInt)
            assertEquals(archive.toAbsolutePath().toString(), json.getAsJsonObject("data").get("archive").asString)
            assertEquals(setOf("s-1"), sessionIdsIn(archive))
            assertEquals(1, json.getAsJsonArray("warnings").size())
            assertTrue(json.getAsJsonArray("warnings")[0].asString, json.getAsJsonArray("warnings")[0].asString.contains("locked.txt"))
        } finally {
            unreadable!!.close()
        }
    }

    /**
     * Makes [file] unreadable to this process until the result is closed: a lock on Windows (where it is mandatory),
     * no permissions on a system that honours them. `null` when neither works (a POSIX root reads everything).
     */
    private fun makeUnreadable(file: java.nio.file.Path): AutoCloseable? {
        if (System.getProperty("os.name").startsWith("Windows")) {
            val channel = java.nio.channels.FileChannel.open(file, java.nio.file.StandardOpenOption.READ, java.nio.file.StandardOpenOption.WRITE)
            channel.lock()
            return AutoCloseable { channel.close() }
        }
        val permissions = java.nio.file.attribute.PosixFilePermissions.fromString("---------")
        Files.setPosixFilePermissions(file, permissions)
        if (Files.isReadable(file)) return null
        return AutoCloseable { Files.setPosixFilePermissions(file, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")) }
    }

    @Test
    fun `--strict-privacy fails with exit 6 and leaves no archive behind`() {
        val home = claudeHomeOf("alice")
        writeSession(home, "s-1", aliceWork, extraLines = listOf(lineWithBareUserName("s-1", aliceWork, "alice")))
        val archive = root.resolve("out.zip")
        Files.writeString(archive, "an older archive")

        val result = export("--home", home.toString(), "export", "--ids", "s-1", "--out", archive.toString(), "--strict-privacy", "--force")

        assertEquals(ExitCode.PRIVACY, result.exitCode)
        val json = result.json()
        assertFalse(json.get("ok").asBoolean)
        assertEquals("privacyFindings", json.getAsJsonObject("error").get("code").asString)
        // The counts are there for the caller to show, the archive is not.
        assertTrue(json.getAsJsonObject("data").get("archive").isJsonNull)
        assertTrue(json.getAsJsonObject("data").get("privacyFindings").asBoolean)
        assertEquals("the older archive is untouched", "an older archive", Files.readString(archive))
        assertEquals(emptyList<String>(), Files.list(root).use { it.map { p -> p.fileName.toString() }.toList() }.filter { it.startsWith(".session-porter") })

        // On a clean session the same option changes nothing.
        writeSession(home, "s-2", aliceWork)
        val clean = export("--home", home.toString(), "export", "--ids", "s-2", "--out", root.resolve("clean.zip").toString(), "--strict-privacy")
        assertEquals(clean.err, 0, clean.exitCode)
        assertTrue(root.resolve("clean.zip").isRegularFile())
    }

    // -------------------------------------------------------------------------------- partial --

    @Test
    fun `a session that cannot be exported makes the export partial, exit 5`() {
        val home = claudeHomeOf("alice")
        writeSession(home, "s-good", aliceWork)
        // Nothing but the beginning of a line: a session being written, which has no complete line to export.
        val transcript = writeSession(home, "s-empty", aliceWork)
        Files.writeString(transcript, "{\"type\":\"user\",\"cw")
        val archive = root.resolve("out.zip")

        val result = export("--home", home.toString(), "export", "--ids", "s-good,s-empty", "--out", archive.toString())

        assertEquals(ExitCode.PARTIAL, result.exitCode)
        val json = result.json()
        assertFalse(json.get("ok").asBoolean)
        assertEquals("partialExport", json.getAsJsonObject("error").get("code").asString)
        assertEquals(1, json.getAsJsonObject("data").get("exportedCount").asInt)
        assertEquals(2, json.getAsJsonObject("data").get("requestedCount").asInt)
        assertEquals(setOf("s-good"), sessionIdsIn(archive))
        assertEquals(1, json.getAsJsonArray("warnings").size())
    }

    // ------------------------------------------------------------------------------------ log --

    @Test
    fun `the export log holds counts only, never a title or a path`() {
        val home = threeSessions()
        val result = export("--home", home.toString(), "export", "--all", "--out", root.resolve("out.zip").toString())

        val logFile = java.nio.file.Paths.get(result.data().get("logFile").asString)
        assertEquals(logDir.toAbsolutePath(), logFile.parent)
        assertEquals("export-20240312-083015.log", logFile.fileName.toString())
        val log = Files.readString(logFile)
        assertTrue(log, log.contains("exportedSessions = 3"))
        assertTrue(log, log.contains("requestedSessions = 3"))
        assertTrue(log, log.contains("cli.version = ${CliVersion.value}"))
        for (forbidden in listOf("First", "Second", "Third", "alice", "work", "s-1")) assertFalse("$forbidden in the log: $log", log.contains(forbidden))
    }

    @Test
    fun `only the last 20 export logs are kept`() {
        val home = threeSessions()
        Files.createDirectories(logDir)
        for (day in 1..25) Files.writeString(logDir.resolve("export-202401%02d-000000.log".format(day)), "old")
        Files.writeString(logDir.resolve("import-20240101-000000.log"), "another kind")

        export("--home", home.toString(), "export", "--all", "--out", root.resolve("out.zip").toString())

        val exportLogs = Files.list(logDir).use { it.map { p -> p.fileName.toString() }.toList() }.filter { it.startsWith("export-") }.sorted()
        assertEquals(20, exportLogs.size)
        assertEquals("export-20240312-083015.log", exportLogs.last())
        assertFalse("the oldest are gone", exportLogs.contains("export-20240101-000000.log"))
        assertTrue("logs of another kind are left alone", Files.exists(logDir.resolve("import-20240101-000000.log")))
    }
}
