package com.github.fabiopelliccia.sessionporter.cli

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files

/** What reaches standard output and standard error: the JSON mode, the encoding, the language, `--verbose`. */
class OutputTest : CliTest() {

    private val aliceWork = "C:\\Users\\alice\\work"
    private val title = "Zoë ha scritto 日本語 – naïve façade"

    // ---------------------------------------------------------------------------------- JSON --

    /** One of everything: a command that works, one that fails, one that ends partially, for each command. */
    @Test
    fun `in JSON mode standard output is one valid document for every command, and standard error is empty`() {
        val alice = claudeHomeOf("alice")
        writeSession(alice, "s-1", aliceWork, title = title)
        val archive = exportArchive(alice, "s-1")
        val bob = claudeHomeOf("bob")
        val target = Files.createDirectories(root.resolve("bob").resolve("work")).toString()
        val missingHome = root.resolve("none").toString()

        val runs = listOf(
            run("--json", "--home", alice.toString(), "list"),
            run("--json", "--home", alice.toString(), "inspect", archive.toString()),
            run("--json", "--home", alice.toString(), "export", "--all", "--out", root.resolve("e.zip").toString(), "--log-dir", logDir.toString()),
            run("--json", "--home", bob.toString(), "import", archive.toString(), "--target", target, "--log-dir", logDir.toString()),
            run("--json", "--home", alice.toString(), "doctor", "--log-dir", logDir.toString()),
            run("--json", "version"),
            run("--json", "--help"),
            // The failures: usage, archive, environment, partial.
            run("--json", "bogus"),
            run("--json", "inspect", root.resolve("e.zip").toString() + ".nope"),
            run("--json", "--home", missingHome, "list"),
            run("--json", "--home", bob.toString(), "import", archive.toString(), "--target", root.resolve("nowhere").toString(), "--log-dir", logDir.toString()),
            run("--json", "--home", missingHome, "doctor", "--log-dir", logDir.toString()),
        )

        for (result in runs) {
            val json = result.json()
            assertEquals(result.out, 1, json.get("schemaVersion").asInt)
            assertEquals(result.out, setOf("schemaVersion", "ok", "command", "data", "warnings", "error"), json.keySet())
            assertEquals(result.out, result.exitCode == 0, json.get("ok").asBoolean)
            assertEquals(result.out, result.exitCode == 0, json.get("error").isJsonNull)
            assertTrue(result.out, json.get("warnings").isJsonArray)
            assertEquals("nothing on standard error without --verbose: ${result.err}", "", result.err)
            if (result.exitCode != 0) {
                assertEquals(setOf("code", "message"), json.getAsJsonObject("error").keySet())
            }
        }
        assertEquals(
            listOf("list", "inspect", "export", "import", "doctor", "version", "help", null, "inspect", "list", "import", "doctor"),
            runs.map { r -> r.json().get("command").let { if (it.isJsonNull) null else it.asString } },
        )
        assertEquals(listOf(0, 0, 0, 0, 0, 0, 0, 2, 4, 4, 5, 4), runs.map { it.exitCode })
    }

    @Test
    fun `--verbose writes progress to standard error and leaves standard output alone`() {
        val alice = claudeHomeOf("alice")
        writeSession(alice, "s-1", aliceWork)

        val result = run("--json", "--verbose", "--home", alice.toString(), "export", "--all", "--out", root.resolve("e.zip").toString(), "--log-dir", logDir.toString())

        assertEquals(0, result.exitCode)
        result.json()
        assertTrue(result.err, result.err.contains("[session-porter] exporting 1 sessions"))
        assertTrue(result.err, result.err.contains("%"))
    }

    @Test
    fun `no conversation text reaches the JSON of an export or an import, only names and counts`() {
        val alice = claudeHomeOf("alice")
        val transcript = writeSession(alice, "s-1", aliceWork, title = "SECRET-PROMPT-TEXT")
        // A line still being written makes the export warn: the test is only worth something if there is a warning to look at.
        Files.writeString(transcript, Files.readString(transcript) + "{\"type\":\"user\",\"cw")
        val export = run("--json", "--verbose", "--home", alice.toString(), "export", "--all", "--out", root.resolve("e.zip").toString(), "--log-dir", logDir.toString())
        val target = Files.createDirectories(root.resolve("bob").resolve("work")).toString()
        val import = run("--json", "--verbose", "--home", claudeHomeOf("bob").toString(), "import", root.resolve("e.zip").toString(), "--target", target, "--log-dir", logDir.toString())

        assertTrue("the export has a warning: ${export.out}", export.json().getAsJsonArray("warnings").size() > 0)
        assertFalse(export.out + export.err, (export.out + export.err).contains("SECRET-PROMPT-TEXT"))
        assertFalse(import.out + import.err, (import.out + import.err).contains("SECRET-PROMPT-TEXT"))
        Files.list(logDir).use { logs -> logs.forEach { assertFalse("$it", Files.readString(it).contains("SECRET-PROMPT-TEXT")) } }
        // The title is what `list` and `inspect` are for.
        assertTrue(run("--json", "--home", alice.toString(), "list").out.contains("SECRET-PROMPT-TEXT"))
    }

    // --------------------------------------------------------------------------------- UTF-8 --

    @Test
    fun `a non ASCII title comes out as UTF-8, in JSON and in plain mode`() {
        val alice = claudeHomeOf("alice")
        writeSession(alice, "s-1", aliceWork, title = title)

        val json = run("--json", "--home", alice.toString(), "list")
        val plain = run("--home", alice.toString(), "list")

        for (result in listOf(json, plain)) {
            assertEquals(0, result.exitCode)
            assertTrue(result.out, result.out.contains(title))
            // Not only after decoding: the bytes are the UTF-8 ones (ë is C3 AB, 日 is E6 97 A5), not an escape and not a single byte.
            val bytes = result.stdoutBytes
            assertTrue(indexOf(bytes, "ë".toByteArray(StandardCharsets.UTF_8)) >= 0)
            assertTrue(indexOf(bytes, "日".toByteArray(StandardCharsets.UTF_8)) >= 0)
        }
        assertEquals(title, json.data().getAsJsonArray("sessions")[0].asJsonObject.get("title").asString)
    }

    @Test
    fun `the streams of the process write UTF-8 whatever the default charset is`() {
        val sink = ByteArrayOutputStream()
        val stream = CliEnvironment.utf8(sink)
        stream.print("Zoë 日本")
        stream.flush()
        assertEquals("Zoë 日本", String(sink.toByteArray(), StandardCharsets.UTF_8))
        assertEquals(listOf(0x5A, 0x6F, 0xC3, 0xAB), sink.toByteArray().take(4).map { it.toInt() and 0xFF })
    }

    @Test
    fun `a title with a non ASCII character survives an export and an import`() {
        val alice = claudeHomeOf("alice")
        writeSession(alice, "s-1", aliceWork, title = title)
        val archive = exportArchive(alice, "s-1")

        val inspected = run("--json", "--home", claudeHomeOf("bob").toString(), "inspect", archive.toString())
        assertEquals(title, inspected.data().getAsJsonArray("sessions")[0].asJsonObject.get("title").asString)
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        for (start in 0..haystack.size - needle.size) {
            if (needle.indices.all { haystack[start + it] == needle[it] }) return start
        }
        return -1
    }

    // ------------------------------------------------------------------------------- language --

    @Test
    fun `--lang changes the messages and not the codes, and the language is put back afterwards`() {
        val before = java.util.Locale.getDefault(java.util.Locale.Category.DISPLAY)

        val italian = run("--json", "--lang", "it", "bogus")
        val json = italian.json().getAsJsonObject("error")
        assertEquals("unknownCommand", json.get("code").asString)
        assertEquals("Comando \"bogus\" sconosciuto.", json.get("message").asString)

        val japanese = run("--lang=ja", "export", "--out", "x.zip")
        assertTrue(japanese.err, japanese.err.contains("エクスポートするセッションを指定してください"))

        val fromEnvironment = run("--json", "bogus", environment = mapOf("SESSION_PORTER_LANG" to "de"))
        assertEquals("Unbekannter Befehl \"bogus\".", fromEnvironment.json().getAsJsonObject("error").get("message").asString)

        // An option beats the environment; a language the bundle does not have is English.
        assertEquals("Unknown command \"bogus\".", run("--json", "--lang", "fi", "bogus", environment = mapOf("SESSION_PORTER_LANG" to "de")).json().getAsJsonObject("error").get("message").asString)

        assertEquals(before, java.util.Locale.getDefault(java.util.Locale.Category.DISPLAY))
    }

    @Test
    fun `the plain output follows --lang too`() {
        val home = claudeHomeOf("alice")
        assertEquals("Nessuna sessione trovata.", run("--lang", "it", "--home", home.toString(), "list").out.trim())
    }
}
