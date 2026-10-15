package com.github.fabiopelliccia.sessionporter.cli

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/** Every exit code of the CLI, with the `error.code` that goes with it. */
class ExitCodesTest : CliTest() {

    private val aliceWork = "C:\\Users\\alice\\work"

    private fun assertFailure(result: CliResult, exitCode: Int, code: String) {
        assertEquals(result.out + result.err, exitCode, result.exitCode)
        val json = result.json()
        assertFalse(json.get("ok").asBoolean)
        assertEquals(code, json.getAsJsonObject("error").get("code").asString)
        assertTrue(json.getAsJsonObject("error").get("message").asString.isNotBlank())
    }

    // ---------------------------------------------------------------------------- 2: usage --

    @Test
    fun `exit 2 for a command line that makes no sense`() {
        val home = claudeHomeOf("alice")
        assertFailure(run("--json"), ExitCode.USAGE, "noCommand")
        assertFailure(run("--json", "frobnicate"), ExitCode.USAGE, "unknownCommand")
        assertFailure(run("--json", "list", "--limit", "zero", "--home", home.toString()), ExitCode.USAGE, "invalidNumber")
        assertFailure(run("--json", "list", "--limit", "0", "--home", home.toString()), ExitCode.USAGE, "invalidNumber")
        assertFailure(run("--json", "list", "--since", "yesterday", "--home", home.toString()), ExitCode.USAGE, "invalidSince")
        assertFailure(run("--json", "export", "--out", "x.zip", "--home", home.toString()), ExitCode.USAGE, "selectorRequired")
        assertFailure(run("--json", "export", "--all", "--ids", "a", "--out", "x.zip", "--home", home.toString()), ExitCode.USAGE, "selectorConflict")
        assertFailure(run("--json", "export", "--all", "--home", home.toString()), ExitCode.USAGE, "outRequired")
        assertFailure(run("--json", "export", "--all", "--out", "x.txt", "--home", home.toString()), ExitCode.USAGE, "outNotZip")
        assertFailure(run("--json", "export", "--all", "--out", root.resolve("x.zip").toString(), "--home", home.toString()), ExitCode.USAGE, "noSessions")
        assertFailure(run("--json", "export", "--ids", "nope", "--out", root.resolve("x.zip").toString(), "--home", home.toString()), ExitCode.USAGE, "unknownSessions")
        assertFailure(run("--json", "import", "x.zip", "--policy", "merge"), ExitCode.USAGE, "invalidPolicy")
        assertFailure(run("--json", "version", "--lang", "!!"), ExitCode.USAGE, "invalidLanguage")
        // A NUL is no part of a path on any system.
        assertFailure(run("--json", "list", "--home", "bad\u0000home"), ExitCode.USAGE, "invalidPath")
    }

    @Test
    fun `a usage mistake in plain mode says so on standard error, with a pointer to the help`() {
        val result = run("export", "--out", "x.zip")

        assertEquals(ExitCode.USAGE, result.exitCode)
        assertEquals("", result.out)
        assertTrue(result.err, result.err.contains("Error: Say which sessions to export"))
        assertTrue(result.err, result.err.contains("session-porter --help"))
    }

    // ------------------------------------------------------------------- 3: invalid archive --

    @Test
    fun `exit 3 for something that is not an archive`() {
        val bob = claudeHomeOf("bob")
        val notZip = root.resolve("notes.zip").also { Files.writeString(it, "just some text") }
        assertFailure(run("--json", "inspect", notZip.toString()), ExitCode.ARCHIVE, "invalidArchive")
        assertFailure(import(notZip, bob), ExitCode.ARCHIVE, "invalidArchive")

        val noManifest = writeZip(root.resolve("nomanifest.zip"), manifest = null, entries = mapOf("hello.txt" to "hi"))
        assertFailure(run("--json", "inspect", noManifest.toString()), ExitCode.ARCHIVE, "invalidArchive")

        val foreign = writeZip(root.resolve("foreign.zip"), """{"producer":"Some other tool","sessions":[{"name":"x"}],"formatVersion":1,"exportedAt":"x"}""")
        assertFailure(run("--json", "inspect", foreign.toString()), ExitCode.ARCHIVE, "invalidArchive")
        assertEquals(emptyList<Path>(), transcriptsIn(bob))
    }

    @Test
    fun `exit 3 for an archive from a newer format`() {
        val tooNew = writeZip(root.resolve("new.zip"), manifestJson("s-1", formatVersion = 99), mapOf("sessions/s-1/transcript.jsonl" to "{}\n"))
        val result = import(tooNew, claudeHomeOf("bob"))
        assertFailure(result, ExitCode.ARCHIVE, "archiveTooNew")
        assertTrue(result.json().getAsJsonObject("error").get("message").asString.contains("99"))
    }

    @Test
    fun `exit 3 for a crafted archive whose ids would write outside the Claude home`() {
        val crafted = writeZip(
            root.resolve("crafted.zip"),
            manifestJson("../../../../escaped"),
            mapOf("sessions/../../../../escaped/transcript.jsonl" to "{}\n", "sessions/s/aux/payload.bat" to "echo pwned"),
        )
        val bob = claudeHomeOf("bob")

        val result = import(crafted, bob)
        assertFailure(result, ExitCode.ARCHIVE, "unsafeArchive")
        assertFailure(run("--json", "inspect", crafted.toString()), ExitCode.ARCHIVE, "unsafeArchive")

        assertEquals("nothing was written anywhere", emptyList<Path>(), Files.walk(root).use { paths ->
            paths.filter { Files.isRegularFile(it) && it != crafted }.toList()
        })
    }

    private fun import(archive: Path, bob: Path) = run(
        "--json", "--home", bob.toString(), "import", archive.toString(), "--target", root.resolve("target").toString(), "--log-dir", logDir.toString(),
    )

    // ------------------------------------------------------------------ 4: I/O, environment --

    @Test
    fun `exit 4 for a Claude home that is not there`() {
        val missing = root.resolve("no-such-home")
        assertFailure(run("--json", "list", "--home", missing.toString()), ExitCode.ENVIRONMENT, "homeNotFound")
        assertFailure(run("--json", "export", "--all", "--out", root.resolve("x.zip").toString(), "--home", missing.toString()), ExitCode.ENVIRONMENT, "homeNotFound")
        assertFailure(import(formatOneArchive("s-v1"), missing), ExitCode.ENVIRONMENT, "homeNotFound")
    }

    @Test
    fun `exit 4 for an archive or an ids file that is not there`() {
        val home = claudeHomeOf("alice")
        assertFailure(run("--json", "inspect", root.resolve("missing.zip").toString(), "--home", home.toString()), ExitCode.ENVIRONMENT, "archiveNotFound")
        writeSession(home, "s-1", aliceWork)
        assertFailure(
            run("--json", "export", "--ids-file", root.resolve("missing.txt").toString(), "--out", root.resolve("x.zip").toString(), "--home", home.toString()),
            ExitCode.ENVIRONMENT, "idsFileUnreadable",
        )
    }

    @Test
    fun `exit 4 for an output that cannot be written`() {
        val home = claudeHomeOf("alice")
        writeSession(home, "s-1", aliceWork)
        val aFile = root.resolve("a-file").also { Files.writeString(it, "x") }

        // The folder the archive would go in is a file.
        val result = run("--json", "export", "--all", "--out", aFile.resolve("x.zip").toString(), "--home", home.toString(), "--log-dir", logDir.toString())
        assertFailure(result, ExitCode.ENVIRONMENT, "ioError")
    }

    // ----------------------------------------------------------------------- 5: partial --

    @Test
    fun `exit 5 for an import whose diagnosis finds a problem, with the data`() {
        val alice = claudeHomeOf("alice")
        writeSession(alice, "s-1", aliceWork)
        val archive = exportArchive(alice, "s-1")

        // A target folder that does not exist: the sessions are imported, check 8 says the folder is not there.
        val result = run("--json", "--home", claudeHomeOf("bob").toString(), "import", archive.toString(), "--target", root.resolve("not-there").toString(), "--log-dir", logDir.toString())

        assertFailure(result, ExitCode.PARTIAL, "partialImport")
        val data = result.data()
        assertEquals(1, data.getAsJsonArray("imported").size())
        val check = data.getAsJsonArray("failedChecks")[0].asJsonObject
        assertEquals(8, check.get("number").asInt)
        assertEquals("s-1", check.get("sessionId").asString)
        assertTrue(check.get("detail").isJsonPrimitive)
    }

    @Test
    fun `exit 5 for an import where a session fails, the others go on`() {
        val alice = claudeHomeOf("alice")
        writeSession(alice, "s-1", aliceWork)
        writeSession(alice, "s-2", aliceWork)
        writeAux(alice, aliceWork, "s-1", "result.txt", "tool result of the first session\n".repeat(500))
        val archive = exportArchive(alice, "s-1", "s-2")
        val target = Files.createDirectories(root.resolve("bob").resolve("work"))
        // Damage the data of an entry of the first session: the manifest still reads, that session cannot be imported.
        val damaged = corruptEntry(archive, "sessions/s-1/aux/tool-results/result.txt")

        val result = run("--json", "--home", claudeHomeOf("bob").toString(), "import", damaged.toString(), "--target", target.toString(), "--log-dir", logDir.toString())

        assertFailure(result, ExitCode.PARTIAL, "partialImport")
        val data = result.data()
        assertEquals(listOf("s-1"), data.getAsJsonArray("failures").map { it.asJsonObject.get("sessionId").asString })
        assertEquals(listOf("s-2"), data.getAsJsonArray("imported").map { it.asJsonObject.get("sourceId").asString })
        assertTrue("nothing of the failed session is left", transcriptsIn(root.resolve("bob").resolve(".claude")).none { it.fileName.toString() == "s-1.jsonl" })
    }

    /** Flips bytes in the compressed data of an entry, so that reading it fails but the central directory still reads. */
    private fun corruptEntry(archive: Path, entryName: String): Path {
        val bytes = Files.readAllBytes(archive)
        val name = entryName.toByteArray(Charsets.UTF_8)
        var nameAt = -1
        for (start in 30..bytes.size - name.size) {
            if (bytes[start - 30] == 'P'.code.toByte() && bytes[start - 29] == 'K'.code.toByte() && bytes[start - 28] == 3.toByte() &&
                name.indices.all { bytes[start + it] == name[it] }
            ) {
                nameAt = start
                break
            }
        }
        check(nameAt >= 0) { "$entryName is not in the archive" }
        val extraLength = (bytes[nameAt - 2].toInt() and 0xFF) or ((bytes[nameAt - 1].toInt() and 0xFF) shl 8)
        val dataStart = nameAt + name.size + extraLength
        for (offset in dataStart + 4 until dataStart + 24) bytes[offset] = (bytes[offset].toInt() xor 0x5A).toByte()
        return Files.write(root.resolve("damaged.zip"), bytes)
    }

    // ---------------------------------------------------------------------- 6: privacy ---

    @Test
    fun `exit 6 is the strict privacy check`() {
        val home = claudeHomeOf("alice")
        writeSession(home, "s-1", aliceWork, extraLines = listOf(lineWithBareUserName("s-1", aliceWork, "alice")))
        val result = run("--json", "--home", home.toString(), "export", "--all", "--out", root.resolve("x.zip").toString(), "--strict-privacy", "--log-dir", logDir.toString())
        assertFailure(result, ExitCode.PRIVACY, "privacyFindings")
    }

    // -------------------------------------------------------------------------- 0, 10 -----

    @Test
    fun `exit 0 for help and version`() {
        assertEquals(0, run("--help").exitCode)
        assertEquals(0, run("version").exitCode)
        assertTrue(run("--help").out.contains("Exit codes"))
    }
}
