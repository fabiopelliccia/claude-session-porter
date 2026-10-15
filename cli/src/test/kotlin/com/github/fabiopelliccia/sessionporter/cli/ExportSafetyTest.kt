package com.github.fabiopelliccia.sessionporter.cli

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/** Findings 4, 5 and 12 of the WP6 review: an export that fails, is refused or is interrupted leaves nothing behind. */
class ExportSafetyTest : CliTest() {

    private val aliceWork = "C:\\Users\\alice\\work"

    private fun CliResult.errorCode(): String = json().getAsJsonObject("error").get("code").asString

    private fun filesIn(folder: Path): List<String> =
        if (!Files.isDirectory(folder)) emptyList() else Files.walk(folder).use { paths -> paths.skip(1).map { folder.relativize(it).toString() }.toList() }

    // -------------------------------------------------------------------------------- finding 4 --

    @Test
    fun `a bad --log-dir is found before anything is written, so the retry is not blocked by an archive`() {
        val alice = claudeHomeOf("alice")
        writeSession(alice, "s-1", aliceWork)
        val outFolder = Files.createDirectories(root.resolve("out"))

        // A NUL is no part of a path on any system.
        val result = run("--json", "--home", alice.toString(), "export", "--all", "--out", outFolder.resolve("x.zip").toString(), "--log-dir", "bad\u0000dir")

        assertEquals(ExitCode.USAGE, result.exitCode)
        assertEquals("invalidPath", result.errorCode())
        assertEquals("no archive, no leftover in the destination folder", emptyList<String>(), filesIn(outFolder))
        assertEquals("nothing is left in the temp folder either", emptyList<String>(), filesIn(systemTemp))

        val retry = run("--json", "--home", alice.toString(), "export", "--all", "--out", outFolder.resolve("x.zip").toString(), "--log-dir", logDir.toString())
        assertEquals(retry.out + retry.err, 0, retry.exitCode)
    }

    // -------------------------------------------------------------------------------- finding 12 --

    @Test
    fun `a refused strict export creates no folder`() {
        val alice = claudeHomeOf("alice")
        writeSession(alice, "s-1", aliceWork, extraLines = listOf(lineWithBareUserName("s-1", aliceWork, "alice")))
        val missing = root.resolve("strict-new").resolve("deeper")

        val result = run(
            "--json", "--home", alice.toString(), "export", "--all", "--out", missing.toString() + "/", "--strict-privacy", "--log-dir", logDir.toString(),
        )

        assertEquals(ExitCode.PRIVACY, result.exitCode)
        assertFalse("the folders of the destination were not created", Files.exists(root.resolve("strict-new")))
    }

    @Test
    fun `an export creates the folders it is missing and only those`() {
        val alice = claudeHomeOf("alice")
        writeSession(alice, "s-1", aliceWork)
        val existing = Files.createDirectories(root.resolve("exports"))
        val destination = existing.resolve("2024").resolve("march").resolve("out.zip")

        val result = run("--json", "--home", alice.toString(), "export", "--all", "--out", destination.toString(), "--log-dir", logDir.toString())

        assertEquals(result.out + result.err, 0, result.exitCode)
        assertEquals(setOf("s-1"), sessionIdsIn(destination))
        assertEquals("only the archive is there, no temporary file", listOf("2024", "2024\\march", "2024\\march\\out.zip").map { it.replace('\\', java.io.File.separatorChar) }, filesIn(existing))
    }

    @Test
    fun `a destination whose parent is a file fails before the export starts, with exit 4`() {
        val alice = claudeHomeOf("alice")
        writeSession(alice, "s-1", aliceWork)
        val aFile = root.resolve("a-file").also { Files.writeString(it, "x") }

        val result = run("--json", "--home", alice.toString(), "export", "--all", "--out", aFile.resolve("sub").resolve("x.zip").toString(), "--log-dir", logDir.toString(), "--verbose")

        assertEquals(ExitCode.ENVIRONMENT, result.exitCode)
        assertEquals("ioError", result.errorCode())
        assertFalse("the export did not even start: ${result.err}", result.err.contains("exporting"))
        assertEquals(emptyList<String>(), filesIn(systemTemp))
    }

    // --------------------------------------------------------------------------------- finding 5 --

    @Test
    fun `the archive is built in the system temp folder, never in the destination, and nothing is left there`() {
        val alice = claudeHomeOf("alice")
        writeSession(alice, "s-1", aliceWork)
        val outFolder = Files.createDirectories(root.resolve("synced"))
        val seenInTemp = mutableListOf<String>()
        val seenInOut = mutableListOf<String>()
        // Looks at both folders while the export is running, at every line of progress.
        val err = object : PrintStream(ByteArrayOutputStream(), true) {
            override fun println(x: String?) {
                seenInTemp += filesIn(systemTemp)
                seenInOut += filesIn(outFolder)
            }
        }
        val out = ByteArrayOutputStream()
        val environment = environmentOf(out, ByteArrayOutputStream()).let {
            CliEnvironment(it.input, it.out, err, it.osName, it.userHome, it.getenv, it.clock, it.tempDirectory)
        }

        val exitCode = SessionPorterCli(environment).run(
            listOf("--json", "--verbose", "--home", alice.toString(), "export", "--all", "--out", outFolder.resolve("e.zip").toString(), "--log-dir", logDir.toString()),
        )

        assertEquals(0, exitCode)
        assertTrue("the work went on in the temp folder: $seenInTemp", seenInTemp.any { it.endsWith("archive.zip.part") || it.contains("session-porter") })
        assertEquals("nothing showed in the destination before the end", emptyList<String>(), seenInOut)
        assertEquals(listOf("e.zip"), filesIn(outFolder))
        assertEquals(emptyList<String>(), filesIn(systemTemp))
    }

    @Test
    fun `an interrupted export removes its temporary files, prints one interrupted envelope and writes nothing`() {
        val alice = claudeHomeOf("alice")
        writeSession(alice, "s-1", aliceWork)
        val outFolder = root.resolve("synced")
        val out = ByteArrayOutputStream()
        var interrupted: Boolean? = null
        lateinit var cli: SessionPorterCli
        // The signal arrives while the archive is being written: the last line of progress is reported inside the export.
        val err = object : PrintStream(ByteArrayOutputStream(), true) {
            override fun println(x: String?) {
                if (interrupted == null && x != null && x.contains("100%")) interrupted = cli.interrupt()
            }
        }
        val base = environmentOf(out, ByteArrayOutputStream())
        cli = SessionPorterCli(CliEnvironment(base.input, base.out, err, base.osName, base.userHome, base.getenv, base.clock, base.tempDirectory))

        val exitCode = cli.run(
            listOf("--json", "--verbose", "--home", alice.toString(), "export", "--all", "--out", outFolder.resolve("e.zip").toString(), "--log-dir", logDir.toString()),
        )
        base.out.flush()

        assertEquals(true, interrupted)
        assertEquals(ExitCode.INTERRUPTED, exitCode)
        val printed = String(out.toByteArray(), StandardCharsets.UTF_8)
        assertEquals("one document, the one of the interruption: $printed", 1, printed.trimEnd().lines().size)
        val json = com.google.gson.JsonParser.parseString(printed).asJsonObject
        assertFalse(json.get("ok").asBoolean)
        assertEquals("export", json.get("command").asString)
        assertEquals("interrupted", json.getAsJsonObject("error").get("code").asString)
        assertFalse("the destination folder was not created", Files.exists(outFolder))
        assertEquals("the temporary files are gone", emptyList<String>(), filesIn(systemTemp))
    }

    @Test
    fun `an interruption after the result was printed adds nothing`() {
        val alice = claudeHomeOf("alice")
        writeSession(alice, "s-1", aliceWork)
        val out = ByteArrayOutputStream()
        val environment = environmentOf(out, ByteArrayOutputStream())
        val cli = SessionPorterCli(environment)

        assertEquals(0, cli.run(listOf("--json", "--home", alice.toString(), "export", "--all", "--out", root.resolve("e.zip").toString(), "--log-dir", logDir.toString())))
        environment.out.flush()
        val printed = out.toByteArray().size

        assertFalse("the shutdown hook of a normal exit has nothing to report", cli.interrupt())
        environment.out.flush()
        assertEquals(printed, out.toByteArray().size)
        assertTrue(Files.isRegularFile(root.resolve("e.zip")))
    }

    @Test
    fun `an interruption in plain mode says so on standard error`() {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val environment = environmentOf(out, err)
        val cli = SessionPorterCli(environment)

        assertTrue(cli.interrupt())
        environment.err.flush()

        assertEquals("", String(out.toByteArray(), StandardCharsets.UTF_8))
        assertTrue(String(err.toByteArray(), StandardCharsets.UTF_8).contains("interrupted"))
    }

    @Test
    fun `a pending file registry removes files and folders, once, and refuses a later commit`() {
        val pending = PendingFiles()
        val folder = Files.createDirectories(root.resolve("work"))
        Files.writeString(folder.resolve("a.part"), "x")
        val file = Files.writeString(root.resolve("b.tmp"), "x")
        pending.track(folder)
        pending.track(file)

        assertEquals(42, pending.commit { 42 })
        pending.abandon()

        assertFalse(Files.exists(folder))
        assertFalse(Files.exists(file))
        var ran = false
        try {
            pending.commit { ran = true }
            org.junit.Assert.fail("the commit of an interrupted run must not happen")
        } catch (_: CommandInterrupted) {
        }
        assertFalse(ran)
    }
}
