package com.github.fabiopelliccia.sessionporter.cli

import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/** Findings 3, 8, 9 and 13 of the WP6 review: which sessions a project filter takes, `--since`, empty paths, spelling of paths. */
class SelectionAndPathsTest : CliTest() {

    private fun CliResult.errorCode(): String = json().getAsJsonObject("error").get("code").asString

    private fun JsonObject.sessionIds(): List<String> = getAsJsonArray("sessions").map { it.asJsonObject.get("id").asString }

    private val onWindows = System.getProperty("os.name").startsWith("Windows")

    // ------------------------------------------------------------------------------- finding 3 --

    @Test
    fun `two folders whose Claude folder name is the same are told apart by their path`() {
        val home = claudeHomeOf("alice")
        // Claude Code turns every character outside letters and digits into a dash, so both are `C--work-b-c`.
        writeSession(home, "s-dash", "C:\\work\\b-c")
        writeSession(home, "s-nested", "C:\\work\\b\\c")

        val nested = run("--home", home.toString(), "list", "--project", "C:\\work\\b\\c", "--json")
        assertEquals(listOf("s-nested"), nested.data().sessionIds())
        val dash = run("--home", home.toString(), "list", "--project", "C:\\work\\b-c", "--json")
        assertEquals(listOf("s-dash"), dash.data().sessionIds())

        val archive = root.resolve("nested.zip")
        run("--json", "--home", home.toString(), "export", "--project", "C:\\work\\b\\c", "--out", archive.toString(), "--log-dir", logDir.toString())
        assertEquals("the export takes only the folder asked for", setOf("s-nested"), sessionIdsIn(archive))
    }

    @Test
    fun `folders that differ by a dash, an underscore, a dot or a space are not mixed up`() {
        val home = claudeHomeOf("alice")
        val spellings = mapOf("s-dash" to "my-app", "s-underscore" to "my_app", "s-dot" to "my.app", "s-space" to "my app")
        spellings.forEach { (id, folder) -> writeSession(home, id, "C:\\work\\$folder") }

        for ((id, folder) in spellings) {
            val result = run("--home", home.toString(), "list", "--project", "C:\\work\\$folder", "--json")
            assertEquals(folder, listOf(id), result.data().sessionIds())
        }
    }

    // ------------------------------------------------------------------------------- finding 8 --

    @Test
    fun `a --since too far back to be a date is a usage error, not an internal one`() {
        val home = claudeHomeOf("alice")
        for (since in listOf("9999999999999d", "99999999999999999999d", "9999999999999999w", "9223372036854775807h")) {
            val result = run("--json", "--home", home.toString(), "list", "--since", since)
            assertEquals(since + ": " + result.out, ExitCode.USAGE, result.exitCode)
            assertEquals("invalidSince", result.errorCode())
        }
        // A large but meaningful one is still fine.
        assertEquals(0, run("--json", "--home", home.toString(), "list", "--since", "5000w").exitCode)
    }

    // ------------------------------------------------------------------------------- finding 9 --

    @Test
    fun `an empty path option is refused, it never means the current folder`() {
        val alice = claudeHomeOf("alice")
        writeSession(alice, "s-1", "C:\\Users\\alice\\work")
        val archive = exportArchive(alice, "s-1")
        val bob = claudeHomeOf("bob")
        val target = root.resolve("bob").resolve("work").toString()
        val out = root.resolve("out.zip").toString()

        val attempts = listOf(
            listOf("--home", "", "list"),
            listOf("--home=", "list"),
            listOf("--home", " ", "import", archive.toString(), "--target", target),
            listOf("--home", bob.toString(), "import", archive.toString(), "--target", ""),
            listOf("--home", bob.toString(), "import", archive.toString(), "--target", target, "--log-dir", ""),
            listOf("--home", alice.toString(), "export", "--all", "--out", ""),
            listOf("--home", alice.toString(), "export", "--all", "--out", out, "--log-dir="),
            listOf("--home", alice.toString(), "export", "--ids-file", "", "--out", out),
            listOf("--home", alice.toString(), "export", "--project", "", "--out", out),
            listOf("--home", alice.toString(), "list", "--project", ""),
            listOf("--home", alice.toString(), "inspect", ""),
            listOf("--home", alice.toString(), "doctor", "--log-dir", ""),
        )
        for (arguments in attempts) {
            val result = run("--json", *arguments.toTypedArray())
            assertEquals(arguments.toString() + ": " + result.out, ExitCode.USAGE, result.exitCode)
            assertEquals(arguments.toString(), "invalidPath", result.errorCode())
        }
        assertEquals("nothing was written into the folder the tests run in", emptyList<Path>(), transcriptsIn(bob))
        assertFalse(Files.exists(root.resolve("out.zip")))
    }

    // ------------------------------------------------------------------------------ finding 13 --

    @Test
    fun `CLAUDE_CONFIG_DIR is read the way --home is, a Git Bash path included`() {
        assumeTrue("a Git Bash drive path means something only on Windows", onWindows)
        val home = claudeHomeOf("alice")
        writeSession(home, "s-1", "C:\\Users\\alice\\work")
        val drive = home.root.toString().first().lowercaseChar()
        val gitBashPath = "/$drive/" + home.root.relativize(home).toString().replace('\\', '/')

        val result = run("--json", "list", windows = true, environment = mapOf("CLAUDE_CONFIG_DIR" to gitBashPath))

        assertEquals(result.out + result.err, 0, result.exitCode)
        assertEquals(home.toString(), result.data().get("home").asString)
        assertEquals(listOf("s-1"), result.data().sessionIds())
    }

    @Test
    fun `the home comes from --home, then the environment, then the folder of the user`() {
        val fromOption = claudeHomeOf("option")
        val fromVariable = claudeHomeOf("variable")
        val fromUser = Files.createDirectories(runnerHome.resolve(".claude"))

        assertEquals(fromOption.toString(), run("--json", "--home", fromOption.toString(), "list", environment = mapOf("CLAUDE_CONFIG_DIR" to fromVariable.toString())).data().get("home").asString)
        assertEquals(fromVariable.toString(), run("--json", "list", environment = mapOf("CLAUDE_CONFIG_DIR" to fromVariable.toString())).data().get("home").asString)
        assertEquals(fromUser.toString(), run("--json", "list").data().get("home").asString)
        assertEquals("a blank variable counts as none", fromUser.toString(), run("--json", "list", environment = mapOf("CLAUDE_CONFIG_DIR" to " ")).data().get("home").asString)
    }

    @Test
    fun `a target folder that exists is recorded as the disk spells it`() {
        val alice = claudeHomeOf("alice")
        writeSession(alice, "s-1", "C:\\Users\\alice\\work")
        val archive = exportArchive(alice, "s-1")
        val target = Files.createDirectories(root.resolve("Bob Work"))
        // The path the folder of the tests has before it is resolved is an 8.3 one where the system temp folder is (PELLIC~1).
        val unresolved = tmp.root.toPath().resolve("Bob Work")

        val result = run("--json", "--home", claudeHomeOf("bob").toString(), "import", archive.toString(), "--target", unresolved.toString(), "--log-dir", logDir.toString())

        assertEquals(result.out + result.err, 0, result.exitCode)
        assertEquals(target.toString(), result.data().get("target").asString)
        assertEquals(target.toString(), result.data().getAsJsonArray("imported")[0].asJsonObject.get("cwd").asString)
        assertEquals("the folder was found, so there is nothing to warn about", 0, result.json().getAsJsonArray("warnings").size())
    }

    @Test
    fun `the case of a target folder is the one on the disk`() {
        assumeTrue("file names are case insensitive only on Windows (and macOS)", onWindows)
        val alice = claudeHomeOf("alice")
        writeSession(alice, "s-1", "C:\\Users\\alice\\work")
        val archive = exportArchive(alice, "s-1")
        val target = Files.createDirectories(root.resolve("Bob Work"))

        val result = run("--json", "--home", claudeHomeOf("bob").toString(), "import", archive.toString(), "--target", root.resolve("bob WORK").toString().uppercase(), "--log-dir", logDir.toString())

        assertEquals(result.out + result.err, 0, result.exitCode)
        assertEquals(target.toString(), result.data().get("target").asString)
    }
}
