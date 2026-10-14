package com.github.fabiopelliccia.claudesessionsimportexport.core

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.zip.ZipFile
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

/**
 * Simulates the scenario the plugin exists for, end to end: **PC_1** exports its sessions to a ZIP,
 * the ZIP is carried to **PC_2**, and PC_2 imports it and lists the sessions as Claude Code would.
 *
 * The two machines never share anything but the archive:
 *
 * - each one has its own Claude Code home, under its own OS account (`alice` on PC_1, `bob` on
 *   PC_2), and PC_1's home is deleted before PC_2 imports, so nothing on PC_2 can lean on it;
 * - alice's project folders are spelled the way her machine records them and do not exist on PC_2;
 *   bob's are real folders of PC_2, handed over the way IntelliJ spells `project.basePath`;
 * - the archive is copied to PC_2's own download folder under another name before it is read.
 *
 * "Lists them correctly" is checked with the same reading `claude --resume` does: the transcript
 * sits in the `projects/` folder named after bob's working directory, every working directory names
 * bob's folder, the session reads as one that just happened, and the visibility diagnosis of the
 * import passes all of its checks. PC_1's sessions are shaped after real transcripts: identity and
 * environment attachments, checkpoints with their backups, a subagent run, tool results, a cost line.
 */
class CrossMachineTransferTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private companion object {
        val MILLIS: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)
    }

    private val gson = GsonBuilder().serializeNulls().disableHtmlEscaping().create()

    // ------------------------------------------------------------------------------ machines --

    /** One physical machine and its OS account: a root standing in for the account's home folder. */
    private inner class Pc(name: String, val user: String) {
        val root: Path = tmp.newFolder(name, "Users", user).toPath()

        /** The Claude Code home: not created, so a machine that never ran Claude Code is the default. */
        val home: Path = root.resolve(".claude")

        /** A real folder of this machine, spelled the way IntelliJ hands a project over (`/` separators). */
        fun projectFolder(vararg parts: String): String {
            val dir = parts.fold(root) { path, part -> path.resolve(part) }
            Files.createDirectories(dir)
            return dir.toAbsolutePath().toString().replace('\\', '/')
        }

        /** Carries a file from another machine into this one's download folder, as a USB stick or a mail would. */
        fun receive(file: Path, name: String): Path {
            val downloads = Files.createDirectories(root.resolve("Downloads"))
            return Files.copy(file, downloads.resolve(name))
        }
    }

    private val aliceEmail = "alice.rossi@pc1.example"
    private val aliceGitName = "Alice Rossi"
    private val aliceRoot = "C:\\Users\\alice\\progetti\\demo"
    private val aliceStart: Instant = Instant.parse("2024-03-10T09:00:00Z")

    /**
     * Writes one PC_1 session below [root], the way Claude Code leaves it after a session that
     * edited a file and ran a subagent. Returns the session as PC_1's export table lists it.
     */
    private fun writePc1Session(
        home: Path,
        id: String,
        root: String,
        prompt: String,
        start: Instant = aliceStart,
        customTitle: String? = null,
        user: String = "alice",
    ): SessionInfo {
        val sep = if (ClaudePaths.isWindowsStyle(root)) "\\" else "/"
        val src = "$root${sep}src"
        val mainFile = "$src${sep}Main.kt"
        val tempDir = if (sep == "\\") "C:\\Users\\$user\\AppData\\Local\\Temp" else "/tmp/claude-$user"
        // Claude Code always writes milliseconds, `2024-03-10T09:00:00.000Z`.
        fun ts(seconds: Long) = MILLIS.format(start.plusSeconds(seconds))
        val common = mapOf("sessionId" to id, "version" to "2.1.275", "gitBranch" to "develop", "userType" to "external")

        val lines = listOfNotNull(
            common + mapOf(
                "parentUuid" to null, "isSidechain" to false, "type" to "user", "uuid" to "$id-u1",
                "message" to mapOf("role" to "user", "content" to prompt), "cwd" to root, "timestamp" to ts(0),
            ),
            common + mapOf(
                "type" to "attachment", "uuid" to "$id-at1", "cwd" to root, "timestamp" to ts(0),
                "attachment" to mapOf(
                    "type" to "session_context",
                    "context" to mapOf("userEmail" to "The user's email address is $aliceEmail.", "gitStatus" to "Git user: $aliceGitName\nCurrent branch: develop"),
                ),
                "rendered" to listOf(mapOf("content" to "<system-reminder>\nThe user's email address is $aliceEmail.\nGit user: $aliceGitName\nCurrent branch: develop\n</system-reminder>")),
            ),
            common + mapOf(
                "type" to "attachment", "uuid" to "$id-at2", "cwd" to root, "timestamp" to ts(1),
                "attachment" to mapOf(
                    "type" to "environment",
                    "snapshot" to mapOf(
                        "workingDirectory" to root,
                        "additionalWorkingDirectories" to listOf("$root${sep}lib"),
                        "scratchpadDirectory" to "$tempDir${sep}claude${sep}$id${sep}scratchpad",
                        "platform" to if (sep == "\\") "win32" else "linux",
                    ),
                ),
            ),
            customTitle?.let { mapOf("type" to "custom-title", "customTitle" to it, "sessionId" to id) },
            common + mapOf(
                "parentUuid" to "$id-u1", "isSidechain" to false, "type" to "assistant", "uuid" to "$id-a1", "cwd" to src, "timestamp" to ts(5),
                "message" to mapOf("role" to "assistant", "content" to listOf(mapOf("type" to "text", "text" to "Modifico Main.kt"))),
            ),
            mapOf(
                "type" to "file-history-snapshot", "messageId" to "$id-u1", "isSnapshotUpdate" to false,
                "snapshot" to mapOf(
                    "messageId" to "$id-u1", "timestamp" to ts(5),
                    "trackedFileBackups" to mapOf(
                        mainFile to mapOf("backupFileName" to "main@v1", "version" to 1, "backupTime" to ts(5), "realParentDir" to src),
                    ),
                ),
            ),
            common + mapOf(
                "type" to "file-history-delta", "timestamp" to ts(6),
                "backup" to mapOf("trackingPath" to mainFile, "backupFileName" to "main@v2", "backupTime" to ts(6), "realParentDir" to src),
            ),
            mapOf("type" to "cost-state", "sessionId" to id, "startTime" to start.toEpochMilli(), "totalCostUsd" to 0.42),
            common + mapOf(
                "parentUuid" to "$id-a1", "isSidechain" to false, "type" to "user", "uuid" to "$id-u2", "cwd" to root, "timestamp" to ts(10),
                // The user typed a path of their own machine: conversation content, kept verbatim.
                "message" to mapOf("role" to "user", "content" to "Leggi anche C:\\Users\\$user\\note.txt"),
            ),
            common + mapOf(
                "parentUuid" to "$id-u2", "isSidechain" to false, "type" to "assistant", "uuid" to "$id-a2", "cwd" to root, "timestamp" to ts(12),
                "message" to mapOf("role" to "assistant", "content" to listOf(mapOf("type" to "text", "text" to "Fatto."))),
                "toolUseResult" to mapOf("filePath" to mainFile),
            ),
        )

        val projectDir = home.resolve("projects").resolve(ClaudePaths.encodeProjectPath(root))
        Files.createDirectories(projectDir)
        Files.writeString(projectDir.resolve("$id.jsonl"), lines.joinToString("") { gson.toJson(it) + "\n" })

        val aux = projectDir.resolve(id)
        Files.createDirectories(aux.resolve("subagents"))
        Files.createDirectories(aux.resolve("tool-results"))
        Files.writeString(aux.resolve("tool-results").resolve("toolu_1.txt"), "Listing of $root\n")
        Files.writeString(aux.resolve("subagents").resolve("agent-a1.meta.json"), """{"agentType":"Explore","description":"Explore the project"}""")
        val subagentLines = listOf(
            common + mapOf(
                "parentUuid" to null, "isSidechain" to true, "agentId" to "a1", "type" to "user", "uuid" to "$id-s1", "cwd" to root, "timestamp" to ts(7),
                "message" to mapOf("role" to "user", "content" to "Esplora il progetto"),
            ),
            common + mapOf(
                "type" to "attachment", "isSidechain" to true, "agentId" to "a1", "uuid" to "$id-s2", "cwd" to root, "timestamp" to ts(7),
                "attachment" to mapOf("type" to "session_context", "context" to mapOf("userEmail" to aliceEmail)),
            ),
            common + mapOf(
                "parentUuid" to "$id-s1", "isSidechain" to true, "agentId" to "a1", "type" to "assistant", "uuid" to "$id-s3", "cwd" to src, "timestamp" to ts(8),
                "message" to mapOf("role" to "assistant", "content" to listOf(mapOf("type" to "text", "text" to "Trovati 3 file"))),
            ),
        )
        Files.writeString(aux.resolve("subagents").resolve("agent-a1.jsonl"), subagentLines.joinToString("") { gson.toJson(it) + "\n" })

        val backups = Files.createDirectories(home.resolve("file-history").resolve(id))
        Files.writeString(backups.resolve("main@v1"), "fun main() {}\n")
        Files.writeString(backups.resolve("main@v2"), "fun main() { println(\"ciao\") }\n")

        return SessionScanner.listSessions(home).single { it.id == id }
    }

    private fun linesOf(transcript: Path): List<JsonObject> =
        Files.readAllLines(transcript).filter { it.isNotBlank() }.map { JsonParser.parseString(it).asJsonObject }

    private fun transcriptOn(pc: Pc, folder: String, id: String): Path =
        pc.home.resolve("projects").resolve(ClaudePaths.encodeProjectPath(folder)).resolve("$id.jsonl")

    private fun subagentOn(pc: Pc, folder: String, id: String): Path =
        transcriptOn(pc, folder, id).resolveSibling(id).resolve("subagents").resolve("agent-a1.jsonl")

    private fun deleteTree(dir: Path) {
        Files.walk(dir).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
    }

    /**
     * Every spot of [home] that still contains [needle] (case-insensitive) *outside* the
     * conversation. The conversation is a line's `message` and `toolUseResult`, and the tool results
     * kept in the auxiliary folder: the plugin never rewrites what the user and Claude said.
     */
    private fun tracesOutsideConversation(home: Path, needle: String): List<String> {
        val found = ArrayList<String>()
        Files.walk(home).use { paths ->
            paths.filter { it.isRegularFile() }.forEach { file ->
                val relative = home.relativize(file).toString()
                if (relative.replace('\\', '/').contains("/tool-results/")) return@forEach
                val text = Files.readString(file)
                if (file.name.endsWith(".jsonl")) {
                    text.lines().forEachIndexed { index, line ->
                        if (line.isBlank()) return@forEachIndexed
                        val obj = JsonParser.parseString(line).asJsonObject
                        obj.remove("message")
                        obj.remove("toolUseResult")
                        if (obj.toString().contains(needle, ignoreCase = true)) found += "$relative:${index + 1}"
                    }
                } else if (text.contains(needle, ignoreCase = true)) {
                    found += relative
                }
            }
        }
        return found
    }

    private fun assertRecent(instant: Instant, message: String) =
        assertTrue("$message: $instant", Duration.between(instant, Instant.now()).abs() < Duration.ofMinutes(1))

    // --------------------------------------------------------------------------------- tests --

    @Test
    fun `PC_2 lists every session PC_1 exported, attached to bob's folders, passing every check`() {
        val pc1 = Pc("PC_1", "alice")
        val apiRoot = "C:\\Users\\alice\\progetti\\api"
        writePc1Session(pc1.home, "s-demo-1", aliceRoot, "Aggiungi la validazione del form")
        writePc1Session(pc1.home, "s-demo-2", aliceRoot, "/model opus", customTitle = "Refactoring del login")
        writePc1Session(pc1.home, "s-api-1", apiRoot, "Documenta gli endpoint REST", start = aliceStart.plusSeconds(3600))
        val exportTable = SessionScanner.listSessions(pc1.home)
        val pc1Names = exportTable.associate { it.id to it.displayName }

        val archive = tmp.root.toPath().resolve("PC_1-export.zip")
        val exported = SessionArchive.export(exportTable, archive, home = pc1.home)
        assertEquals(3, exported.exportedCount)
        assertEquals(emptyList<String>(), exported.warnings)

        // From here on PC_1 is gone: PC_2 only has the archive, under a name of its own.
        val pc2 = Pc("PC_2", "bob")
        val received = pc2.receive(archive, "sessioni di alice.zip")
        deleteTree(tmp.root.toPath().resolve("PC_1"))
        Files.delete(archive)

        // The import table shows what the export table showed.
        val importTable = SessionArchive.readManifest(received)
        assertEquals(pc1Names, importTable.associate { it.id to it.displayName })
        assertEquals("Refactoring del login", pc1Names["s-demo-2"])

        // One import per project, each attached to bob's own checkout of it.
        val bobDemo = pc2.projectFolder("lavoro", "demo")
        val bobApi = pc2.projectFolder("lavoro", "api")
        val demo = SessionArchive.import(received, sessionIds = setOf("s-demo-1", "s-demo-2"), home = pc2.home, targetProjectPath = bobDemo)
        val api = SessionArchive.import(received, sessionIds = setOf("s-api-1"), home = pc2.home, targetProjectPath = bobApi)

        for (outcome in listOf(demo, api)) {
            assertEquals(emptyList<FailedCheck>(), outcome.failedChecks)
            assertEquals(emptyList<ImportFailure>(), outcome.failures)
            assertTrue(outcome.imported.all { it.action == "new" })
        }

        // What `claude --resume` sees on PC_2, started from each of bob's folders.
        val listed = SessionScanner.listSessions(pc2.home).associateBy { it.id }
        assertEquals(setOf("s-demo-1", "s-demo-2", "s-api-1"), listed.keys)
        for ((id, folder) in listOf("s-demo-1" to bobDemo, "s-demo-2" to bobDemo, "s-api-1" to bobApi)) {
            val session = listed.getValue(id)
            val canonical = ClaudePaths.normalizeProjectPath(folder)
            assertEquals(id, ClaudePaths.encodeProjectPath(canonical), session.projectFolderName)
            assertEquals(id, canonical, session.projectPath)
            assertEquals(id, pc1Names[id], session.displayName)
            assertEquals(id, exportTable.single { it.id == id }.messageCount, session.messageCount)
            assertTrue(id, session.hasAuxData && session.hasFileHistory)
            assertRecent(Instant.parse(session.lastTimestamp), "$id ends at import time")
        }
    }

    @Test
    fun `nothing on PC_2 names alice or her account outside the conversation`() {
        val pc1 = Pc("PC_1", "alice")
        val session = writePc1Session(pc1.home, "s-1", aliceRoot, "Aggiungi un test")
        val archive = tmp.root.toPath().resolve("export.zip")
        SessionArchive.export(listOf(session), archive, home = pc1.home)

        val pc2 = Pc("PC_2", "bob")
        val bobDemo = pc2.projectFolder("lavoro", "demo")
        SessionArchive.import(pc2.receive(archive, "a.zip"), home = pc2.home, targetProjectPath = bobDemo)

        assertEquals(emptyList<String>(), tracesOutsideConversation(pc2.home, "alice"))
        // Her account email and git name are not even in the conversation: they must be gone entirely.
        Files.walk(pc2.home).use { paths ->
            paths.filter { it.isRegularFile() }.forEach { file ->
                val text = Files.readString(file)
                assertFalse("$file", text.contains(aliceEmail) || text.contains(aliceGitName))
            }
        }
        // The conversation itself is exactly what alice and Claude said, her own path included.
        val text = Files.readString(transcriptOn(pc2, bobDemo, "s-1"))
        assertTrue(text.contains("Leggi anche C:\\\\Users\\\\alice\\\\note.txt"))
        assertTrue(text.contains("\"Aggiungi un test\""))
    }

    @Test
    fun `the archive leaving PC_1 carries no account identity, only the paths needed to remap`() {
        val pc1 = Pc("PC_1", "alice")
        val session = writePc1Session(pc1.home, "s-1", aliceRoot, "Aggiungi un test")
        val archive = tmp.root.toPath().resolve("export.zip")
        val outcome = SessionArchive.export(listOf(session), archive, home = pc1.home)
        assertEquals(2, outcome.redactedIdentityLines) // main transcript and subagent
        assertEquals(1, outcome.redactedScratchpadPaths)

        ZipFile(archive.toFile()).use { zip ->
            for (entry in zip.entries()) {
                val text = zip.getInputStream(entry).bufferedReader().readText()
                assertFalse(entry.name, text.contains(aliceEmail))
                assertFalse(entry.name, text.contains(aliceGitName))
                assertFalse(entry.name, text.contains("AppData\\\\Local\\\\Temp"))
            }
        }
        val manifest = SessionArchive.readArchiveManifest(archive)
        assertEquals(null, manifest.sourceHome)
        // The source folder is what lets PC_2 tell "below the project" from "elsewhere".
        assertEquals(aliceRoot, manifest.sessions.single().projectPath)
    }

    @Test
    fun `a subagent run follows the main transcript to bob's folder, id and time`() {
        val pc1 = Pc("PC_1", "alice")
        val session = writePc1Session(pc1.home, "s-1", aliceRoot, "Aggiungi un test")
        val archive = tmp.root.toPath().resolve("export.zip")
        SessionArchive.export(listOf(session), archive, home = pc1.home)

        val pc2 = Pc("PC_2", "bob")
        val bobDemo = pc2.projectFolder("lavoro", "demo")
        val canonical = ClaudePaths.normalizeProjectPath(bobDemo)
        val received = pc2.receive(archive, "a.zip")
        SessionArchive.import(received, home = pc2.home, targetProjectPath = bobDemo)
        // A second import of the same archive is a duplicate with a new id: the subagent follows it.
        val duplicate = SessionArchive.import(received, home = pc2.home, targetProjectPath = bobDemo).imported.single()
        assertEquals("duplicated", duplicate.action)

        for (id in listOf("s-1", duplicate.writtenId)) {
            val main = linesOf(transcriptOn(pc2, bobDemo, id))
            val sub = linesOf(subagentOn(pc2, bobDemo, id))
            val sep = if (ClaudePaths.isWindowsStyle(canonical)) "\\" else "/"
            assertEquals(id, listOf(canonical, canonical, "$canonical${sep}src"), sub.map { it.get("cwd").asString })
            assertEquals(id, setOf(id), sub.map { it.get("sessionId").asString }.toSet())
            // The same delta as the main transcript: the run sits between its parent's messages.
            val mainTimes = main.mapNotNull { it.get("timestamp")?.asString }.map(Instant::parse)
            val subTimes = sub.map { Instant.parse(it.get("timestamp").asString) }
            assertEquals(Duration.ofSeconds(7), Duration.between(mainTimes.first(), subTimes.first()))
            assertTrue(subTimes.all { it < mainTimes.last() })
            assertFalse(sub.any { it.get("agentId").asString != "a1" })
        }
        // The subagent's metadata and the tool results are copied as they were.
        val aux = transcriptOn(pc2, bobDemo, "s-1").resolveSibling("s-1")
        assertEquals("""{"agentType":"Explore","description":"Explore the project"}""", Files.readString(aux.resolve("subagents/agent-a1.meta.json")))
        assertEquals("Listing of $aliceRoot\n", Files.readString(aux.resolve("tool-results/toolu_1.txt")))
    }

    @Test
    fun `checkpoints and rewind work on PC_2, pointing at bob's files`() {
        val pc1 = Pc("PC_1", "alice")
        val session = writePc1Session(pc1.home, "s-1", aliceRoot, "Aggiungi un test")
        val archive = tmp.root.toPath().resolve("export.zip")
        SessionArchive.export(listOf(session), archive, home = pc1.home)

        val pc2 = Pc("PC_2", "bob")
        val bobDemo = pc2.projectFolder("lavoro", "demo")
        val canonical = ClaudePaths.normalizeProjectPath(bobDemo)
        val sep = if (ClaudePaths.isWindowsStyle(canonical)) "\\" else "/"
        val outcome = SessionArchive.import(pc2.receive(archive, "a.zip"), home = pc2.home, targetProjectPath = bobDemo)
        assertEquals(2, outcome.imported.single().fileHistoryFiles)
        assertFalse(outcome.failedChecks.any { it.number == 13 })

        val backups = pc2.home.resolve("file-history").resolve("s-1")
        assertEquals("fun main() {}\n", Files.readString(backups.resolve("main@v1")))
        assertEquals("fun main() { println(\"ciao\") }\n", Files.readString(backups.resolve("main@v2")))

        val lines = linesOf(transcriptOn(pc2, bobDemo, "s-1"))
        val tracked = lines.single { it.get("type").asString == "file-history-snapshot" }
            .getAsJsonObject("snapshot").getAsJsonObject("trackedFileBackups")
        val bobMain = "$canonical${sep}src${sep}Main.kt"
        assertEquals(setOf(bobMain), tracked.keySet())
        assertEquals("$canonical${sep}src", tracked.getAsJsonObject(bobMain).get("realParentDir").asString)
        val delta = lines.single { it.get("type").asString == "file-history-delta" }.getAsJsonObject("backup")
        assertEquals(bobMain, delta.get("trackingPath").asString)
        assertEquals("$canonical${sep}src", delta.get("realParentDir").asString)
    }

    @Test
    fun `sessions are dated at PC_2's import time, with their spacing intact`() {
        val pc1 = Pc("PC_1", "alice")
        val session = writePc1Session(pc1.home, "s-1", aliceRoot, "Aggiungi un test")
        val archive = tmp.root.toPath().resolve("export.zip")
        SessionArchive.export(listOf(session), archive, home = pc1.home)

        val pc2 = Pc("PC_2", "bob")
        val bobDemo = pc2.projectFolder("lavoro", "demo")
        SessionArchive.import(pc2.receive(archive, "a.zip"), home = pc2.home, targetProjectPath = bobDemo)

        val lines = linesOf(transcriptOn(pc2, bobDemo, "s-1"))
        val times = lines.mapNotNull { it.get("timestamp")?.asString }.map(Instant::parse)
        assertRecent(times.last(), "the last message lands at import time")
        assertEquals(Duration.ofSeconds(12), Duration.between(times.first(), times.last()))
        // The cost line's epoch moved by the same delta: it still marks the first message.
        val startTime = lines.single { it.get("type").asString == "cost-state" }.get("startTime").asLong
        assertEquals(times.first(), Instant.ofEpochMilli(startTime))
        // Every file Claude Code reads for the session is dated now on PC_2, not in 2024.
        val written = Files.getLastModifiedTime(transcriptOn(pc2, bobDemo, "s-1")).toInstant()
        assertRecent(written, "the transcript file is written at import time")
    }

    @Test
    fun `a PC_2 that never ran Claude Code gets its home created by the import`() {
        val pc1 = Pc("PC_1", "alice")
        val session = writePc1Session(pc1.home, "s-1", aliceRoot, "Aggiungi un test")
        val archive = tmp.root.toPath().resolve("export.zip")
        SessionArchive.export(listOf(session), archive, home = pc1.home)

        val pc2 = Pc("PC_2", "bob")
        assertFalse(Files.exists(pc2.home))
        val bobDemo = pc2.projectFolder("lavoro", "demo")
        val outcome = SessionArchive.import(pc2.receive(archive, "a.zip"), home = pc2.home, targetProjectPath = bobDemo)

        assertEquals(emptyList<FailedCheck>(), outcome.failedChecks)
        assertEquals(listOf("s-1"), SessionScanner.listSessions(pc2.home).map { it.id })
    }

    @Test
    fun `bob's own sessions are untouched and the imported ones are listed first`() {
        val pc1 = Pc("PC_1", "alice")
        val session = writePc1Session(pc1.home, "s-alice", aliceRoot, "Aggiungi un test")
        val archive = tmp.root.toPath().resolve("export.zip")
        SessionArchive.export(listOf(session), archive, home = pc1.home)

        // Bob has worked on the same project yesterday, on his own machine.
        val pc2 = Pc("PC_2", "bob")
        val bobDemo = pc2.projectFolder("lavoro", "demo")
        val canonical = ClaudePaths.normalizeProjectPath(bobDemo)
        val bobProjectDir = Files.createDirectories(pc2.home.resolve("projects").resolve(ClaudePaths.encodeProjectPath(canonical)))
        val yesterday = MILLIS.format(Instant.now().minus(Duration.ofDays(1)))
        val bobTranscript = bobProjectDir.resolve("s-bob.jsonl")
        Files.writeString(
            bobTranscript,
            gson.toJson(mapOf("type" to "user", "message" to mapOf("role" to "user", "content" to "Ciao"), "cwd" to canonical, "sessionId" to "s-bob", "timestamp" to yesterday, "version" to "2.1.275")) + "\n",
        )
        val bobBytes = Files.readAllBytes(bobTranscript)

        val outcome = SessionArchive.import(pc2.receive(archive, "a.zip"), home = pc2.home, targetProjectPath = bobDemo)
        assertEquals(emptyList<FailedCheck>(), outcome.failedChecks)
        assertEquals("new", outcome.imported.single().action)

        assertTrue(bobBytes.contentEquals(Files.readAllBytes(bobTranscript)))
        assertEquals(listOf("s-alice", "s-bob"), SessionScanner.listSessions(pc2.home).map { it.id })
        assertTrue(SessionScanner.listSessions(pc2.home).all { it.projectFolderName == bobProjectDir.name })
    }

    @Test
    fun `importing the same archive twice on PC_2 keeps both copies, each with its own id`() {
        val pc1 = Pc("PC_1", "alice")
        val session = writePc1Session(pc1.home, "s-1", aliceRoot, "Aggiungi un test")
        val archive = tmp.root.toPath().resolve("export.zip")
        SessionArchive.export(listOf(session), archive, home = pc1.home)

        val pc2 = Pc("PC_2", "bob")
        val bobDemo = pc2.projectFolder("lavoro", "demo")
        val received = pc2.receive(archive, "a.zip")
        SessionArchive.import(received, home = pc2.home, targetProjectPath = bobDemo)
        val second = SessionArchive.import(received, home = pc2.home, targetProjectPath = bobDemo)

        assertEquals(emptyList<FailedCheck>(), second.failedChecks)
        val copy = second.imported.single()
        assertNotEquals("s-1", copy.writtenId)
        val listed = SessionScanner.listSessions(pc2.home)
        assertEquals(setOf("s-1", copy.writtenId), listed.map { it.id }.toSet())
        assertEquals(1, listed.map { it.displayName }.toSet().size)
        assertTrue(Files.isDirectory(pc2.home.resolve("file-history").resolve(copy.writtenId)))

        // SKIP and REPLACE leave exactly one copy of the original id.
        assertEquals(listOf("s-1"), SessionArchive.import(received, home = pc2.home, conflictPolicy = ConflictPolicy.SKIP, targetProjectPath = bobDemo).skipped)
        val replaced = SessionArchive.import(received, home = pc2.home, conflictPolicy = ConflictPolicy.REPLACE, targetProjectPath = bobDemo)
        assertEquals("replaced", replaced.imported.single().action)
        assertEquals(emptyList<FailedCheck>(), replaced.failedChecks)
        assertEquals(2, SessionScanner.listSessions(pc2.home).size)
    }

    @Test
    fun `PC_1 and PC_2 on different operating systems`() {
        // Stand-in paths of the other operating system: they cannot exist on the machine running the
        // test, so check #8 ("the folder exists on this machine") is the only one left out here.
        data class Case(val aliceRoot: String, val bobRoot: String, val bobSrc: String)
        val cases = listOf(
            Case("/home/alice/dev/demo", "C:/Users/bob/lavoro/demo", "C:\\Users\\bob\\lavoro\\demo\\src"),
            Case("C:\\Users\\alice\\progetti\\demo", "/Users/bob/lavoro/demo", "/Users/bob/lavoro/demo/src"),
            Case("/Users/alice/dev/demo", "/home/bob/lavoro/demo/", "/home/bob/lavoro/demo/src"),
        )
        cases.forEachIndexed { index, case ->
            val pc1 = Pc("PC_1-$index", "alice")
            val session = writePc1Session(pc1.home, "s-$index", case.aliceRoot, "Aggiungi un test")
            val archive = tmp.root.toPath().resolve("export-$index.zip")
            SessionArchive.export(listOf(session), archive, home = pc1.home)

            val pc2 = Pc("PC_2-$index", "bob")
            val outcome = SessionArchive.import(pc2.receive(archive, "a.zip"), home = pc2.home, targetProjectPath = case.bobRoot)
            assertEquals("$case", emptyList<Int>(), outcome.failedChecks.map { it.number }.filter { it != 8 })

            val canonical = ClaudePaths.normalizeProjectPath(case.bobRoot)
            val lines = linesOf(transcriptOn(pc2, canonical, "s-$index"))
            val cwds = lines.mapNotNull { it.get("cwd")?.asString }.toSet()
            assertEquals("$case", setOf(canonical, case.bobSrc), cwds)
            val sub = linesOf(subagentOn(pc2, canonical, "s-$index")).map { it.get("cwd").asString }.toSet()
            assertEquals("$case", setOf(canonical, case.bobSrc), sub)
            assertEquals("$case", emptyList<String>(), tracesOutsideConversation(pc2.home, "alice"))
            assertEquals("$case", canonical, SessionScanner.listSessions(pc2.home).single().projectPath)
        }
    }

    @Test
    fun `user names and folders with spaces and accents on both machines`() {
        val pc1 = Pc("PC_1", "Zoë Müller")
        val zoeRoot = "C:\\Users\\Zoë Müller\\Mes Projets\\démo"
        val session = writePc1Session(pc1.home, "s-1", zoeRoot, "Ajoute un test", user = "Zoë Müller")
        // Claude Code's own folder naming turns every non-ASCII letter into '-' as well.
        assertEquals("C--Users-Zo--M-ller-Mes-Projets-d-mo", session.projectFolderName)
        val archive = tmp.root.toPath().resolve("export.zip")
        SessionArchive.export(listOf(session), archive, home = pc1.home)

        val pc2 = Pc("PC_2", "Bob O'Brien")
        val bobRoot = pc2.projectFolder("Progetti Lavoro", "démo")
        val outcome = SessionArchive.import(pc2.receive(archive, "sessions de Zoë.zip"), home = pc2.home, targetProjectPath = bobRoot)

        assertEquals(emptyList<FailedCheck>(), outcome.failedChecks)
        val listed = SessionScanner.listSessions(pc2.home).single()
        assertEquals(ClaudePaths.normalizeProjectPath(bobRoot), listed.projectPath)
        assertEquals("Ajoute un test", listed.displayName)
        assertEquals(emptyList<String>(), tracesOutsideConversation(pc2.home, "Müller"))
    }

    @Test
    fun `without attaching, the session keeps alice's folder and the diagnosis says why it will not show`() {
        val pc1 = Pc("PC_1", "alice")
        val session = writePc1Session(pc1.home, "s-1", aliceRoot, "Aggiungi un test")
        val archive = tmp.root.toPath().resolve("export.zip")
        SessionArchive.export(listOf(session), archive, home = pc1.home)

        val pc2 = Pc("PC_2", "bob")
        val outcome = SessionArchive.import(pc2.receive(archive, "a.zip"), home = pc2.home, targetProjectPath = null)

        // Stored under alice's folder name: only `claude --resume` started from "C:\Users\alice\..."
        // would list it, and that folder does not exist on PC_2 - which is exactly what #8 reports.
        assertEquals(listOf(8), outcome.failedChecks.map { it.number })
        val listed = SessionScanner.listSessions(pc2.home).single()
        assertEquals(ClaudePaths.encodeProjectPath(aliceRoot), listed.projectFolderName)
        assertEquals(aliceRoot, listed.projectPath)
    }

    @Test
    fun `PC_2 resolves its home the way the IDE does, through the claude home property`() {
        val pc1 = Pc("PC_1", "alice")
        val session = writePc1Session(pc1.home, "s-1", aliceRoot, "Aggiungi un test")
        val archive = tmp.root.toPath().resolve("export.zip")
        SessionArchive.export(listOf(session), archive, home = pc1.home)

        val pc2 = Pc("PC_2", "bob")
        val bobDemo = pc2.projectFolder("lavoro", "demo")
        val received = pc2.receive(archive, "a.zip")
        val previous = System.getProperty(ClaudePaths.SYSTEM_PROPERTY)
        System.setProperty(ClaudePaths.SYSTEM_PROPERTY, pc2.home.toString())
        try {
            val outcome = SessionArchive.import(received, targetProjectPath = bobDemo)
            assertEquals(emptyList<FailedCheck>(), outcome.failedChecks)
            assertEquals(listOf("s-1"), SessionScanner.listSessions().map { it.id })
        } finally {
            if (previous == null) System.clearProperty(ClaudePaths.SYSTEM_PROPERTY) else System.setProperty(ClaudePaths.SYSTEM_PROPERTY, previous)
        }
        assertTrue(Files.exists(transcriptOn(pc2, bobDemo, "s-1")))
    }

    @Test
    fun `a round trip PC_1 to PC_2 and back brings the session home to alice's folder`() {
        val pc1 = Pc("PC_1", "alice")
        val original = writePc1Session(pc1.home, "s-1", aliceRoot, "Aggiungi un test")
        val toBob = tmp.root.toPath().resolve("to-bob.zip")
        SessionArchive.export(listOf(original), toBob, home = pc1.home)

        val pc2 = Pc("PC_2", "bob")
        val bobDemo = pc2.projectFolder("lavoro", "demo")
        SessionArchive.import(pc2.receive(toBob, "a.zip"), home = pc2.home, targetProjectPath = bobDemo)

        // Bob exports what he imported; alice imports it back where her project still lives.
        val toAlice = tmp.root.toPath().resolve("to-alice.zip")
        SessionArchive.export(SessionScanner.listSessions(pc2.home), toAlice, home = pc2.home)
        val back = SessionArchive.import(toAlice, home = pc1.home, targetProjectPath = aliceRoot)

        // Her original is still there, so the default policy keeps both.
        val copy = back.imported.single()
        assertEquals("duplicated", copy.action)
        assertEquals(listOf(8), back.failedChecks.map { it.number }.distinct()) // alice's folder is a stand-in path
        val lines = linesOf(transcriptOn(pc1, aliceRoot, copy.writtenId))
        assertEquals(setOf(aliceRoot, "$aliceRoot\\src"), lines.mapNotNull { it.get("cwd")?.asString }.toSet())
        assertEquals(emptyList<String>(), tracesOutsideConversation(pc1.home, "bob"))
        assertEquals(setOf("s-1", copy.writtenId), SessionScanner.listSessions(pc1.home).map { it.id }.toSet())
    }
}
