package com.github.fabiopelliccia.sessionporter.cli

import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ListAndInspectTest : CliTest() {

    private val aliceWork = "C:\\Users\\alice\\work"
    private val aliceOther = "C:\\Users\\alice\\other-project"

    private fun JsonObject.sessionIds(): List<String> = getAsJsonArray("sessions").map { it.asJsonObject.get("id").asString }

    // ------------------------------------------------------------------------------------- list --

    @Test
    fun `list shows the sessions most recent first, with the keys the plugin reads`() {
        val home = claudeHomeOf("alice")
        writeSession(home, "s-old", aliceWork, title = "Older session", day = 3)
        writeSession(home, "s-new", aliceOther, title = "Newer session", day = 10)

        val result = run("--home", home.toString(), "list", "--json")

        assertEquals(result.err, 0, result.exitCode)
        val json = result.json()
        assertEquals(1, json.get("schemaVersion").asInt)
        assertTrue(json.get("ok").asBoolean)
        assertEquals("list", json.get("command").asString)
        assertTrue(json.get("error").isJsonNull)
        assertEquals(0, json.getAsJsonArray("warnings").size())
        val data = result.data()
        assertEquals(2, data.get("totalMatching").asInt)
        assertEquals(listOf("s-new", "s-old"), data.sessionIds())

        val first = data.getAsJsonArray("sessions")[0].asJsonObject
        assertEquals(
            setOf(
                "id", "title", "projectPath", "projectName", "projectFolderName", "firstTimestamp", "lastTimestamp",
                "messageCount", "sizeBytes", "gitBranch", "hasAuxData", "hasFileHistory",
            ),
            first.keySet(),
        )
        assertEquals("Newer session", first.get("title").asString)
        assertEquals(aliceOther, first.get("projectPath").asString)
        assertEquals("other-project", first.get("projectName").asString)
        assertEquals("C--Users-alice-other-project", first.get("projectFolderName").asString)
        assertEquals("2024-03-10T09:00:00.000Z", first.get("firstTimestamp").asString)
        assertEquals("2024-03-10T09:00:05.000Z", first.get("lastTimestamp").asString)
        assertEquals(2, first.get("messageCount").asInt)
        assertTrue(first.get("sizeBytes").asLong > 0)
        assertTrue(first.get("gitBranch").isJsonNull)
        assertFalse(first.get("hasAuxData").asBoolean)
        assertFalse(first.get("hasFileHistory").asBoolean)
    }

    @Test
    fun `list filters by project, whatever way the folder is written`() {
        val home = claudeHomeOf("alice")
        writeSession(home, "s-work", aliceWork)
        writeSession(home, "s-other", aliceOther)

        for (spelling in listOf("C:\\Users\\alice\\work", "C:/Users/alice/work/", "c:\\users\\alice\\WORK")) {
            val result = run("--home", home.toString(), "list", "--project", spelling, "--json")
            assertEquals(spelling, listOf("s-work"), result.data().sessionIds())
        }
        // The Git Bash spelling of the same folder, as `$PWD` hands it over on Windows.
        val gitBash = run("--home", home.toString(), "list", "--project", "/c/Users/alice/work", "--json", windows = true)
        assertEquals(listOf("s-work"), gitBash.data().sessionIds())
        // The same words are not a Windows folder on another system.
        val elsewhere = run("--home", home.toString(), "list", "--project", "/c/Users/alice/work", "--json")
        assertEquals(emptyList<String>(), elsewhere.data().sessionIds())
    }

    @Test
    fun `list filters by date and limits the count`() {
        val home = claudeHomeOf("alice")
        writeSession(home, "s-a", aliceWork, day = 1)
        writeSession(home, "s-b", aliceWork, day = 8)
        writeSession(home, "s-c", aliceWork, day = 11)

        // The clock of the tests says 12 March 2024: seven days back is 5 March.
        assertEquals(listOf("s-c", "s-b"), run("--home", home.toString(), "list", "--since", "7d", "--json").data().sessionIds())
        assertEquals(listOf("s-c"), run("--home", home.toString(), "list", "--since", "2024-03-10", "--json").data().sessionIds())
        assertEquals(listOf("s-c"), run("--home", home.toString(), "list", "--since", "2024-03-11T00:00:00Z", "--json").data().sessionIds())
        assertEquals(listOf("s-c"), run("--home", home.toString(), "list", "--since", "24h", "--json").data().sessionIds())

        val limited = run("--home", home.toString(), "list", "--limit", "2", "--json").data()
        assertEquals(listOf("s-c", "s-b"), limited.sessionIds())
        assertEquals("the total is what matched, not what is shown", 3, limited.get("totalMatching").asInt)
    }

    @Test
    fun `list in plain mode is a table`() {
        val home = claudeHomeOf("alice")
        writeSession(home, "s-1", aliceWork, title = "Fix the build")

        val result = run("--home", home.toString(), "list")

        assertEquals(0, result.exitCode)
        assertTrue(result.out, result.out.contains("Fix the build"))
        assertTrue(result.out, result.out.contains("s-1"))
        assertTrue(result.out, result.out.contains("2024-03-10 09:00"))
        assertEquals("", result.err)
    }

    @Test
    fun `list of an empty home says so`() {
        val home = claudeHomeOf("alice")
        assertEquals(emptyList<String>(), run("--home", home.toString(), "list", "--json").data().sessionIds())
        assertEquals("No sessions found.", run("--home", home.toString(), "list").out.trim())
    }

    // ---------------------------------------------------------------------------------- inspect --

    @Test
    fun `inspect tells what an archive holds and which sessions the machine already has`() {
        val alice = claudeHomeOf("alice")
        writeSession(alice, "s-1", aliceWork, title = "First")
        writeSession(alice, "s-2", aliceOther, title = "Second")
        val archive = exportArchive(alice, "s-1", "s-2")
        val bob = claudeHomeOf("bob")
        writeSession(bob, "s-2", "/home/bob/elsewhere", title = "Second")

        val result = run("--home", bob.toString(), "inspect", archive.toString(), "--json")

        assertEquals(result.err, 0, result.exitCode)
        val data = result.data()
        assertEquals(2, data.get("formatVersion").asInt)
        assertTrue(data.get("anonymized").asBoolean)
        assertEquals("cli", data.get("producerComponent").asString)
        assertEquals(CliVersion.value, data.get("producerVersion").asString)
        assertEquals("Session Porter for Claude Code", data.get("producer").asString)
        assertEquals(listOf("s-2"), data.getAsJsonArray("alreadyPresentIds").map { it.asString })
        val byId = data.getAsJsonArray("sessions").associate { it.asJsonObject.get("id").asString to it.asJsonObject }
        assertEquals("work", byId.getValue("s-1").get("projectName").asString)
        assertEquals("other-project", byId.getValue("s-2").get("projectName").asString)
        // A format 2 archive holds tokens where the paths were: they are never shown.
        assertTrue(byId.getValue("s-1").get("projectPath").isJsonNull)
        assertFalse(result.out.contains("{{SP"))
        assertFalse(byId.getValue("s-1").get("presentLocally").asBoolean)
        assertTrue(byId.getValue("s-2").get("presentLocally").asBoolean)
        assertEquals("First", byId.getValue("s-1").get("title").asString)
    }

    @Test
    fun `inspect of a format 1 archive shows the paths it holds`() {
        val archive = formatOneArchive("s-v1")
        val data = run("--home", claudeHomeOf("bob").toString(), "inspect", archive.toString(), "--json").data()

        assertEquals(1, data.get("formatVersion").asInt)
        assertFalse(data.get("anonymized").asBoolean)
        val session = data.getAsJsonArray("sessions")[0].asJsonObject
        assertEquals("C:\\Users\\alice\\work", session.get("projectPath").asString)
        assertEquals("work", session.get("projectName").asString)
    }

    @Test
    fun `inspect in plain mode lists the sessions and whether they are local`() {
        val alice = claudeHomeOf("alice")
        writeSession(alice, "s-1", aliceWork, title = "First")
        val archive = exportArchive(alice, "s-1")

        val result = run("--home", alice.toString(), "inspect", archive.toString())

        assertEquals(0, result.exitCode)
        assertTrue(result.out, result.out.contains("Archive format 2 (paths anonymized)"))
        assertTrue(result.out, result.out.contains("First"))
        assertTrue(result.out, result.out.contains("yes"))
    }
}
