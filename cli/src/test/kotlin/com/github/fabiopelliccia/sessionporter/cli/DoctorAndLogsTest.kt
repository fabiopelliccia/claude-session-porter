package com.github.fabiopelliccia.sessionporter.cli

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Paths

class DoctorAndLogsTest : CliTest() {

    private fun environment(osName: String, userHome: String, variables: Map<String, String> = emptyMap()) =
        CliEnvironment(
            input = java.io.ByteArrayInputStream(ByteArray(0)),
            out = CliEnvironment.utf8(java.io.ByteArrayOutputStream()),
            err = CliEnvironment.utf8(java.io.ByteArrayOutputStream()),
            osName = osName,
            userHome = userHome,
            getenv = variables::get,
        )

    // ---------------------------------------------------------------------------- doctor --

    @Test
    fun `doctor reports the environment and every check passes on a usable home`() {
        val home = claudeHomeOf("alice")
        writeSession(home, "s-1", "C:\\Users\\alice\\work")

        val result = run("--json", "--home", home.toString(), "doctor", "--log-dir", logDir.toString())

        assertEquals(result.out + result.err, 0, result.exitCode)
        val data = result.data()
        assertEquals(CliVersion.value, data.getAsJsonObject("cli").get("version").asString)
        assertEquals(2, data.getAsJsonObject("cli").get("formatVersion").asInt)
        assertTrue(data.getAsJsonObject("java").get("version").asString.isNotBlank())
        val homeInfo = data.getAsJsonObject("home")
        assertTrue(homeInfo.get("exists").asBoolean)
        assertTrue(homeInfo.get("projectsDirWritable").asBoolean)
        assertEquals(1, homeInfo.get("sessionCount").asInt)
        assertEquals(logDir.toAbsolutePath().toString(), data.getAsJsonObject("logDir").get("path").asString)
        assertTrue(data.getAsJsonObject("logDir").get("writable").asBoolean)
        val checks = data.getAsJsonArray("checks").associate { it.asJsonObject.get("id").asString to it.asJsonObject.get("ok").asBoolean }
        assertEquals(mapOf("java" to true, "home" to true, "projects" to true, "logs" to true), checks)
    }

    @Test
    fun `doctor accepts a home that has no projects folder yet`() {
        val home = claudeHomeOf("alice")
        val result = run("--json", "--home", home.toString(), "doctor", "--log-dir", logDir.toString())
        assertEquals(0, result.exitCode)
        assertFalse(result.data().getAsJsonObject("home").get("projectsDirExists").asBoolean)
        assertEquals(0, result.data().getAsJsonObject("home").get("sessionCount").asInt)
    }

    @Test
    fun `doctor fails with exit 4 on a home that is not there, and still reports the rest`() {
        val result = run("--json", "--home", root.resolve("none").toString(), "doctor", "--log-dir", logDir.toString())

        assertEquals(ExitCode.ENVIRONMENT, result.exitCode)
        val json = result.json()
        assertEquals("environmentProblem", json.getAsJsonObject("error").get("code").asString)
        val checks = json.getAsJsonObject("data").getAsJsonArray("checks").associate { it.asJsonObject.get("id").asString to it.asJsonObject.get("ok").asBoolean }
        assertFalse(checks.getValue("home"))
        assertFalse(checks.getValue("projects"))
        assertTrue(checks.getValue("java"))
    }

    @Test
    fun `doctor in plain mode lists the checks`() {
        val home = claudeHomeOf("alice")
        val result = run("--home", home.toString(), "doctor", "--log-dir", logDir.toString())
        assertTrue(result.out, result.out.contains("OK    Claude home:"))
        assertTrue(result.out, result.out.contains("session-porter ${CliVersion.value} (archive format 2)"))
    }

    @Test
    fun `version reports the CLI and the archive format`() {
        val result = run("--json", "version")
        assertEquals(CliVersion.value, result.data().get("version").asString)
        assertEquals(2, result.data().get("formatVersion").asInt)
        assertEquals("session-porter ${CliVersion.value} (archive format 2)", run("version").out.trim())
        assertEquals("1.0.0", CliVersion.value)
    }

    // ----------------------------------------------------------------------------- logs --

    @Test
    fun `the default log folder is the one of each operating system`() {
        assertEquals(
            Paths.get("C:\\Users\\a\\AppData\\Local", "SessionPorter", "logs"),
            LogLocations.defaultDirectory(environment("Windows 11", "C:\\Users\\a", mapOf("LOCALAPPDATA" to "C:\\Users\\a\\AppData\\Local"))),
        )
        assertEquals(
            Paths.get("C:\\Users\\a", "AppData", "Local", "SessionPorter", "logs"),
            LogLocations.defaultDirectory(environment("Windows 11", "C:\\Users\\a")),
        )
        assertEquals(Paths.get("/Users/a", "Library", "Logs", "SessionPorter"), LogLocations.defaultDirectory(environment("Mac OS X", "/Users/a")))
        assertEquals(
            Paths.get("/state", "session-porter", "logs"),
            LogLocations.defaultDirectory(environment("Linux", "/home/a", mapOf("XDG_STATE_HOME" to "/state"))),
        )
        assertEquals(
            Paths.get("/home/a", ".local", "state", "session-porter", "logs"),
            LogLocations.defaultDirectory(environment("Linux", "/home/a")),
        )
    }

    @Test
    fun `without --log-dir the log goes to the folder of the system`() {
        val alice = claudeHomeOf("alice")
        writeSession(alice, "s-1", "C:\\Users\\alice\\work")

        val result = run("--json", "--home", alice.toString(), "export", "--all", "--out", root.resolve("e.zip").toString())

        val expected = runnerHome.resolve(".local").resolve("state").resolve("session-porter").resolve("logs")
        assertEquals(expected.resolve("export-20240312-083015.log").toAbsolutePath().toString(), result.data().get("logFile").asString)
        assertTrue(Files.isRegularFile(expected.resolve("export-20240312-083015.log")))
    }

    @Test
    fun `a log folder that cannot be written does not fail the command`() {
        val alice = claudeHomeOf("alice")
        writeSession(alice, "s-1", "C:\\Users\\alice\\work")
        val aFile = root.resolve("a-file").also { Files.writeString(it, "x") }

        val result = run("--json", "--home", alice.toString(), "export", "--all", "--out", root.resolve("e.zip").toString(), "--log-dir", aFile.resolve("logs").toString())

        assertEquals(result.out + result.err, 0, result.exitCode)
        assertTrue(result.data().get("logFile").isJsonNull)
    }
}
