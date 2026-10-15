package com.github.fabiopelliccia.sessionporter.cli

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/** Finding 6 of the WP6 review: arguments that survive the ANSI command line of `java.exe` on Windows. */
class UnicodeArgumentsTest : CliTest() {

    private val omega = "\u03A9"

    @Test
    fun `the arguments of the process are used as they are when there are some`() {
        val variables = mapOf(ArgumentSource.VARIABLE to "list\n")
        assertEquals(listOf("version"), ArgumentSource.resolve(listOf("version"), variables::get))
    }

    @Test
    fun `without arguments they come from the variable, one per line`() {
        val packed = ArgumentSource.pack(listOf("--json", "--home", "C:\\Users\\psΩhome", "list", "--since", "u*"))
        assertEquals(
            listOf("--json", "--home", "C:\\Users\\psΩhome", "list", "--since", "u*"),
            ArgumentSource.resolve(emptyList(), mapOf(ArgumentSource.VARIABLE to packed)::get),
        )
    }

    @Test
    fun `an empty argument survives the trip, and so does having none`() {
        val oneEmpty = ArgumentSource.pack(listOf("--home", ""))
        assertEquals(listOf("--home", ""), ArgumentSource.unpack(oneEmpty))
        assertEquals(listOf(""), ArgumentSource.unpack(ArgumentSource.pack(listOf(""))))
        assertEquals(emptyList<String>(), ArgumentSource.unpack(ArgumentSource.pack(emptyList())))
        assertEquals(emptyList<String>(), ArgumentSource.resolve(emptyList()) { null })
    }

    @Test
    fun `a trailing carriage return is part of the argument, not something the launcher may strip`() {
        assertEquals(listOf("a\r", "b"), ArgumentSource.unpack("a\r\nb\n"))
    }

    @Test
    fun `a real JVM given only the variable runs the command with a path no ANSI code page can carry`() {
        // On Linux and macOS the file system itself must be able to name the folder.
        val onWindows = System.getProperty("os.name").startsWith("Windows")
        val systemCharset = Charset.forName(System.getProperty("sun.jnu.encoding", "UTF-8"))
        assumeTrue("this system cannot name a folder with $omega", onWindows || systemCharset.newEncoder().canEncode(omega[0]))

        val home = Files.createDirectories(root.resolve("ps${omega}home").resolve(".claude"))
        writeSession(home, "s-1", "C:\\Users\\alice\\work", title = "Zoë 日本語")
        val java = System.getProperty("java.home") + java.io.File.separator + "bin" + java.io.File.separator + "java"
        val command = listOf(java, "-Dfile.encoding=UTF-8", "-cp", System.getProperty("java.class.path"), "com.github.fabiopelliccia.sessionporter.cli.MainKt")
        val process = ProcessBuilder(command).also {
            // No argument at all on the command line of the JVM; everything goes through the environment, which is Unicode.
            it.environment()[ArgumentSource.VARIABLE] = ArgumentSource.pack(listOf("--json", "--home", home.toString(), "list"))
            it.redirectError(ProcessBuilder.Redirect.DISCARD)
        }.start()

        val output = process.inputStream.readBytes()
        assertTrue("the process ends", process.waitFor(60, TimeUnit.SECONDS))
        val json = JsonParser.parseString(String(output, StandardCharsets.UTF_8)).asJsonObject
        assertEquals(String(output, StandardCharsets.UTF_8), 0, process.exitValue())
        assertEquals(home.toString(), json.getAsJsonObject("data").get("home").asString)
        assertEquals("Zoë 日本語", json.getAsJsonObject("data").getAsJsonArray("sessions")[0].asJsonObject.get("title").asString)
    }
}
