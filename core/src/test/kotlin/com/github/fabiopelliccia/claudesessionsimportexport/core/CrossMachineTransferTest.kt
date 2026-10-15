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
     * Exports [sessions] of [pc] the way its tools would: [spelledHome] is what the account's home is
     * called in the paths its sessions hold - alice's `C:\Users\alice` does not exist on the machine
     * running the test, so the real `user.home` would anonymize nothing of it.
     */
    private fun exportFrom(
        pc: Pc,
        sessions: List<SessionInfo>,
        archive: Path,
        spelledHome: String = "C:\\Users\\${pc.user}",
    ): ExportOutcome = SessionArchive.export(sessions, archive, home = pc.home, identity = SourceIdentity(spelledHome))

    /**
     * [user]'s Windows home and a file below it, written the way each tool spells it: plain, with
     * forward slashes, as a path inside a JSON document (`\\`, and `\\\\` one level deeper), as a Git
     * Bash, WSL and Cygwin path, in its 8.3 form, as a Claude Code folder name and as a file URI.
     */
    private fun homeForms(user: String): List<String> = listOf(
        "C:\\Users\\$user\\docs\\a.txt",
        "C:/Users/$user/docs/a.txt",
        "C:\\\\Users\\\\$user\\\\docs\\\\a.txt",
        "C:\\\\\\\\Users\\\\\\\\$user\\\\\\\\docs\\\\\\\\a.txt",
        "/c/Users/$user/docs/a.txt",
        "/mnt/c/Users/$user/docs/a.txt",
        "C:\\Users\\${user.uppercase().take(6)}~1\\AppData\\Local\\Temp\\x.txt",
        "C--Users-$user-docs-a-txt",
        "file:///C:/Users/$user/docs/a.txt",
    )

    /**
     * Writes one PC_1 session below [root], the way Claude Code leaves it after a session that
     * edited a file and ran a subagent. Returns the session as PC_1's export table lists it.
     *
     * With [forms] the user's home is planted in every spelling of [homeForms] (Windows accounts
     * only) in the places a path can hide: a message, a `toolUseResult`, a `rendered` block, an
     * `edited_text_file` attachment, a `trackedFileBackups` key, tool results and their metadata, a
     * subagent transcript (one of its lines is not valid JSON) and a file history backup. The user's
     * bare name is there too, outside any path: "sono alice" and the branch `users/alice/fix`.
     */
    private fun writePc1Session(
        home: Path,
        id: String,
        root: String,
        prompt: String,
        start: Instant = aliceStart,
        customTitle: String? = null,
        user: String = "alice",
        forms: Boolean = false,
    ): SessionInfo {
        val formsText = homeForms(user).joinToString(" | ")
        val userDocs = "C:\\Users\\$user\\docs"
        val sep = if (ClaudePaths.isWindowsStyle(root)) "\\" else "/"
        val src = "$root${sep}src"
        val mainFile = "$src${sep}Main.kt"
        val tempDir = if (sep == "\\") "C:\\Users\\$user\\AppData\\Local\\Temp" else "/tmp/claude-$user"
        // A file of the user's own home the user names in the conversation, in the way of the account's OS.
        val typedPath = when {
            sep == "\\" -> "C:\\Users\\$user\\note.txt"
            root.startsWith("/Users/") -> "/Users/$user/note.txt"
            else -> "/home/$user/note.txt"
        }
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
                "rendered" to listOf(mapOf("content" to "<system-reminder>\nThe user's email address is $aliceEmail.\nGit user: $aliceGitName\nCurrent branch: develop\n</system-reminder>")) +
                    if (forms) listOf(mapOf("content" to "<system-reminder>\nCartella: $formsText\n</system-reminder>")) else emptyList(),
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
                    ) + if (forms) {
                        // A file of the user's home that is not below the project: a key that is a path.
                        mapOf("$userDocs\\a.txt" to mapOf("backupFileName" to "docs@v1", "version" to 1, "backupTime" to ts(5), "realParentDir" to userDocs))
                    } else {
                        emptyMap()
                    },
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
                "message" to mapOf("role" to "user", "content" to "Leggi anche $typedPath"),
            ),
            if (forms) {
                common + mapOf(
                    "parentUuid" to "$id-u2", "isSidechain" to false, "type" to "user", "uuid" to "$id-u3", "cwd" to root, "timestamp" to ts(11),
                    "message" to mapOf("role" to "user", "content" to "Forme: $formsText"),
                )
            } else {
                null
            },
            if (forms) {
                common + mapOf(
                    "type" to "attachment", "uuid" to "$id-at3", "cwd" to root, "timestamp" to ts(11),
                    "attachment" to mapOf("type" to "edited_text_file", "filename" to "$userDocs\\a.txt", "snippet" to "1\t$formsText"),
                )
            } else {
                null
            },
            common + mapOf(
                "parentUuid" to "$id-u2", "isSidechain" to false, "type" to "assistant", "uuid" to "$id-a2", "cwd" to root, "timestamp" to ts(12),
                "message" to mapOf("role" to "assistant", "content" to listOf(mapOf("type" to "text", "text" to if (forms) "Fatto, sono $user." else "Fatto."))),
                "toolUseResult" to mapOf("filePath" to mainFile) + if (forms) mapOf("stdout" to formsText) else emptyMap(),
            ) + if (forms) mapOf("gitBranch" to "users/$user/fix") else emptyMap(),
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

        if (forms) {
            Files.writeString(aux.resolve("tool-results").resolve("toolu_2.txt"), "$formsText\n")
            // Raw JSON text: the backslashes of a path are doubled, one level deeper than in a decoded string.
            Files.writeString(aux.resolve("tool-results").resolve("toolu_2.meta.json"), """{"path":"C:\\Users\\$user\\docs\\a.txt","tool":"Read"}""")
            val subagent = aux.resolve("subagents").resolve("agent-a1.jsonl")
            val formsLine = gson.toJson(
                common + mapOf(
                    "parentUuid" to "$id-s3", "isSidechain" to true, "agentId" to "a1", "type" to "assistant", "uuid" to "$id-s4", "cwd" to root, "timestamp" to ts(9),
                    "message" to mapOf("role" to "assistant", "content" to listOf(mapOf("type" to "text", "text" to "Forme: $formsText"))),
                ),
            )
            // A line cut short is not JSON: its paths are found in the raw text, one level deeper again.
            val brokenLine = """{"type":"user","text":"C:\\Users\\$user\\docs\\a.txt and C:\\\\Users\\\\$user\\\\docs and /c/Users/$user/docs"""
            Files.writeString(subagent, Files.readString(subagent) + formsLine + "\n" + brokenLine + "\n")
            Files.writeString(backups.resolve("docs@v1"), "// copia di $userDocs\\a.txt\n")
            Files.writeString(backups.resolve("main@v3"), "// $formsText\n")
        }

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
     * Every file of [home] - by name or by content - that still contains [needle] (case-insensitive).
     * The conversation is no exception: the export anonymizes it too, so nothing of the other
     * machine's user is left anywhere, not even where the user and Claude talked about a path.
     */
    private fun traces(home: Path, needle: String): List<String> {
        val found = ArrayList<String>()
        Files.walk(home).use { paths ->
            paths.filter { it.isRegularFile() }.forEach { file ->
                val relative = home.relativize(file).toString()
                if (relative.contains(needle, ignoreCase = true) || Files.readString(file).contains(needle, ignoreCase = true)) found += relative
            }
        }
        return found
    }

    /** No anonymization token is left anywhere under [home], whatever the prefix. */
    private fun assertNoTokens(home: Path) {
        assertEquals(emptyList<String>(), traces(home, "{{SP"))
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
        val exported = exportFrom(pc1, exportTable, archive)
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
    fun `nothing on PC_2 names alice or her account, the conversation included`() {
        val pc1 = Pc("PC_1", "alice")
        val session = writePc1Session(pc1.home, "s-1", aliceRoot, "Aggiungi un test")
        val archive = tmp.root.toPath().resolve("export.zip")
        exportFrom(pc1, listOf(session), archive)

        val pc2 = Pc("PC_2", "bob")
        val bobDemo = pc2.projectFolder("lavoro", "demo")
        SessionArchive.import(pc2.receive(archive, "a.zip"), home = pc2.home, targetProjectPath = bobDemo, userHome = pc2.root.toString())

        assertEquals(emptyList<String>(), traces(pc2.home, "alice"))
        assertNoTokens(pc2.home)
        // Her account email and git name were never in the archive.
        assertEquals(emptyList<String>(), traces(pc2.home, aliceEmail))
        assertEquals(emptyList<String>(), traces(pc2.home, aliceGitName))
        // The conversation is anonymized with the rest: the path alice typed is bob's home on PC_2.
        val text = Files.readString(transcriptOn(pc2, bobDemo, "s-1"))
        assertTrue(text.contains("Leggi anche ${pc2.root.toString().replace("\\", "\\\\")}\\\\note.txt"))
        assertTrue(text.contains("\"Aggiungi un test\""))
    }

    @Test
    fun `the archive leaving PC_1 carries no account identity, only the paths needed to remap`() {
        val pc1 = Pc("PC_1", "alice")
        val session = writePc1Session(pc1.home, "s-1", aliceRoot, "Aggiungi un test")
        val archive = tmp.root.toPath().resolve("export.zip")
        val outcome = exportFrom(pc1, listOf(session), archive)
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
        // The source folder is a token now; what lets PC_2 tell "below the project" from "elsewhere" is
        // that every path under it carries the same token. The project keeps its name for the tables.
        val listed = manifest.sessions.single()
        assertEquals("{{SP:ROOT:win}}", listed.projectPath)
        assertEquals("demo", listed.projectName)
    }

    @Test
    fun `a subagent run follows the main transcript to bob's folder, id and time`() {
        val pc1 = Pc("PC_1", "alice")
        val session = writePc1Session(pc1.home, "s-1", aliceRoot, "Aggiungi un test")
        val archive = tmp.root.toPath().resolve("export.zip")
        exportFrom(pc1, listOf(session), archive)

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
        assertEquals("Listing of $canonical\n", Files.readString(aux.resolve("tool-results/toolu_1.txt")))
    }

    @Test
    fun `checkpoints and rewind work on PC_2, pointing at bob's files`() {
        val pc1 = Pc("PC_1", "alice")
        val session = writePc1Session(pc1.home, "s-1", aliceRoot, "Aggiungi un test")
        val archive = tmp.root.toPath().resolve("export.zip")
        exportFrom(pc1, listOf(session), archive)

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
        exportFrom(pc1, listOf(session), archive)

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
        exportFrom(pc1, listOf(session), archive)

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
        exportFrom(pc1, listOf(session), archive)

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
        exportFrom(pc1, listOf(session), archive)

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
        // [typed] is the path alice typed in the conversation, as it reads on PC_2.
        data class Case(val aliceHome: String, val aliceRoot: String, val bobHome: String, val bobRoot: String, val bobSrc: String, val typed: String)
        val cases = listOf(
            Case("/home/alice", "/home/alice/dev/demo", "C:\\Users\\bob", "C:/Users/bob/lavoro/demo", "C:\\Users\\bob\\lavoro\\demo\\src", "C:\\Users\\bob\\note.txt"),
            Case("C:\\Users\\alice", "C:\\Users\\alice\\progetti\\demo", "/Users/bob", "/Users/bob/lavoro/demo", "/Users/bob/lavoro/demo/src", "/Users/bob/note.txt"),
            Case("/Users/alice", "/Users/alice/dev/demo", "/home/bob", "/home/bob/lavoro/demo/", "/home/bob/lavoro/demo/src", "/home/bob/note.txt"),
        )
        cases.forEachIndexed { index, case ->
            val pc1 = Pc("PC_1-$index", "alice")
            val session = writePc1Session(pc1.home, "s-$index", case.aliceRoot, "Aggiungi un test")
            val archive = tmp.root.toPath().resolve("export-$index.zip")
            exportFrom(pc1, listOf(session), archive, spelledHome = case.aliceHome)

            val pc2 = Pc("PC_2-$index", "bob")
            val outcome = SessionArchive.import(pc2.receive(archive, "a.zip"), home = pc2.home, targetProjectPath = case.bobRoot, userHome = case.bobHome)
            assertEquals("$case", emptyList<Int>(), outcome.failedChecks.map { it.number }.filter { it != 8 })

            val canonical = ClaudePaths.normalizeProjectPath(case.bobRoot)
            val lines = linesOf(transcriptOn(pc2, canonical, "s-$index"))
            val cwds = lines.mapNotNull { it.get("cwd")?.asString }.toSet()
            assertEquals("$case", setOf(canonical, case.bobSrc), cwds)
            val sub = linesOf(subagentOn(pc2, canonical, "s-$index")).map { it.get("cwd").asString }.toSet()
            assertEquals("$case", setOf(canonical, case.bobSrc), sub)
            // The path alice typed is bob's home in bob's own spelling: separators included.
            val typed = lines.map { it.getAsJsonObject("message")?.get("content") }.filter { it != null && it.isJsonPrimitive }.map { it!!.asString }
            assertTrue("$case $typed", "Leggi anche ${case.typed}" in typed)
            assertEquals("$case", emptyList<String>(), traces(pc2.home, "alice"))
            assertNoTokens(pc2.home)
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
        exportFrom(pc1, listOf(session), archive)

        val pc2 = Pc("PC_2", "Bob O'Brien")
        val bobRoot = pc2.projectFolder("Progetti Lavoro", "démo")
        val outcome = SessionArchive.import(pc2.receive(archive, "sessions de Zoë.zip"), home = pc2.home, targetProjectPath = bobRoot, userHome = pc2.root.toString())

        assertEquals(emptyList<FailedCheck>(), outcome.failedChecks)
        val listed = SessionScanner.listSessions(pc2.home).single()
        assertEquals(ClaudePaths.normalizeProjectPath(bobRoot), listed.projectPath)
        assertEquals("Ajoute un test", listed.displayName)
        assertEquals(emptyList<String>(), traces(pc2.home, "Müller"))
    }

    @Test
    fun `a format 2 archive cannot be imported without a target folder`() {
        val pc1 = Pc("PC_1", "alice")
        val session = writePc1Session(pc1.home, "s-1", aliceRoot, "Aggiungi un test")
        val archive = tmp.root.toPath().resolve("export.zip")
        exportFrom(pc1, listOf(session), archive)

        val pc2 = Pc("PC_2", "bob")
        val received = pc2.receive(archive, "a.zip")
        for (target in listOf(null, "", "   ")) {
            val failure = try {
                SessionArchive.import(received, home = pc2.home, targetProjectPath = target)
                null
            } catch (e: IllegalArgumentException) {
                e
            }
            assertEquals("target $target", ClaudeSessionsBundle.message("error.targetRequired"), failure?.message)
        }
        // Nothing was written: the refusal comes before the first session.
        assertFalse(Files.exists(pc2.home.resolve("projects")))
    }

    @Test
    fun `PC_2 resolves its home the way the IDE does, through the claude home property`() {
        val pc1 = Pc("PC_1", "alice")
        val session = writePc1Session(pc1.home, "s-1", aliceRoot, "Aggiungi un test")
        val archive = tmp.root.toPath().resolve("export.zip")
        exportFrom(pc1, listOf(session), archive)

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
        exportFrom(pc1, listOf(original), toBob)

        val pc2 = Pc("PC_2", "bob")
        val bobDemo = pc2.projectFolder("lavoro", "demo")
        SessionArchive.import(pc2.receive(toBob, "a.zip"), home = pc2.home, targetProjectPath = bobDemo)

        // Bob exports what he imported; alice imports it back where her project still lives.
        val toAlice = tmp.root.toPath().resolve("to-alice.zip")
        exportFrom(pc2, SessionScanner.listSessions(pc2.home), toAlice, spelledHome = pc2.root.toString())
        val back = SessionArchive.import(toAlice, home = pc1.home, targetProjectPath = aliceRoot)

        // Her original is still there, so the default policy keeps both.
        val copy = back.imported.single()
        assertEquals("duplicated", copy.action)
        assertEquals(listOf(8), back.failedChecks.map { it.number }.distinct()) // alice's folder is a stand-in path
        val lines = linesOf(transcriptOn(pc1, aliceRoot, copy.writtenId))
        assertEquals(setOf(aliceRoot, "$aliceRoot\\src"), lines.mapNotNull { it.get("cwd")?.asString }.toSet())
        assertEquals(emptyList<String>(), traces(pc1.home, "bob"))
        assertEquals(setOf("s-1", copy.writtenId), SessionScanner.listSessions(pc1.home).map { it.id }.toSet())
    }

    // ------------------------------------------------------------------ anonymized export (format 2) --

    /** Every entry of [archive] by name, decompressed. */
    private fun entriesOf(archive: Path): Map<String, ByteArray> = ZipFile(archive.toFile()).use { zip ->
        zip.entries().asSequence().associate { it.name to zip.getInputStream(it).readBytes() }
    }

    private fun textOf(archive: Path, name: String): String =
        String(entriesOf(archive).getValue(name), Charsets.UTF_8)

    /** The project directory of the session [id] of [pc] with the given [root]. */
    private fun projectDirOf(pc: Pc, root: String): Path =
        pc.home.resolve("projects").resolve(ClaudePaths.encodeProjectPath(root)).also { Files.createDirectories(it) }

    @Test
    fun `the archive leaving PC_1 names alice in no path form`() {
        val pc1 = Pc("PC_1", "alice")
        val session = writePc1Session(pc1.home, "s-1", aliceRoot, "Aggiungi un test", forms = true)
        val archive = tmp.root.toPath().resolve("export.zip")
        val outcome = exportFrom(pc1, listOf(session), archive)
        assertEquals(emptyList<String>(), outcome.warnings)

        val forbidden = listOf(
            "C:\\Users\\alice", "C:/Users/alice", "C:\\\\Users\\\\alice", "C:\\\\\\\\Users\\\\\\\\alice",
            "/c/Users/alice", "/mnt/c/Users/alice", "ALICE~1", "C--Users-alice", "Users%5Calice",
            aliceEmail, aliceGitName, "alice.rossi",
        )
        val entries = entriesOf(archive)
        assertEquals(
            setOf(
                "manifest.json", "sessions/s-1/transcript.jsonl",
                "sessions/s-1/aux/subagents/agent-a1.jsonl", "sessions/s-1/aux/subagents/agent-a1.meta.json",
                "sessions/s-1/aux/tool-results/toolu_1.txt", "sessions/s-1/aux/tool-results/toolu_2.txt", "sessions/s-1/aux/tool-results/toolu_2.meta.json",
                "sessions/s-1/file-history/main@v1", "sessions/s-1/file-history/main@v2", "sessions/s-1/file-history/main@v3", "sessions/s-1/file-history/docs@v1",
            ),
            entries.keys,
        )
        for ((name, bytes) in entries) {
            val text = String(bytes, Charsets.UTF_8)
            for (form in forbidden) {
                assertFalse("$name holds $form", name.contains(form, ignoreCase = true))
                assertFalse("$name holds $form", text.contains(form, ignoreCase = true))
            }
        }

        // Every spelling that was planted was found, and each by the anchor that owns it.
        assertEquals(
            setOf("enc", "esc1", "esc2", "fwd", "msys", "short", "win", "wsl"),
            outcome.replacedByStyle.keys,
        )
        assertEquals(setOf("HOME", "ROOT"), outcome.replacedByKind.keys)
        // What stays is the bare name outside any path: a sentence and a branch (twice: transcript and manifest).
        assertEquals(3, outcome.residualUserNameHits)
        assertEquals(listOf("sessions/s-1/transcript.jsonl", "manifest.json"), outcome.residualEntries)
        assertEquals(emptyList<String>(), outcome.binaryEntriesWithHits)
        assertEquals(0, outcome.keyCollisions)

        // The manifest: tokens for the paths, the project's own name, no source home.
        val manifest = SessionArchive.readArchiveManifest(archive)
        assertEquals(2, manifest.formatVersion)
        assertEquals(null, manifest.sourceHome)
        assertEquals(SessionArchive.AnonymizationInfo(1, "SP", listOf("HOME", "ROOT"), "windows"), manifest.anonymization)
        val listed = manifest.sessions.single()
        assertEquals("{{SP:ROOT:win}}", listed.projectPath)
        assertEquals("{{SP:ROOT:enc}}", listed.projectFolderName)
        assertEquals("demo", listed.projectName)
        assertEquals("Aggiungi un test", listed.summary)
        assertEquals("users/alice/fix", listed.gitBranch)
    }

    // ------------------------------------------------------- leak forms found by the engine review --

    /**
     * Plants [forms] in the places a path can hide, on top of what [writePc1Session] wrote: a message of the
     * transcript (decoded text), the keys of a `trackedFileBackups`, a text file of the auxiliary folder and a
     * file history backup (both raw text).
     */
    private fun plantLeakForms(pc: Pc, root: String, id: String, forms: List<String>) {
        val transcript = transcriptOn(pc, root, id)
        val message = mapOf("type" to "user", "message" to mapOf("role" to "user", "content" to "Altre forme: " + forms.joinToString(" | ")), "cwd" to root, "sessionId" to id)
        val keyed = forms.withIndex().associate { (index, form) -> form to mapOf("backupFileName" to "leak@v${index + 1}", "version" to index + 1) }
        val snapshot = mapOf("type" to "file-history-snapshot", "messageId" to "$id-u1", "snapshot" to mapOf("messageId" to "$id-u1", "trackedFileBackups" to keyed))
        Files.writeString(transcript, Files.readString(transcript) + gson.toJson(message) + "\n" + gson.toJson(snapshot) + "\n")
        val aux = transcript.resolveSibling(id).resolve("tool-results")
        Files.writeString(aux.resolve("toolu_leak.txt"), forms.joinToString("\n") + "\n")
        val backups = pc.home.resolve("file-history").resolve(id)
        // The checkpoints the snapshot names exist; the first one also holds the forms as raw text.
        forms.indices.forEach { index ->
            Files.writeString(backups.resolve("leak@v${index + 1}"), if (index == 0) "// " + forms.joinToString(" | ") + "\n" else "// copia\n")
        }
    }

    /** Exports the one session of [pc] and asserts that no entry, by name or by content, holds any of [forbidden]. */
    private fun exportAndAssertNoneOf(pc: Pc, spelledHome: String, forbidden: List<String>): Pair<Path, ExportOutcome> {
        val session = SessionScanner.listSessions(pc.home).single()
        val archive = tmp.root.toPath().resolve("export.zip")
        val outcome = exportFrom(pc, listOf(session), archive, spelledHome)
        for ((name, bytes) in entriesOf(archive)) {
            val text = String(bytes, Charsets.UTF_8)
            for (form in forbidden) {
                assertFalse("$name holds $form", name.contains(form, ignoreCase = true))
                assertFalse("$name holds $form", text.contains(form, ignoreCase = true))
            }
        }
        return archive to outcome
    }

    /** What PC_2 gets of such an archive: no token left, no failed check, and nothing of the source user. */
    private fun importAndAssertNoTraceOf(archive: Path, name: String) {
        val pc2 = Pc("PC_2", "bob")
        val bobDemo = pc2.projectFolder("lavoro", "demo")
        val outcome = SessionArchive.import(pc2.receive(archive, "leak.zip"), home = pc2.home, targetProjectPath = bobDemo, userHome = pc2.root.toString())
        assertEquals(emptyList<FailedCheck>(), outcome.failedChecks)
        assertEquals(emptyList<ImportFailure>(), outcome.failures)
        assertNoTokens(pc2.home)
        assertEquals(emptyList<String>(), traces(pc2.home, name))
    }

    @Test
    fun `the archive names alice in none of the forms the engine review found, Git Bash and WSL 8-3, flags, colours, HOMEPATH, UNC and mixed separators`() {
        val pc1 = Pc("PC_1", "alice")
        writePc1Session(pc1.home, "s-1", aliceRoot, "Aggiungi un test")
        val forms = listOf(
            // 8.3 home in the Git Bash, WSL, Cygwin, encoded and eight-backslash spellings (finding 1)
            "/c/Users/ALICE~1/AppData/Local/Temp/claude/x",
            "/mnt/c/Users/ALICE~1/AppData",
            "/cygdrive/c/Users/ALICE~1/AppData",
            "C--Users-ALICE-1-AppData-Local-Temp-x",
            """C:\\\\\\\\Users\\\\\\\\ALICE~1\\\\\\\\x""",
            // glued to a flag, an ANSI colour or a longer path (finding 2)
            "gcc -IC:\\Users\\alice\\inc",
            "\u001b[34mC:\\Users\\alice\\proj\u001b[0m",
            "docker: /host_mnt/c/Users/alice/proj",
            "cc -I/c/Users/alice/include",
            "file%3A%2F%2F%2Fc%3A%2FUsers%2Falice%2Fx",
            // HOMEPATH and the admin share (finding 8)
            "HOMEPATH=\\Users\\alice",
            "\\\\localhost\\c$\\Users\\alice\\x",
            "\\\\?\\UNC\\localhost\\c$\\Users\\alice\\x",
            // mixed and escaped separators (finding 9)
            "C:\\/Users\\/alice\\/x",
            "C:\\\\Users/alice",
            "C://Users//alice",
            "C:\\u005cUsers\\u005calice\\x",
            "C:\\Users/alice\\x",
        )
        plantLeakForms(pc1, aliceRoot, "s-1", forms)
        val (archive, outcome) = exportAndAssertNoneOf(pc1, "C:\\Users\\alice", listOf("alice"))

        assertTrue(outcome.replacedByStyle.toString(), outcome.replacedByStyle.keys.containsAll(listOf("cyg", "enc", "esc3", "msys", "rel", "win", "wsl")))
        assertEquals(0, outcome.residualUserNameHits)
        importAndAssertNoTraceOf(archive, "alice")
    }

    @Test
    fun `the archive names Zoe Muller in none of the forms the engine review found, unicode escapes, partial percent, NFD, shell escapes`() {
        val pc1 = Pc("PC_1", "Zoë Müller")
        val zoeRoot = "C:\\Users\\Zoë Müller\\Mes Projets\\démo"
        writePc1Session(pc1.home, "s-1", zoeRoot, "Ajoute un test", user = "Zoë Müller")
        val forms = listOf(
            // the name as JSON unicode escapes, one level of escaping deeper (finding 3)
            "C:\\\\Users\\\\Zo\\u00eb M\\u00fcller\\\\x",
            "C:\\\\Users\\\\Zo\\u00EB M\\u00FCller\\\\x",
            // partially percent-encoded URIs (finding 4)
            "file:/C:/Users/Zo%C3%AB%20M\u00fcller/x",
            "file:/C:/Users/Zo\u00eb+M\u00fcller/x",
            // decomposed, as macOS writes it (finding 5)
            "C:\\Users\\Zoe\u0308 Mu\u0308ller\\x",
            "C:/Users/Zoe%CC%88%20Mu%CC%88ller/x",
            // spaces escaped for a shell (finding 7)
            "cd /c/Users/Zo\u00eb\\ M\u00fcller/proj",
            "cd C:\\Users\\Zo\u00eb` M\u00fcller\\proj",
            // the 8.3 name, with the letters Windows keeps (finding 1)
            "/c/Users/ZOMLLE~1/AppData",
        )
        plantLeakForms(pc1, zoeRoot, "s-1", forms)
        val forbidden = listOf(
            "Zo\u00eb", "Zoe\u0308", "Zo\\u00eb", "M\u00fcller", "Mu\u0308ller", "M\\u00fcller", "Zo%C3%AB", "M%C3%BCller", "ZOMLLE", "Mu%CC%88ller",
        )
        val (archive, _) = exportAndAssertNoneOf(pc1, "C:\\Users\\Zo\u00eb M\u00fcller", forbidden)
        importAndAssertNoTraceOf(archive, "Müller")
    }

    @Test
    fun `the archive names a dotted account in none of the 8-3 forms Windows gives it`() {
        val pc1 = Pc("PC_1", "alice.smith")
        val smithRoot = "C:\\Users\\alice.smith\\progetti\\demo"
        writePc1Session(pc1.home, "s-1", smithRoot, "Aggiungi un test", user = "alice.smith")
        val forms = listOf(
            // alice.smith is ALICE~1.SMI to Windows (finding 11)
            "C:\\Users\\ALICE~1.SMI\\AppData\\Local\\Temp",
            "/c/Users/ALICE~1.SMI/x",
            "C--Users-ALICE-1-SMI-AppData-Local-Temp-x",
            "C:\\\\Users\\\\ALICE~1.SMI\\\\x",
        )
        plantLeakForms(pc1, smithRoot, "s-1", forms)
        val session = SessionScanner.listSessions(pc1.home).single()
        val archive = tmp.root.toPath().resolve("export.zip")
        val outcome = exportFrom(pc1, listOf(session), archive, "C:\\Users\\alice.smith")
        for ((name, bytes) in entriesOf(archive)) {
            val text = String(bytes, Charsets.UTF_8)
            assertFalse("$name holds a short name", text.contains("~1", ignoreCase = true) || name.contains("~1", ignoreCase = true))
            assertFalse("$name holds the surname", text.contains("smith", ignoreCase = true) || name.contains("smith", ignoreCase = true))
        }
        assertEquals(0, outcome.residualUserNameHits)
    }

    @Test
    fun `export keeps every unchanged line byte for byte and the line endings of each line`() {
        val pc1 = Pc("PC_1", "alice")
        val dir = projectDirOf(pc1, aliceRoot)
        val untouched = """{"type":"assistant",  "message":{"role":"assistant","content":[{"type":"text","text":"a <b> & 'c' é"}]},"timestamp":"2024-03-10T09:00:05.000Z","sessionId":"s-1"}"""
        val first = """{"type":"user","message":{"role":"user","content":"ciao"},"cwd":"C:\\Users\\alice\\progetti\\demo","timestamp":"2024-03-10T09:00:00.000Z","sessionId":"s-1"}"""
        val third = """{"type":"user","message":{"role":"user","content":"leggi <C:\\Users\\alice\\n.txt>"},"timestamp":"2024-03-10T09:00:09.000Z","sessionId":"s-1","parentUuid":null}"""
        Files.writeString(dir.resolve("s-1.jsonl"), "$first\r\n$untouched\r\n$third\n\n")
        val session = SessionScanner.listSessions(pc1.home).single()

        val archive = tmp.root.toPath().resolve("export.zip")
        exportFrom(pc1, listOf(session), archive)

        val parts = textOf(archive, "sessions/s-1/transcript.jsonl").split("\n")
        assertEquals(5, parts.size)
        assertTrue(parts[0].endsWith("\r"))
        assertTrue(parts[0].contains("\"cwd\":\"{{SP:ROOT:win}}\""))
        assertEquals("$untouched\r", parts[1]) // the odd spacing and the unescaped characters prove it was not re-emitted
        assertEquals(
            """{"type":"user","message":{"role":"user","content":"leggi <{{SP:HOME:win}}\\n.txt>"},"timestamp":"2024-03-10T09:00:09.000Z","sessionId":"s-1","parentUuid":null}""",
            parts[2],
        )
        assertEquals(listOf("", ""), parts.drop(3))
    }

    @Test
    fun `a line that is not JSON goes through the raw text pass, one escaping level down`() {
        val pc1 = Pc("PC_1", "alice")
        val dir = projectDirOf(pc1, aliceRoot)
        Files.writeString(
            dir.resolve("s-1.jsonl"),
            """{"type":"user","cwd":"C:\\Users\\alice\\progetti\\demo","sessionId":"s-1"}""" + "\n" +
                """{"type":"user","text":"C:\\Users\\alice\\progetti\\demo\\src and C:\\\\Users\\\\alice\\\\x"""" + "\n",
        )
        val session = SessionScanner.listSessions(pc1.home).single()
        val archive = tmp.root.toPath().resolve("export.zip")
        val outcome = exportFrom(pc1, listOf(session), archive)

        val lines = textOf(archive, "sessions/s-1/transcript.jsonl").trimEnd().split("\n")
        assertEquals("""{"type":"user","text":"{{SP:ROOT:esc1}}\\src and {{SP:HOME:esc2}}\\\\x"""", lines[1])
        assertEquals(setOf("esc1", "esc2", "enc", "win"), outcome.replacedByStyle.keys)
    }

    @Test
    fun `text files are anonymized, binary ones are copied and reported, by entry name`() {
        val pc1 = Pc("PC_1", "alice")
        val session = writePc1Session(pc1.home, "s-1", aliceRoot, "Aggiungi un test")
        val aux = projectDirOf(pc1, aliceRoot).resolve("s-1").resolve("tool-results")
        val history = pc1.home.resolve("file-history").resolve("s-1")
        // Text, with the CRLF endings of a Windows tool kept.
        Files.writeString(aux.resolve("notes.txt"), "riga 1 C:\\Users\\alice\\docs\r\nriga 2\r\n")
        // Binaries: a NUL, the path in UTF-8 and in UTF-16LE (how a Windows tool stores it), and a file that is not UTF-8.
        val utf8 = "C:\\Users\\alice\\docs".toByteArray(Charsets.UTF_8)
        val utf16 = "C:\\Users\\alice\\docs".toByteArray(Charsets.UTF_16LE)
        val withNul = byteArrayOf(0x89.toByte(), 0x50, 0x00) + utf8 + byteArrayOf(0x00) + utf16
        Files.write(aux.resolve("blob.bin"), withNul)
        val notUtf8 = byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x41, 0x20) + utf8
        Files.write(history.resolve("img@v1"), notUtf8)
        Files.write(history.resolve("clean@v1"), byteArrayOf(0x00, 0x01, 0x02))

        val archive = tmp.root.toPath().resolve("export.zip")
        val outcome = exportFrom(pc1, listOf(session), archive)

        val entries = entriesOf(archive)
        assertEquals("riga 1 {{SP:HOME:win}}\\docs\r\nriga 2\r\n", String(entries.getValue("sessions/s-1/aux/tool-results/notes.txt"), Charsets.UTF_8))
        assertTrue(withNul.contentEquals(entries.getValue("sessions/s-1/aux/tool-results/blob.bin")))
        assertTrue(notUtf8.contentEquals(entries.getValue("sessions/s-1/file-history/img@v1")))
        assertEquals(
            listOf("sessions/s-1/aux/tool-results/blob.bin", "sessions/s-1/file-history/img@v1"),
            outcome.binaryEntriesWithHits.sorted(),
        )
    }

    @Test
    fun `zip entry names are anonymized and two names that become one stay two entries`() {
        val pc1 = Pc("PC_1", "alice")
        val session = writePc1Session(pc1.home, "s-1", aliceRoot, "Aggiungi un test")
        val aux = projectDirOf(pc1, aliceRoot).resolve("s-1").resolve("tool-results")
        Files.writeString(aux.resolve("C--Users-alice-notes.txt"), "uno")
        // Names that differ by case only become the same token: only a case sensitive file system holds both.
        Files.writeString(aux.resolve("C--Users-ALICE-notes.txt"), "due")
        val both = Files.list(aux).use { files -> files.filter { "notes" in it.name }.count() } == 2L
        val archive = tmp.root.toPath().resolve("export.zip")
        exportFrom(pc1, listOf(session), archive)

        val names = entriesOf(archive).keys.filter { "notes" in it }.sorted()
        val plain = "sessions/s-1/aux/tool-results/{{SP:HOME:enc}}-notes.txt"
        assertEquals(if (both) listOf(plain, "sessions/s-1/aux/tool-results/{{SP:HOME:enc}}-notes~2.txt") else listOf(plain), names)
    }

    @Test
    fun `a source that already holds the token marker gets a nonce prefix, recorded in the manifest`() {
        val pc1 = Pc("PC_1", "alice")
        val session = writePc1Session(pc1.home, "s-1", aliceRoot, "Aggiungi un test")
        // The conversation talks about the very syntax of the tokens.
        Files.writeString(pc1.home.resolve("file-history").resolve("s-1").resolve("main@v1"), "uses {{SP:HOME:win}} as a placeholder\n")
        val archive = tmp.root.toPath().resolve("export.zip")
        exportFrom(pc1, listOf(session), archive)

        val prefix = SessionArchive.readArchiveManifest(archive).anonymization!!.tokenPrefix
        assertTrue(prefix, Regex("SP[0-9a-f]{4}").matches(prefix))
        val manifest = textOf(archive, "manifest.json")
        assertTrue(manifest.contains("\"projectPath\": \"{{$prefix:ROOT:win}}\""))
        assertFalse(manifest.contains("{{SP:ROOT"))
        // The literal of the source is untouched; the archive's own tokens are the nonce ones.
        assertEquals("uses {{SP:HOME:win}} as a placeholder\n", textOf(archive, "sessions/s-1/file-history/main@v1"))
        assertTrue(textOf(archive, "sessions/s-1/transcript.jsonl").contains("{{$prefix:ROOT:win}}"))
    }

    @Test
    fun `a transcript still being written is trimmed to its last whole line, with a warning`() {
        val pc1 = Pc("PC_1", "alice")
        val session = writePc1Session(pc1.home, "s-1", aliceRoot, "Aggiungi un test")
        val transcript = transcriptOn(pc1, aliceRoot, "s-1")
        val whole = Files.readString(transcript)
        Files.writeString(transcript, whole + """{"type":"assistant","message":{"role":"assis""")
        val subagent = subagentOn(pc1, aliceRoot, "s-1")
        Files.writeString(subagent, Files.readString(subagent) + """{"type":"user","mess""")

        val archive = tmp.root.toPath().resolve("export.zip")
        val outcome = exportFrom(pc1, listOf(SessionScanner.listSessions(pc1.home).single()), archive)

        assertEquals(2, outcome.partialLinesDropped)
        assertEquals(2, outcome.warnings.size)
        assertTrue(outcome.warnings.any { it.contains("transcript.jsonl") })
        assertTrue(outcome.warnings.any { it.contains("subagents/agent-a1.jsonl") })
        val archived = textOf(archive, "sessions/s-1/transcript.jsonl")
        assertTrue(archived.endsWith("\n"))
        assertFalse(archived.contains("assis\""))
        assertEquals(whole.count { it == '\n' }, archived.count { it == '\n' })
        assertTrue(textOf(archive, "sessions/s-1/aux/subagents/agent-a1.jsonl").endsWith("}\n"))
    }

    @Test
    fun `a POSIX account is recorded as such and its paths are tokens too`() {
        val pc1 = Pc("PC_1", "alice")
        val session = writePc1Session(pc1.home, "s-1", "/home/alice/dev/demo", "Aggiungi un test")
        val archive = tmp.root.toPath().resolve("export.zip")
        exportFrom(pc1, listOf(session), archive, spelledHome = "/home/alice")

        val manifest = SessionArchive.readArchiveManifest(archive)
        assertEquals("posix", manifest.anonymization!!.sourceOs)
        val listed = manifest.sessions.single()
        assertEquals("{{SP:ROOT:fwd}}", listed.projectPath)
        assertEquals("{{SP:ROOT:enc}}", listed.projectFolderName)
        assertEquals("demo", listed.projectName)
        val text = textOf(archive, "sessions/s-1/transcript.jsonl")
        assertTrue(text.contains("\"cwd\":\"{{SP:ROOT:fwd}}/src\""))
        assertFalse(text.contains("/home/alice"))
    }

    @Test
    fun `a project outside the home, a relocated Claude home and a project that is the home`() {
        val pc1 = Pc("PC_1", "alice")
        val outside = writePc1Session(pc1.home, "s-out", "D:\\lavoro\\demo", "Aggiungi un test")
        val archive = tmp.root.toPath().resolve("outside.zip")
        // Claude's own home lives on another drive and the session talks about it.
        val relocated = SourceIdentity(home = "C:\\Users\\alice", claudeHome = "D:\\claude-home")
        val transcript = transcriptOn(pc1, "D:\\lavoro\\demo", "s-out")
        Files.writeString(transcript, Files.readString(transcript) + gson.toJson(mapOf("type" to "user", "message" to mapOf("content" to "vedi D:\\claude-home\\projects\\x e C:\\Users\\alice\\note.txt"), "sessionId" to "s-out")) + "\n")
        val outcome = SessionArchive.export(listOf(SessionScanner.listSessions(pc1.home).single()), archive, home = pc1.home, identity = relocated)
        val text = textOf(archive, "sessions/s-out/transcript.jsonl")
        assertTrue(text.contains("vedi {{SP:CLAUDE_HOME:win}}\\\\projects\\\\x e {{SP:HOME:win}}\\\\note.txt"))
        assertTrue(text.contains("\"cwd\":\"{{SP:ROOT:win}}\""))
        assertEquals(setOf("CLAUDE_HOME", "HOME", "ROOT"), outcome.replacedByKind.keys)
        assertEquals("demo", SessionArchive.readArchiveManifest(archive).sessions.single().projectName)
        assertEquals("{{SP:ROOT:enc}}", SessionArchive.readArchiveManifest(archive).sessions.single().projectFolderName)

        // A project that is the home itself: its name is the user's, so the manifest does not repeat it.
        val pc2 = Pc("PC_1b", "alice")
        val atHome = writePc1Session(pc2.home, "s-home", "C:\\Users\\alice", "Aggiungi un test")
        val homeArchive = tmp.root.toPath().resolve("home.zip")
        exportFrom(pc2, listOf(atHome), homeArchive)
        val listed = SessionArchive.readArchiveManifest(homeArchive).sessions.single()
        assertEquals("{{SP:ROOT:win}}", listed.projectPath)
        assertEquals(null, listed.projectName)
        assertFalse(textOf(homeArchive, "sessions/s-home/transcript.jsonl").contains("alice", ignoreCase = true))
        assertFalse(textOf(homeArchive, "manifest.json").contains("alice", ignoreCase = true))
    }

    @Test
    fun `boundaries - alice2 and alice dot bak are other folders, Alice is alice`() {
        val pc1 = Pc("PC_1", "alice")
        val session = writePc1Session(pc1.home, "s-1", aliceRoot, "Aggiungi un test")
        val transcript = transcriptOn(pc1, aliceRoot, "s-1")
        val message = "C:\\Users\\alice2\\x C:\\Users\\alice.bak\\y C:\\Users\\Alice\\z C:\\Users\\aliceX"
        Files.writeString(transcript, Files.readString(transcript) + gson.toJson(mapOf("type" to "user", "message" to mapOf("content" to message), "sessionId" to "s-1")) + "\n")
        val archive = tmp.root.toPath().resolve("export.zip")
        exportFrom(pc1, listOf(SessionScanner.listSessions(pc1.home).single()), archive)

        val lines = textOf(archive, "sessions/s-1/transcript.jsonl").trimEnd().split("\n")
        val content = JsonParser.parseString(lines.last()).asJsonObject.getAsJsonObject("message").get("content").asString
        assertEquals("C:\\Users\\alice2\\x C:\\Users\\alice.bak\\y {{SP:HOME:win}}\\z C:\\Users\\aliceX", content)
    }

    // The residual count of the engine leaves the tokens out: for an account called root every
    // {{SP:ROOT:..}} token would be a hit (WP3 review, finding 19).
    @Test
    fun `a user called root is not reported as residual because of the ROOT tokens`() {
        val pc1 = Pc("PC_1", "root")
        val dir = projectDirOf(pc1, "/root/dev/demo")
        Files.writeString(dir.resolve("s-1.jsonl"), """{"type":"user","message":{"role":"user","content":"ciao"},"cwd":"/root/dev/demo","timestamp":"2024-03-10T09:00:00.000Z","sessionId":"s-1"}""" + "\n")
        val session = SessionScanner.listSessions(pc1.home).single()
        val archive = tmp.root.toPath().resolve("export.zip")
        val outcome = exportFrom(pc1, listOf(session), archive, spelledHome = "/root")

        assertTrue(textOf(archive, "sessions/s-1/transcript.jsonl").contains("{{SP:ROOT:fwd}}"))
        assertEquals(0, outcome.residualUserNameHits)
    }

    // ------------------------------------------------------------------ anonymized import (format 2) --

    /** bob's home spelled in each of the forms [homeForms] plants, in the same order, as PC_2 renders them. */
    private fun bobForms(home: String): List<String> {
        val forward = home.replace('\\', '/')
        val drive = home[0].lowercaseChar()
        val folders = home.substring(3).replace('\\', '/')
        fun escaped(times: Int) = home.replace("\\", "\\".repeat(times))
        return listOf(
            "$home\\docs\\a.txt",
            "$forward/docs/a.txt",
            "${escaped(2)}\\\\docs\\\\a.txt",
            "${escaped(4)}\\\\\\\\docs\\\\\\\\a.txt",
            "/$drive/$folders/docs/a.txt",
            "/mnt/$drive/$folders/docs/a.txt",
            "$home\\AppData\\Local\\Temp\\x.txt",
            "${ClaudePaths.encodeProjectPath(home)}-docs-a-txt",
            "file:///$forward/docs/a.txt",
        )
    }

    @Test
    fun `every spelling of alice's home comes back as bob's home, everywhere it was planted`() {
        val pc1 = Pc("PC_1", "alice")
        val session = writePc1Session(pc1.home, "s-1", aliceRoot, "Aggiungi un test", forms = true)
        val archive = tmp.root.toPath().resolve("export.zip")
        exportFrom(pc1, listOf(session), archive)

        val pc2 = Pc("PC_2", "bob")
        val bobDemo = pc2.projectFolder("lavoro", "demo")
        val home = pc2.root.toString()
        val outcome = SessionArchive.import(pc2.receive(archive, "a.zip"), home = pc2.home, targetProjectPath = bobDemo, userHome = home)

        // Every check, #14 included: nothing failed and no token was left.
        assertEquals(emptyList<FailedCheck>(), outcome.failedChecks)
        assertEquals(emptyList<ImportFailure>(), outcome.failures)
        assertNoTokens(pc2.home)
        val formsText = bobForms(home).joinToString(" | ")
        val escapedHome = home.replace("\\", "\\\\")

        val lines = linesOf(transcriptOn(pc2, bobDemo, "s-1"))
        fun message(prefix: String) = lines.mapNotNull { it.getAsJsonObject("message")?.get("content") }
            .filter { it.isJsonPrimitive }.map { it.asString }.single { it.startsWith(prefix) }
        assertEquals("Forme: $formsText", message("Forme: "))
        val result = lines.mapNotNull { it.getAsJsonObject("toolUseResult") }.single { it.has("stdout") }
        assertEquals(formsText, result.get("stdout").asString)
        val rendered = lines.mapNotNull { it.getAsJsonArray("rendered") }.single { it.size() == 2 }[1].asJsonObject.get("content").asString
        assertEquals("<system-reminder>\nCartella: $formsText\n</system-reminder>", rendered)
        val edited = lines.mapNotNull { it.getAsJsonObject("attachment") }.single { it.get("type").asString == "edited_text_file" }
        assertEquals("$home\\docs\\a.txt", edited.get("filename").asString)
        assertEquals("1\t$formsText", edited.get("snippet").asString)
        // A key that is a path is renamed like a value.
        val tracked = lines.single { it.get("type").asString == "file-history-snapshot" }.getAsJsonObject("snapshot").getAsJsonObject("trackedFileBackups")
        assertTrue(tracked.has("$home\\docs\\a.txt"))
        assertEquals("$home\\docs", tracked.getAsJsonObject("$home\\docs\\a.txt").get("realParentDir").asString)

        // The auxiliary folder: text files, the JSON metadata (one escaping level down) and the subagent run.
        val aux = transcriptOn(pc2, bobDemo, "s-1").resolveSibling("s-1")
        assertEquals("$formsText\n", Files.readString(aux.resolve("tool-results/toolu_2.txt")))
        assertEquals("""{"path":"$escapedHome\\docs\\a.txt","tool":"Read"}""", Files.readString(aux.resolve("tool-results/toolu_2.meta.json")))
        val subagent = Files.readAllLines(subagentOn(pc2, bobDemo, "s-1"))
        val msys = "/${home[0].lowercaseChar()}/${home.substring(3).replace('\\', '/')}"
        assertEquals(
            """{"type":"user","text":"$escapedHome\\docs\\a.txt and ${escapedHome.replace("\\", "\\\\")}\\\\docs and $msys/docs""",
            subagent.last(),
        )
        val formsLine = JsonParser.parseString(subagent[subagent.size - 2]).asJsonObject
        assertEquals("Forme: $formsText", formsLine.getAsJsonObject("message").getAsJsonArray("content")[0].asJsonObject.get("text").asString)
        val history = pc2.home.resolve("file-history").resolve("s-1")
        assertEquals("// $formsText\n", Files.readString(history.resolve("main@v3")))
        assertEquals("// copia di $home\\docs\\a.txt\n", Files.readString(history.resolve("docs@v1")))

        // What stays is what was never a path: the bare name, in a sentence and in a branch.
        assertEquals(listOf("projects/${ClaudePaths.encodeProjectPath(bobDemo)}/s-1.jsonl"), traces(pc2.home, "alice").map { it.replace('\\', '/') })
    }

    @Test
    fun `an archive entry named after alice's home is written under bob's name, a name a Windows file system accepts`() {
        val pc1 = Pc("PC_1", "alice")
        val session = writePc1Session(pc1.home, "s-1", aliceRoot, "Aggiungi un test")
        Files.writeString(projectDirOf(pc1, aliceRoot).resolve("s-1").resolve("tool-results").resolve("C--Users-alice-notes.txt"), "uno\n")
        val archive = tmp.root.toPath().resolve("export.zip")
        exportFrom(pc1, listOf(session), archive)
        // In the archive the name holds a token, and a colon: it is never used as a file name as it is.
        assertTrue(entriesOf(archive).keys.contains("sessions/s-1/aux/tool-results/{{SP:HOME:enc}}-notes.txt"))

        val pc2 = Pc("PC_2", "bob")
        val bobDemo = pc2.projectFolder("lavoro", "demo")
        val home = pc2.root.toString()
        val outcome = SessionArchive.import(pc2.receive(archive, "a.zip"), home = pc2.home, targetProjectPath = bobDemo, userHome = home)
        assertEquals(emptyList<ImportFailure>(), outcome.failures)

        val results = transcriptOn(pc2, bobDemo, "s-1").resolveSibling("s-1").resolve("tool-results")
        assertEquals("uno\n", Files.readString(results.resolve("${ClaudePaths.encodeProjectPath(home)}-notes.txt")))
        Files.walk(pc2.home).use { paths -> paths.forEach { assertFalse("$it", it.name.contains(":") || it.name.contains("{")) } }
    }

    @Test
    fun `a format 1 archive of an earlier version still imports, kept in its own folder or attached`() {
        val fixture = tmp.root.toPath().resolve("format1-alice.zip")
        javaClass.getResourceAsStream("/fixtures/format1-alice.zip").use { Files.copy(it!!, fixture) }
        val manifest = SessionArchive.readArchiveManifest(fixture)
        assertEquals(1, manifest.formatVersion)
        assertFalse(manifest.isAnonymized)
        assertEquals(aliceRoot, manifest.sessions.single().projectPath)

        // "Keep the original folder": alice's own folder name, which PC_2 does not have (#8 only).
        val kept = Pc("PC_2", "bob")
        val keptOutcome = SessionArchive.import(kept.receive(fixture, "a.zip"), home = kept.home, targetProjectPath = null)
        assertEquals(listOf(8), keptOutcome.failedChecks.map { it.number })
        val listed = SessionScanner.listSessions(kept.home).single()
        assertEquals(ClaudePaths.encodeProjectPath(aliceRoot), listed.projectFolderName)
        assertEquals(aliceRoot, listed.projectPath)
        // Format 1 is never anonymized on the way in: the conversation is what it was.
        assertTrue(Files.readString(transcriptOn(kept, aliceRoot, "s-v1")).contains("Leggi anche C:\\\\Users\\\\alice\\\\note.txt"))

        // Attached to a folder of bob's: as before, with check 14 simply not applying.
        val attached = Pc("PC_3", "bob")
        val bobDemo = attached.projectFolder("lavoro", "demo")
        val outcome = SessionArchive.import(attached.receive(fixture, "a.zip"), home = attached.home, targetProjectPath = bobDemo)
        assertEquals(emptyList<FailedCheck>(), outcome.failedChecks)
        val canonical = ClaudePaths.normalizeProjectPath(bobDemo)
        assertEquals(setOf(canonical, "$canonical\\src"), linesOf(transcriptOn(attached, bobDemo, "s-v1")).mapNotNull { it.get("cwd")?.asString }.toSet())
        val results = transcriptOn(attached, bobDemo, "s-v1").resolveSibling("s-v1").resolve("tool-results/toolu_1.txt")
        assertEquals("Listing of $aliceRoot\n", Files.readString(results))
    }

    @Test
    fun `check 14 reports a token that survived the import`() {
        val pc1 = Pc("PC_1", "alice")
        val session = writePc1Session(pc1.home, "s-1", aliceRoot, "Aggiungi un test")
        val archive = tmp.root.toPath().resolve("export.zip")
        exportFrom(pc1, listOf(session), archive)
        val pc2 = Pc("PC_2", "bob")
        val bobDemo = pc2.projectFolder("lavoro", "demo")
        val outcome = SessionArchive.import(pc2.receive(archive, "a.zip"), home = pc2.home, targetProjectPath = bobDemo)
        val imported = outcome.imported.single()
        val target = ImportDiagnostics.Target(imported, null, ClaudePaths.normalizeProjectPath(bobDemo), true, "SP")
        val diagnostics = ImportDiagnostics(pc2.home)
        assertTrue(diagnostics.runChecks(target, Instant.now()).single { it.number == 14 }.ok)

        val results = transcriptOn(pc2, bobDemo, "s-1").resolveSibling("s-1").resolve("tool-results/toolu_1.txt")
        Files.writeString(results, "left {{SP:HOME:win}}\\x\n")
        val check = diagnostics.runChecks(target, Instant.now()).single { it.number == 14 }
        assertFalse(check.ok)
        assertTrue(check.detail, check.detail!!.contains("aux/tool-results/toolu_1.txt"))
        // A format 1 target has no prefix: nothing to look for.
        assertTrue(diagnostics.runChecks(target.copy(tokenPrefix = null), Instant.now()).single { it.number == 14 }.ok)
        assertEquals(14, ImportDiagnostics.CHECK_COUNT)
    }

    @Test
    fun `a token spelled with unicode escapes in a transcript is found, so the prefix is a nonce`() {
        val pc1 = Pc("PC_1", "alice")
        writePc1Session(pc1.home, "s-1", aliceRoot, "Aggiungi un test")
        val escape = "\\" + "u007b"
        val transcript = transcriptOn(pc1, aliceRoot, "s-1")
        val line = """{"type":"user","message":{"content":"${escape}${escape}SP:HOME:win}}"},"sessionId":"s-1"}"""
        Files.writeString(transcript, Files.readString(transcript) + line + "\n")
        val archive = tmp.root.toPath().resolve("export.zip")
        exportFrom(pc1, listOf(SessionScanner.listSessions(pc1.home).single()), archive)
        assertNotEquals("SP", SessionArchive.readArchiveManifest(archive).anonymization!!.tokenPrefix)
    }

    @Test
    fun `a literal token of the source survives the round trip and is not rendered`() {
        val pc1 = Pc("PC_1", "alice")
        writePc1Session(pc1.home, "s-1", aliceRoot, "Aggiungi un test")
        // In a text file, in plain JSON, and with its braces written as unicode escapes in JSON, which a
        // scan of the raw bytes does not see but the import would still read as a token.
        val escape = "\\" + "u007b"
        val transcript = transcriptOn(pc1, aliceRoot, "s-1")
        val literal = """{"type":"user","message":{"content":"literal ${escape}${escape}SP:HOME:win}} and {{SP:ROOT:fwd}}"},"sessionId":"s-1"}"""
        Files.writeString(transcript, Files.readString(transcript) + literal + "\n")
        Files.writeString(pc1.home.resolve("file-history").resolve("s-1").resolve("main@v1"), "uses {{SP:HOME:win}} as a placeholder\n")
        val archive = tmp.root.toPath().resolve("export.zip")
        exportFrom(pc1, listOf(SessionScanner.listSessions(pc1.home).single()), archive)
        val prefix = SessionArchive.readArchiveManifest(archive).anonymization!!.tokenPrefix
        assertNotEquals("SP", prefix)

        val pc2 = Pc("PC_2", "bob")
        val bobDemo = pc2.projectFolder("lavoro", "demo")
        val outcome = SessionArchive.import(pc2.receive(archive, "a.zip"), home = pc2.home, targetProjectPath = bobDemo, userHome = pc2.root.toString())
        assertEquals(emptyList<FailedCheck>(), outcome.failedChecks)

        val text = Files.readString(transcriptOn(pc2, bobDemo, "s-1"))
        val content = linesOf(transcriptOn(pc2, bobDemo, "s-1")).last().getAsJsonObject("message").get("content").asString
        assertEquals("literal {{SP:HOME:win}} and {{SP:ROOT:fwd}}", content)
        assertFalse(text.contains("{{$prefix"))
        assertEquals("uses {{SP:HOME:win}} as a placeholder\n", Files.readString(pc2.home.resolve("file-history/s-1/main@v1")))
    }

    @Test
    fun `a project outside the home, a relocated Claude home and a project that is the home all come back`() {
        val pc1 = Pc("PC_1", "alice")
        writePc1Session(pc1.home, "s-out", "D:\\lavoro\\demo", "Aggiungi un test")
        val transcript = transcriptOn(pc1, "D:\\lavoro\\demo", "s-out")
        val typed = gson.toJson(mapOf("type" to "user", "message" to mapOf("content" to "vedi D:\\claude-home\\projects\\x"), "sessionId" to "s-out"))
        Files.writeString(transcript, Files.readString(transcript) + typed + "\n")
        val outsideArchive = tmp.root.toPath().resolve("outside.zip")
        val relocated = SourceIdentity(home = "C:\\Users\\alice", claudeHome = "D:\\claude-home")
        SessionArchive.export(listOf(SessionScanner.listSessions(pc1.home).single()), outsideArchive, home = pc1.home, identity = relocated)
        val atHome = writePc1Session(pc1.home, "s-home", "C:\\Users\\alice", "Aggiungi un test")
        val homeArchive = tmp.root.toPath().resolve("home.zip")
        exportFrom(pc1, listOf(atHome), homeArchive)

        val pc2 = Pc("PC_2", "bob")
        val bobDemo = pc2.projectFolder("lavoro", "demo")
        val bobHome = pc2.root.toString()
        val claudeHome = pc2.home.toAbsolutePath().toString()
        val first = SessionArchive.import(pc2.receive(outsideArchive, "a.zip"), home = pc2.home, targetProjectPath = bobDemo, userHome = bobHome)
        assertEquals(emptyList<FailedCheck>(), first.failedChecks)
        val text = Files.readString(transcriptOn(pc2, bobDemo, "s-out"))
        // The Claude home token becomes PC_2's Claude home, not bob's home.
        assertTrue(text, text.contains("vedi ${claudeHome.replace("\\", "\\\\")}\\\\projects\\\\x"))
        assertNoTokens(pc2.home)
        assertEquals(emptyList<String>(), traces(pc2.home, "D:\\\\lavoro"))

        // The project that was alice's home: bob's target replaces it.
        val second = SessionArchive.import(pc2.receive(homeArchive, "b.zip"), home = pc2.home, targetProjectPath = bobDemo, userHome = bobHome)
        assertEquals(emptyList<FailedCheck>(), second.failedChecks)
        val canonical = ClaudePaths.normalizeProjectPath(bobDemo)
        val cwds = linesOf(transcriptOn(pc2, bobDemo, "s-home")).mapNotNull { it.get("cwd")?.asString }.toSet()
        assertEquals(setOf(canonical, "$canonical\\src"), cwds)
        assertEquals(emptyList<String>(), traces(pc2.home, "alice"))
    }

    @Test
    fun `alice2 and alice dot bak are not alice, on the way out and on the way back`() {
        val pc1 = Pc("PC_1", "alice")
        writePc1Session(pc1.home, "s-1", aliceRoot, "Aggiungi un test")
        val transcript = transcriptOn(pc1, aliceRoot, "s-1")
        val message = "C:\\Users\\alice2\\x C:\\Users\\alice.bak\\y C:\\Users\\Alice\\z"
        Files.writeString(transcript, Files.readString(transcript) + gson.toJson(mapOf("type" to "user", "message" to mapOf("content" to message), "sessionId" to "s-1")) + "\n")
        val archive = tmp.root.toPath().resolve("export.zip")
        exportFrom(pc1, listOf(SessionScanner.listSessions(pc1.home).single()), archive)

        val pc2 = Pc("PC_2", "bob")
        val bobDemo = pc2.projectFolder("lavoro", "demo")
        val home = pc2.root.toString()
        SessionArchive.import(pc2.receive(archive, "a.zip"), home = pc2.home, targetProjectPath = bobDemo, userHome = home)
        val content = linesOf(transcriptOn(pc2, bobDemo, "s-1")).last().getAsJsonObject("message").get("content").asString
        assertEquals("C:\\Users\\alice2\\x C:\\Users\\alice.bak\\y $home\\z", content)
    }

    @Test
    fun `a name with spaces and accents comes back in its encoded and percent forms`() {
        val pc1 = Pc("PC_1", "Zoë Müller")
        val zoeRoot = "C:\\Users\\Zoë Müller\\Mes Projets\\démo"
        writePc1Session(pc1.home, "s-1", zoeRoot, "Ajoute un test", user = "Zoë Müller")
        val transcript = transcriptOn(pc1, zoeRoot, "s-1")
        val forms = "file:///C:/Users/Zo%C3%AB%20M%C3%BCller/x C--Users-Zo--M-ller-notes C:/Users/Zoë Müller/y"
        Files.writeString(transcript, Files.readString(transcript) + gson.toJson(mapOf("type" to "user", "message" to mapOf("content" to forms), "sessionId" to "s-1")) + "\n")
        val archive = tmp.root.toPath().resolve("export.zip")
        exportFrom(pc1, listOf(SessionScanner.listSessions(pc1.home).single()), archive)
        val leaks = entriesOf(archive).filter { (_, bytes) -> String(bytes, Charsets.UTF_8).let { "Zo%C3" in it || "Müller" in it || "M-ller" in it } }
        assertEquals(emptyList<String>(), leaks.keys.toList())

        val pc2 = Pc("PC_2", "Bob O'Brien")
        val bobRoot = pc2.projectFolder("Progetti Lavoro", "démo")
        val home = pc2.root.toString()
        val outcome = SessionArchive.import(pc2.receive(archive, "a.zip"), home = pc2.home, targetProjectPath = bobRoot, userHome = home)
        assertEquals(emptyList<FailedCheck>(), outcome.failedChecks)
        val content = linesOf(transcriptOn(pc2, bobRoot, "s-1")).last().getAsJsonObject("message").get("content").asString
        assertTrue(content, content.contains("${ClaudePaths.encodeProjectPath(home)}-notes"))
        assertTrue(content, content.contains("${home.replace('\\', '/')}/y"))
        assertNoTokens(pc2.home)
    }

    @Test
    fun `a transcript of 50 MB is exported and imported within a time bound`() {
        val pc1 = Pc("PC_1", "alice")
        writePc1Session(pc1.home, "s-1", aliceRoot, "Aggiungi un test")
        val transcript = transcriptOn(pc1, aliceRoot, "s-1")
        val filler = "x".repeat(900)
        val message = mapOf("content" to "C:\\Users\\alice\\docs $filler")
        val line = gson.toJson(mapOf("type" to "user", "cwd" to aliceRoot, "sessionId" to "s-1", "message" to message)) + "\n"
        Files.newBufferedWriter(transcript, Charsets.UTF_8, java.nio.file.StandardOpenOption.APPEND).use { out ->
            repeat(50 * 1024 * 1024 / line.length) { out.write(line) }
        }
        val archive = tmp.root.toPath().resolve("export.zip")
        val pc2 = Pc("PC_2", "bob")
        val bobDemo = pc2.projectFolder("lavoro", "demo")

        val started = System.nanoTime()
        exportFrom(pc1, listOf(SessionScanner.listSessions(pc1.home).single()), archive)
        val outcome = SessionArchive.import(pc2.receive(archive, "a.zip"), home = pc2.home, targetProjectPath = bobDemo, userHome = pc2.root.toString())
        val seconds = (System.nanoTime() - started) / 1_000_000_000.0

        assertEquals(emptyList<ImportFailure>(), outcome.failures)
        assertTrue("took $seconds s", seconds < 240)
        assertTrue(Files.size(transcriptOn(pc2, bobDemo, "s-1")) > 45L * 1024 * 1024)
    }
}
