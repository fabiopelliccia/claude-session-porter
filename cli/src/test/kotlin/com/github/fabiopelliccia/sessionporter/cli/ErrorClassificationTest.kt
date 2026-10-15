package com.github.fabiopelliccia.sessionporter.cli

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.UncheckedIOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files

/** Finding 11 of the WP6 review: the command and the code of every failure, decided by the kind of the failure. */
class ErrorClassificationTest : CliTest() {

    private fun CliResult.errorCode(): String = json().getAsJsonObject("error").get("code").asString

    private fun CliResult.command(): String? = json().get("command").let { if (it.isJsonNull) null else it.asString }

    // ----------------------------------------------------------------------------------- command --

    @Test
    fun `a usage error after the command word names the command`() {
        val home = claudeHomeOf("alice")
        val cases = mapOf(
            listOf("export", "--out", "x.zip") to "export",
            listOf("list", "--limit", "0") to "list",
            listOf("import") to "import",
            listOf("inspect", "a.zip", "b.zip") to "inspect",
            listOf("doctor", "--bogus") to "doctor",
            listOf("list", "--since", "yesterday") to "list",
        )
        for ((arguments, command) in cases) {
            val result = run("--json", "--home", home.toString(), *arguments.toTypedArray())
            assertEquals(arguments.toString() + result.out, ExitCode.USAGE, result.exitCode)
            assertEquals(arguments.toString(), command, result.command())
        }
    }

    @Test
    fun `an unknown command, or none, has no command to name`() {
        assertEquals(null, run("--json", "frobnicate").command())
        assertEquals(null, run("--json").command())
        assertEquals(null, run("--json", "--bogus", "list").command())
    }

    // --------------------------------------------------------------------------------- exceptions --

    @Test
    fun `an unchecked I O exception is an I O error, exit 4`() {
        val result = run("--json", "list", environment = { name ->
            if (name == "SESSION_PORTER_LANG") throw UncheckedIOException(IOException("the disk went away")) else null
        })

        assertEquals(ExitCode.ENVIRONMENT, result.exitCode)
        assertEquals("ioError", result.errorCode())
        assertTrue(result.out, result.out.contains("the disk went away"))
    }

    @Test
    fun `running out of memory has a code of its own, with the exit code of an internal error`() {
        val result = run("--json", "list", environment = { name -> if (name == "SESSION_PORTER_LANG") throw OutOfMemoryError("Java heap space") else null })

        assertEquals(ExitCode.INTERNAL, result.exitCode)
        assertEquals("outOfMemory", result.errorCode())
        assertTrue(result.out, result.out.contains("-Xmx"))
    }

    @Test
    fun `any other exception is an internal error, exit 10, and says what it was`() {
        val result = run("--json", "list", environment = { name -> if (name == "SESSION_PORTER_LANG") throw IllegalStateException("a bug") else null })

        assertEquals(ExitCode.INTERNAL, result.exitCode)
        assertEquals("internalError", result.errorCode())
        assertTrue(result.out, result.out.contains("IllegalStateException: a bug"))
        assertEquals("", result.err)
    }

    @Test
    fun `with --verbose an internal error also prints where it happened`() {
        val result = run("--json", "--verbose", "list", environment = { name -> if (name == "SESSION_PORTER_LANG") throw IllegalStateException("a bug") else null })

        assertEquals(ExitCode.INTERNAL, result.exitCode)
        assertTrue(result.err, result.err.contains("at com.github.fabiopelliccia.sessionporter.cli"))
    }

    // ------------------------------------------------------------------------------------ archives --

    @Test
    fun `an archive of a newer format, one that is not a ZIP and one of another tool have a code each`() {
        val bob = claudeHomeOf("bob")
        val tooNew = writeZip(root.resolve("new.zip"), manifestJson("s-1", formatVersion = 99), mapOf("sessions/s-1/transcript.jsonl" to "{}\n"))
        val notZip = root.resolve("notes.zip").also { Files.writeString(it, "just some text") }
        val foreign = writeZip(root.resolve("foreign.zip"), """{"producer":"Some other tool","sessions":[{"name":"x"}],"formatVersion":1,"exportedAt":"x"}""")
        val noManifest = writeZip(root.resolve("nomanifest.zip"), manifest = null, entries = mapOf("hello.txt" to "hi"))

        val newer = run("--json", "--home", bob.toString(), "inspect", tooNew.toString())
        assertEquals(ExitCode.ARCHIVE, newer.exitCode)
        assertEquals("archiveTooNew", newer.errorCode())

        val plainText = run("--json", "--home", bob.toString(), "inspect", notZip.toString())
        assertEquals(ExitCode.ARCHIVE, plainText.exitCode)
        assertEquals("invalidArchive", plainText.errorCode())
        // The message is the CLI's own, not what the ZIP library says ("zip END header not found").
        assertEquals("Not a valid session archive.", plainText.json().getAsJsonObject("error").get("message").asString)

        for (archive in listOf(foreign, noManifest)) {
            val result = run("--json", "--home", bob.toString(), "inspect", archive.toString())
            assertEquals(ExitCode.ARCHIVE, result.exitCode)
            assertEquals("invalidArchive", result.errorCode())
            assertNotEquals("Not a valid session archive.", result.json().getAsJsonObject("error").get("message").asString)
        }
    }

    @Test
    fun `the kind of failure counts, not the words of its message`() {
        // A message that happens to be the one of the unsafe archive, on an exception that is something else.
        val unsafeWords = com.github.fabiopelliccia.claudesessionsimportexport.core.ClaudeSessionsBundle.message("error.unsafeArchive")
        val bob = claudeHomeOf("bob")
        val result = run("--json", "list", "--home", bob.toString(), environment = { name ->
            if (name == "SESSION_PORTER_LANG") throw IOException(unsafeWords) else null
        })
        assertEquals("ioError", result.errorCode())
    }

    // --------------------------------------------------------------------------- ids from the input --

    @Test
    fun `--ids-file - on a console is a usage error instead of waiting for the person`() {
        val alice = claudeHomeOf("alice")
        writeSession(alice, "s-1", "C:\\Users\\alice\\work")

        val result = run("--json", "--home", alice.toString(), "export", "--ids-file", "-", "--out", root.resolve("x.zip").toString(), interactiveInput = true)

        assertEquals(ExitCode.USAGE, result.exitCode)
        assertEquals("idsFromConsole", result.errorCode())
        assertFalse(Files.exists(root.resolve("x.zip")))
    }

    @Test
    fun `standard input that cannot be read is a problem of the environment, not an I O error with a system message`() {
        val alice = claudeHomeOf("alice")
        writeSession(alice, "s-1", "C:\\Users\\alice\\work")
        val broken = object : InputStream() {
            override fun read(): Int = throw IOException("Funzione non corretta")
        }
        val out = ByteArrayOutputStream()
        val base = environmentOf(out, ByteArrayOutputStream())
        val environment = CliEnvironment(broken, base.out, base.err, base.osName, base.userHome, base.getenv, base.clock, base.tempDirectory)

        val code = SessionPorterCli(environment).run(listOf("--json", "--home", alice.toString(), "export", "--ids-file", "-", "--out", root.resolve("x.zip").toString()))
        environment.out.flush()

        assertEquals(ExitCode.ENVIRONMENT, code)
        assertTrue(String(out.toByteArray(), StandardCharsets.UTF_8).contains("idsFileUnreadable"))
    }

    @Test
    fun `ids can still come from standard input when it is not a console`() {
        val alice = claudeHomeOf("alice")
        writeSession(alice, "s-1", "C:\\Users\\alice\\work")

        val result = run("--json", "--home", alice.toString(), "export", "--ids-file", "-", "--out", root.resolve("x.zip").toString(), "--log-dir", logDir.toString(), stdin = "s-1\n")

        assertEquals(result.out + result.err, 0, result.exitCode)
    }

    // ------------------------------------------------------------ names of sessions in the output --

    @Test
    fun `warnings and progress name a session by its id, never by its title`() {
        val alice = claudeHomeOf("alice")
        val transcript = writeSession(alice, "s-1", "C:\\Users\\alice\\work", title = "SECRET-PROMPT-TEXT")
        // The last line is still being written: the export drops it and says so.
        Files.writeString(transcript, Files.readString(transcript) + "{\"type\":\"user\",\"cw")
        val archive = root.resolve("e.zip")

        val export = run("--json", "--verbose", "--home", alice.toString(), "export", "--all", "--out", archive.toString(), "--log-dir", logDir.toString())

        assertEquals(export.out + export.err, 0, export.exitCode)
        val warnings = export.json().getAsJsonArray("warnings").map { it.asString }
        assertEquals("the partial line is reported: $warnings", 1, warnings.size)
        assertTrue(warnings.single(), warnings.single().contains("s-1"))
        assertFalse(export.out + export.err, (export.out + export.err).contains("SECRET-PROMPT-TEXT"))
        assertTrue("the progress names the session", export.err.contains("s-1"))

        val bob = claudeHomeOf("bob")
        val target = Files.createDirectories(root.resolve("bob").resolve("work")).toString()
        val import = run("--json", "--verbose", "--home", bob.toString(), "import", archive.toString(), "--target", target, "--log-dir", logDir.toString())
        assertEquals(import.out + import.err, 0, import.exitCode)
        assertTrue(import.err, import.err.contains("s-1"))
        assertFalse(import.out + import.err, (import.out + import.err).contains("SECRET-PROMPT-TEXT"))
    }

    @Test
    fun `the import of an archive without a transcript warns by id`() {
        val archive = writeZip(root.resolve("bare.zip"), manifestJson("s-bare"))
        val result = run("--json", "--home", claudeHomeOf("bob").toString(), "import", archive.toString(), "--target", root.resolve("t").toString(), "--log-dir", logDir.toString())

        val warnings = result.json().getAsJsonArray("warnings").map { it.asString }
        assertTrue(warnings.toString(), warnings.any { it.startsWith("s-bare:") })
    }
}
