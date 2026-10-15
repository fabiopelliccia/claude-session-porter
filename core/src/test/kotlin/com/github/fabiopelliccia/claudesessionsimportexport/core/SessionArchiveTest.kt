package com.github.fabiopelliccia.claudesessionsimportexport.core

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.Locale

class SessionArchiveTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** A Windows path only survives inside a JSON string with its backslashes escaped. */
    private fun String.asJsonString(): String = replace("\\", "\\\\")

    private fun writeSourceSession(home: java.nio.file.Path, id: String, cwd: String): SessionInfo {
        val projectDir = home.resolve("projects").resolve(ClaudePaths.encodeProjectPath(cwd))
        Files.createDirectories(projectDir)
        Files.write(
            projectDir.resolve("$id.jsonl"),
            listOf(
                """{"type":"summary","summary":"Demo session","sessionId":"$id"}""",
                """{"type":"user","message":{"role":"user","content":"hi"},"timestamp":"2026-01-01T00:00:00.000Z","cwd":"${cwd.asJsonString()}","sessionId":"$id"}""",
            ),
        )
        val auxDir = projectDir.resolve(id).resolve("tool-results")
        Files.createDirectories(auxDir)
        Files.writeString(auxDir.resolve("result.json"), """{"ok":true}""")

        return SessionScanner.listSessions(home).single { it.id == id }
    }

    @Test
    @Ignore("WP5: needs the format 2 import (token pass, target folder)")
    fun `exports and imports a session into a fresh home`() {
        val sourceHome = tmp.newFolder("source", ".claude").toPath()
        val cwd = "C:\\Users\\fabio\\demo"
        val session = writeSourceSession(sourceHome, "session-1", cwd)

        val archive = tmp.root.toPath().resolve("archive.zip")
        val exportOutcome = SessionArchive.export(listOf(session), archive, home = sourceHome)
        assertEquals(1, exportOutcome.exportedCount)
        assertTrue(Files.exists(archive))

        val targetHome = tmp.newFolder("target", ".claude").toPath()
        val importOutcome = SessionArchive.import(archive, home = targetHome)

        assertEquals(1, importOutcome.imported.size)
        val written = importOutcome.imported.single()
        assertEquals("session-1", written.writtenId)
        assertEquals("new", written.action)

        val targetTranscript = targetHome.resolve("projects")
            .resolve(ClaudePaths.encodeProjectPath(cwd))
            .resolve("session-1.jsonl")
        assertTrue(Files.exists(targetTranscript))
        assertTrue(Files.readString(targetTranscript).contains("Demo session") || Files.readString(targetTranscript).contains("\"hi\""))

        val targetAuxFile = targetHome.resolve("projects")
            .resolve(ClaudePaths.encodeProjectPath(cwd))
            .resolve("session-1")
            .resolve("tool-results")
            .resolve("result.json")
        assertTrue(Files.exists(targetAuxFile))
    }

    @Test
    @Ignore("WP5: needs the format 2 import (token pass, target folder)")
    fun `importing twice duplicates under a new id instead of overwriting`() {
        val sourceHome = tmp.newFolder("source2", ".claude").toPath()
        val cwd = "C:\\Users\\fabio\\demo2"
        val session = writeSourceSession(sourceHome, "session-2", cwd)

        val archive = tmp.root.toPath().resolve("archive2.zip")
        SessionArchive.export(listOf(session), archive, home = sourceHome)

        val targetHome = tmp.newFolder("target2", ".claude").toPath()
        val first = SessionArchive.import(archive, home = targetHome)
        assertEquals("new", first.imported.single().action)

        val second = SessionArchive.import(archive, home = targetHome, conflictPolicy = ConflictPolicy.DUPLICATE)
        val duplicated = second.imported.single()
        assertEquals("duplicated", duplicated.action)
        assertTrue(duplicated.writtenId != "session-2")

        val projectDir = targetHome.resolve("projects").resolve(ClaudePaths.encodeProjectPath(cwd))
        assertTrue(Files.exists(projectDir.resolve("session-2.jsonl")))
        assertTrue(Files.exists(projectDir.resolve("${duplicated.writtenId}.jsonl")))
        // The duplicated transcript must reference its own new id, not the original one.
        assertTrue(Files.readString(projectDir.resolve("${duplicated.writtenId}.jsonl")).contains(duplicated.writtenId))
    }

    @Test
    fun `importing into a different machine's folder remaps the project path`() {
        val sourceHome = tmp.newFolder("source4", ".claude").toPath()
        val sourceCwd = "C:\\Users\\fabio\\demo4"
        val session = writeSourceSession(sourceHome, "session-4", sourceCwd)

        val archive = tmp.root.toPath().resolve("archive4.zip")
        SessionArchive.export(listOf(session), archive, home = sourceHome)

        // Simulates a colleague importing the archive on another machine (different drive/user),
        // where the folder Claude Code encoded from the original `cwd` doesn't exist at all.
        val targetHome = tmp.newFolder("target4", ".claude").toPath()
        val targetCwd = "D:\\Work\\colleague\\demo4"
        val outcome = SessionArchive.import(archive, home = targetHome, targetProjectPath = targetCwd)

        val written = outcome.imported.single()
        assertEquals(ClaudePaths.encodeProjectPath(targetCwd), written.projectFolderName)

        val transcript = targetHome.resolve("projects")
            .resolve(ClaudePaths.encodeProjectPath(targetCwd))
            .resolve("session-4.jsonl")
        assertTrue(Files.exists(transcript))

        val userLine = Files.readAllLines(transcript).map { JsonParser.parseString(it).asJsonObject }
            .single { it.has("cwd") }
        assertEquals(targetCwd, userLine.get("cwd").asString)
    }

    @Test
    fun `every recorded working directory is pointed at the target folder`() {
        // Shapes taken from real transcripts: a session records its project root, directories below
        // it, an unrelated root it was moved to mid-way, and - the trap - a sibling folder whose
        // name merely starts like the root.
        val sourceHome = tmp.newFolder("source7", ".claude").toPath()
        val root = "C:\\Users\\fabio\\Demo"
        val projectDir = sourceHome.resolve("projects").resolve(ClaudePaths.encodeProjectPath(root))
        Files.createDirectories(projectDir)
        val recordedCwds = listOf(
            root,
            "$root\\src\\main\\resources",
            "C:\\Users\\fabio\\OtherProject",
            "C:\\Users\\fabio\\Demo2",
        )
        Files.write(
            projectDir.resolve("session-7.jsonl"),
            recordedCwds.mapIndexed { index, recorded ->
                """{"type":"user","message":{"role":"user","content":"m$index"},"timestamp":"2026-01-01T00:00:0$index.000Z","cwd":"${recorded.asJsonString()}","sessionId":"session-7"}"""
            },
        )
        val session = SessionScanner.listSessions(sourceHome).single()

        val archive = tmp.root.toPath().resolve("archive7.zip")
        SessionArchive.export(listOf(session), archive, home = sourceHome)

        val targetHome = tmp.newFolder("target7", ".claude").toPath()
        val targetCwd = "D:\\Work\\Demo"
        SessionArchive.import(archive, home = targetHome, targetProjectPath = targetCwd)

        val transcript = targetHome.resolve("projects")
            .resolve(ClaudePaths.encodeProjectPath(targetCwd))
            .resolve("session-7.jsonl")
        val written = Files.readAllLines(transcript)
            .map { JsonParser.parseString(it).asJsonObject.get("cwd").asString }

        assertEquals(
            listOf(
                targetCwd,                          // the root itself
                "$targetCwd\\src\\main\\resources", // below the root: keeps its remainder
                targetCwd,                          // another project: replaced outright
                targetCwd,                          // sibling folder: must not become "D:\Work\Demo2"
            ),
            written,
        )
    }

    @Test
    @Ignore("WP5: needs the format 2 import (token pass, target folder)")
    fun `imported timestamps are shifted to look recent while keeping their spacing`() {
        val sourceHome = tmp.newFolder("source5", ".claude").toPath()
        val cwd = "C:\\Users\\fabio\\demo5"
        val projectDir = sourceHome.resolve("projects").resolve(ClaudePaths.encodeProjectPath(cwd))
        Files.createDirectories(projectDir)
        Files.write(
            projectDir.resolve("session-5.jsonl"),
            listOf(
                """{"type":"user","message":{"role":"user","content":"first"},"timestamp":"2020-01-01T00:00:00.000Z","cwd":"${cwd.asJsonString()}","sessionId":"session-5"}""",
                """{"type":"assistant","message":{"role":"assistant","content":[{"type":"text","text":"second"}]},"timestamp":"2020-01-01T00:00:05.000Z","sessionId":"session-5"}""",
            ),
        )
        val session = SessionScanner.listSessions(sourceHome).single()

        val archive = tmp.root.toPath().resolve("archive5.zip")
        SessionArchive.export(listOf(session), archive, home = sourceHome)

        val targetHome = tmp.newFolder("target5", ".claude").toPath()
        SessionArchive.import(archive, home = targetHome)

        val transcript = targetHome.resolve("projects")
            .resolve(ClaudePaths.encodeProjectPath(cwd))
            .resolve("session-5.jsonl")
        val timestamps = Files.readAllLines(transcript)
            .map { JsonParser.parseString(it).asJsonObject.get("timestamp").asString }
            .map { Instant.parse(it) }

        // The gap between the two messages must survive the shift...
        assertEquals(Duration.ofSeconds(5), Duration.between(timestamps[0], timestamps[1]))
        // ...and the whole session must now land close to "now", not in 2020.
        assertTrue(Duration.between(timestamps[1], Instant.now()).abs() < Duration.ofMinutes(1))
    }

    @Test
    @Ignore("WP5: needs the format 2 import (token pass, target folder)")
    fun `shiftTimestampsToNow = false leaves timestamps exactly as recorded`() {
        val sourceHome = tmp.newFolder("source6", ".claude").toPath()
        val cwd = "C:\\Users\\fabio\\demo6"
        val session = writeSourceSession(sourceHome, "session-6", cwd)

        val archive = tmp.root.toPath().resolve("archive6.zip")
        SessionArchive.export(listOf(session), archive, home = sourceHome)

        val targetHome = tmp.newFolder("target6", ".claude").toPath()
        SessionArchive.import(archive, home = targetHome, shiftTimestampsToNow = false)

        val transcript = targetHome.resolve("projects")
            .resolve(ClaudePaths.encodeProjectPath(cwd))
            .resolve("session-6.jsonl")
        assertTrue(Files.readString(transcript).contains("2026-01-01T00:00:00.000Z"))
    }

    @Test
    @Ignore("WP5: needs the format 2 import (token pass, target folder)")
    fun `SKIP policy leaves an already present session untouched`() {
        val sourceHome = tmp.newFolder("source3", ".claude").toPath()
        val cwd = "C:\\Users\\fabio\\demo3"
        val session = writeSourceSession(sourceHome, "session-3", cwd)

        val archive = tmp.root.toPath().resolve("archive3.zip")
        SessionArchive.export(listOf(session), archive, home = sourceHome)

        val targetHome = tmp.newFolder("target3", ".claude").toPath()
        SessionArchive.import(archive, home = targetHome)

        val second = SessionArchive.import(archive, home = targetHome, conflictPolicy = ConflictPolicy.SKIP)
        assertTrue(second.imported.isEmpty())
        assertEquals(listOf("session-3"), second.skipped)
        assertFalse(
            Files.list(targetHome.resolve("projects").resolve(ClaudePaths.encodeProjectPath(cwd))).use { it.count() } > 2,
        )
    }

    @Test
    fun `an imported line is identical to the original except for sessionId, cwd and timestamp`() {
        // Shapes taken from real transcripts, with everything a re-serialization tends to alter:
        // `null` members, HTML-sensitive characters, escaped control characters, non-ASCII text,
        // decimal numbers, a nested snapshot timestamp and a line carrying none of the three fields.
        // `{ROOT}`, `{SID}` and `{TS}` mark the only spots an import may rewrite.
        val templates = listOf(
            """{"parentUuid":null,"isSidechain":false,"cwd":"{ROOT}","sessionId":"{SID}","type":"user","message":{"role":"user","content":"<command-name>/model</command-name> a=b && 'c' è\n\tfine"},"uuid":"u-1","timestamp":"{TS}"}""",
            """{"parentUuid":"u-1","cwd":"{ROOT}\\src\\main","sessionId":"{SID}","type":"assistant","message":{"role":"assistant","content":[{"type":"text","text":"<b>ok</b>"}],"stop_reason":null,"usage":{"input_tokens":12,"cost":1.50}},"uuid":"a-1","timestamp":"{TS}"}""",
            """{"type":"file-history-snapshot","messageId":"u-1","snapshot":{"messageId":"u-1","trackedFileBackups":{},"timestamp":"{TS}"},"isSnapshotUpdate":false}""",
            """{"type":"summary","summary":"a <b> & 'c' = d","leafUuid":null}""",
        )
        val sourceTimestamps = listOf("2026-01-01T10:00:00.000Z", "2026-01-01T10:00:05.250Z", "2026-01-01T10:00:06.000Z", null)
        fun fill(template: String, root: String, id: String, ts: String?) = template
            .replace("{ROOT}", root.asJsonString())
            .replace("{SID}", id)
            .let { if (ts != null) it.replace("{TS}", ts) else it }

        val sourceHome = tmp.newFolder("source8", ".claude").toPath()
        val sourceRoot = "C:\\Users\\fabio\\Demo8"
        val projectDir = sourceHome.resolve("projects").resolve(ClaudePaths.encodeProjectPath(sourceRoot))
        Files.createDirectories(projectDir)
        val originalLines = templates.mapIndexed { i, t -> fill(t, sourceRoot, "session-8", sourceTimestamps[i]) }
        // Claude Code terminates every line, the last one included.
        Files.writeString(projectDir.resolve("session-8.jsonl"), originalLines.joinToString("\n") + "\n")
        val session = SessionScanner.listSessions(sourceHome).single()

        val archive = tmp.root.toPath().resolve("archive8.zip")
        SessionArchive.export(listOf(session), archive, home = sourceHome)

        // Importing twice makes the second copy a duplicate, so all three fields get rewritten. The
        // target is given the way IntelliJ's `project.basePath` spells it, with forward slashes.
        val targetHome = tmp.newFolder("target8", ".claude").toPath()
        val targetRoot = "D:/Work/Demo8"
        SessionArchive.import(archive, home = targetHome, targetProjectPath = targetRoot)
        val duplicated = SessionArchive.import(archive, home = targetHome, targetProjectPath = targetRoot)
            .imported.single()
        assertEquals("duplicated", duplicated.action)

        val importedText = Files.readString(
            targetHome.resolve("projects")
                .resolve(ClaudePaths.encodeProjectPath(targetRoot))
                .resolve("${duplicated.writtenId}.jsonl"),
        )
        assertTrue("the trailing newline must survive the import", importedText.endsWith("\n"))
        val importedLines = importedText.removeSuffix("\n").split("\n")
        assertEquals(originalLines.size, importedLines.size)

        val importedTimestamps = importedLines.map { line ->
            val obj = JsonParser.parseString(line).asJsonObject
            (obj.get("timestamp") ?: obj.getAsJsonObject("snapshot")?.get("timestamp"))?.asString
        }
        templates.forEachIndexed { i, template ->
            // Claude Code on Windows records backslashes: the target is normalized to that form.
            val expected = fill(template, "D:\\Work\\Demo8", duplicated.writtenId, importedTimestamps[i])
            assertEquals("line $i", expected, importedLines[i])
        }

        // The timestamps must really have moved, all by one and the same delta.
        val deltas = sourceTimestamps.zip(importedTimestamps).mapNotNull { (before, after) ->
            if (before == null || after == null) null else Duration.between(Instant.parse(before), Instant.parse(after))
        }
        assertEquals(3, deltas.size)
        assertTrue(deltas.all { it == deltas.first() } && !deltas.first().isZero)
    }

    /**
     * A session the way Claude Code leaves it after editing a file: a conversation line, a
     * `file-history-snapshot` line tracking `a.kt` below the project root and the backup itself
     * under `<home>/file-history/<id>/`.
     */
    private fun writeSessionWithHistory(home: Path, id: String, cwd: String, withBackupFile: Boolean = true): SessionInfo {
        val projectDir = home.resolve("projects").resolve(ClaudePaths.encodeProjectPath(cwd))
        Files.createDirectories(projectDir)
        val file = "$cwd\\a.kt"
        Files.writeString(
            projectDir.resolve("$id.jsonl"),
            listOf(
                """{"parentUuid":null,"isSidechain":false,"type":"user","message":{"role":"user","content":"TOP-SECRET-CONTENT edit a.kt"},"uuid":"u-1","timestamp":"2026-01-01T10:00:00.000Z","cwd":"${cwd.asJsonString()}","sessionId":"$id"}""",
                """{"type":"file-history-snapshot","messageId":"u-1","snapshot":{"messageId":"u-1","trackedFileBackups":{"${file.asJsonString()}":{"backupFileName":"0123456789abcdef@v1","version":1,"backupTime":"2026-01-01T10:00:01.000Z","realParentDir":"${cwd.asJsonString()}"}},"timestamp":"2026-01-01T10:00:01.000Z"},"isSnapshotUpdate":false}""",
                """{"parentUuid":"u-1","isSidechain":false,"type":"assistant","message":{"role":"assistant","content":[{"type":"text","text":"done"}]},"uuid":"a-1","timestamp":"2026-01-01T10:00:05.000Z","cwd":"${cwd.asJsonString()}","sessionId":"$id"}""",
            ).joinToString("\n") + "\n",
        )
        if (withBackupFile) {
            val backups = home.resolve("file-history").resolve(id)
            Files.createDirectories(backups)
            Files.writeString(backups.resolve("0123456789abcdef@v1"), "class A")
        }
        return SessionScanner.listSessions(home).single { it.id == id }
    }

    private fun transcriptOf(home: Path, folder: String, id: String): Path =
        home.resolve("projects").resolve(ClaudePaths.encodeProjectPath(folder)).resolve("$id.jsonl")

    @Test
    fun `the file history travels with the session and follows a duplicated id`() {
        val sourceHome = tmp.newFolder("source9", ".claude").toPath()
        val session = writeSessionWithHistory(sourceHome, "session-9", "C:\\Users\\fabio\\Demo9")
        assertTrue(session.hasFileHistory)
        val archive = tmp.root.toPath().resolve("archive9.zip")
        SessionArchive.export(listOf(session), archive, home = sourceHome)
        assertTrue(SessionArchive.readManifest(archive).single().hasFileHistory)

        val targetHome = tmp.newFolder("target9", ".claude").toPath()
        val target = "D:\\Work\\Demo9"
        SessionArchive.import(archive, home = targetHome, targetProjectPath = target)
        val duplicated = SessionArchive.import(archive, home = targetHome, targetProjectPath = target).imported.single()
        assertEquals("duplicated", duplicated.action)
        assertEquals(1, duplicated.fileHistoryFiles)

        for (id in listOf("session-9", duplicated.writtenId)) {
            assertEquals("class A", Files.readString(targetHome.resolve("file-history").resolve(id).resolve("0123456789abcdef@v1")))
        }
        // The snapshot now tracks the file where it lives on this machine.
        val snapshot = Files.readAllLines(transcriptOf(targetHome, target, duplicated.writtenId))
            .map { JsonParser.parseString(it).asJsonObject }
            .single { it.get("type").asString == "file-history-snapshot" }
            .getAsJsonObject("snapshot").getAsJsonObject("trackedFileBackups")
        assertEquals(setOf("D:\\Work\\Demo9\\a.kt"), snapshot.keySet())
        assertEquals("D:\\Work\\Demo9", snapshot.getAsJsonObject("D:\\Work\\Demo9\\a.kt").get("realParentDir").asString)
    }

    @Test
    @Ignore("WP5: needs the format 2 import (token pass, target folder)")
    fun `REPLACE removes the local session wherever it lives`() {
        val sourceHome = tmp.newFolder("source10", ".claude").toPath()
        val sourceCwd = "C:\\Users\\fabio\\Demo10"
        val session = writeSessionWithHistory(sourceHome, "session-10", sourceCwd)
        val archive = tmp.root.toPath().resolve("archive10.zip")
        SessionArchive.export(listOf(session), archive, home = sourceHome)

        // First restored into its own folder, then replaced into another one.
        val targetHome = tmp.newFolder("target10", ".claude").toPath()
        SessionArchive.import(archive, home = targetHome)
        assertTrue(Files.exists(transcriptOf(targetHome, sourceCwd, "session-10")))

        val target = "D:\\Work\\Demo10"
        val outcome = SessionArchive.import(archive, home = targetHome, conflictPolicy = ConflictPolicy.REPLACE, targetProjectPath = target)
        assertEquals("replaced", outcome.imported.single().action)
        assertEquals("session-10", outcome.imported.single().writtenId)
        assertFalse(Files.exists(transcriptOf(targetHome, sourceCwd, "session-10")))
        assertTrue(Files.exists(transcriptOf(targetHome, target, "session-10")))
        assertEquals(listOf("session-10"), SessionScanner.listSessions(targetHome).map { it.id })
        assertTrue(Files.exists(targetHome.resolve("file-history").resolve("session-10").resolve("0123456789abcdef@v1")))
    }

    @Test
    fun `SKIP sees a session that is already present in another project folder`() {
        val sourceHome = tmp.newFolder("source11", ".claude").toPath()
        val session = writeSourceSession(sourceHome, "session-11", "C:\\Users\\fabio\\Demo11")
        val archive = tmp.root.toPath().resolve("archive11.zip")
        SessionArchive.export(listOf(session), archive, home = sourceHome)

        val targetHome = tmp.newFolder("target11", ".claude").toPath()
        SessionArchive.import(archive, home = targetHome)
        val second = SessionArchive.import(archive, home = targetHome, conflictPolicy = ConflictPolicy.SKIP, targetProjectPath = "D:\\Elsewhere")
        assertEquals(listOf("session-11"), second.skipped)
        assertFalse(Files.exists(transcriptOf(targetHome, "D:\\Elsewhere", "session-11")))
    }

    @Test
    fun `a clean import passes every visibility check`() {
        val sourceHome = tmp.newFolder("source12", ".claude").toPath()
        val session = writeSessionWithHistory(sourceHome, "session-12", "C:\\Users\\fabio\\Demo12")
        val archive = tmp.root.toPath().resolve("archive12.zip")
        SessionArchive.export(listOf(session), archive, home = sourceHome)

        // A folder that really exists on this machine, spelled the way IntelliJ hands it over.
        val folder = tmp.newFolder("work12").toPath().toAbsolutePath().toString().replace('\\', '/')
        val targetHome = tmp.newFolder("target12", ".claude").toPath()
        val outcome = SessionArchive.import(archive, home = targetHome, targetProjectPath = folder)

        assertEquals(emptyList<FailedCheck>(), outcome.failedChecks)
        assertEquals(ClaudePaths.normalizeProjectPath(folder), outcome.imported.single().cwd)
    }

    @Test
    fun `a checkpoint backup missing from the archive is reported by check 13 only`() {
        val sourceHome = tmp.newFolder("source13", ".claude").toPath()
        val session = writeSessionWithHistory(sourceHome, "session-13", "C:\\Users\\fabio\\Demo13", withBackupFile = false)
        val archive = tmp.root.toPath().resolve("archive13.zip")
        SessionArchive.export(listOf(session), archive, home = sourceHome)

        val folder = tmp.newFolder("work13").toPath().toAbsolutePath().toString()
        val targetHome = tmp.newFolder("target13", ".claude").toPath()
        val outcome = SessionArchive.import(archive, home = targetHome, targetProjectPath = folder)

        assertEquals(listOf(13), outcome.failedChecks.map { it.number })
        assertEquals("session-13", outcome.failedChecks.single().sessionId)
    }

    @Test
    fun `the import log records every step but never the conversation`() {
        val sourceHome = tmp.newFolder("source14", ".claude").toPath()
        val session = writeSessionWithHistory(sourceHome, "session-14", "C:\\Users\\fabio\\Demo14")
        val archive = tmp.root.toPath().resolve("archive14.zip")
        SessionArchive.export(listOf(session), archive, home = sourceHome)

        val logFile = tmp.root.toPath().resolve("logs").resolve("import.log")
        val targetHome = tmp.newFolder("target14", ".claude").toPath()
        FileImportLog(logFile).use { log ->
            SessionArchive.import(archive, home = targetHome, targetProjectPath = "D:\\Work\\Demo14", log = log)
        }
        val text = Files.readString(logFile)

        for (key in listOf("environment", "context", "sessions", "diagnosis", "comparison", "summary")) {
            val title = ClaudeSessionsBundle.message("log.section.$key").uppercase()
            assertTrue("section $title", text.contains("== $title =="))
        }
        for (number in 1..ImportDiagnostics.CHECK_COUNT) {
            assertTrue("check #$number", Regex("(OK|KO)  #$number  ").containsMatchIn(text))
        }
        assertTrue(text.contains("transcript.changed.cwd = 2"))
        assertTrue(text.contains("fileHistory.filesWritten = 1"))
        assertFalse(text.contains("TOP-SECRET-CONTENT"))
    }

    @Test
    fun `the manifest lists only the sessions that made it into the archive`() {
        val sourceHome = tmp.newFolder("source15", ".claude").toPath()
        val present = writeSourceSession(sourceHome, "session-15", "C:\\Users\\fabio\\Demo15")
        val missing = present.copy(id = "gone")

        val archive = tmp.root.toPath().resolve("archive15.zip")
        val outcome = SessionArchive.export(listOf(present, missing), archive, home = sourceHome)

        assertEquals(1, outcome.exportedCount)
        assertEquals(1, outcome.warnings.size)
        assertEquals(listOf("session-15"), SessionArchive.readManifest(archive).map { it.id })
    }

    @Test
    @Ignore("WP5: needs the format 2 import (token pass, target folder)")
    fun `a failure while importing one session does not stop the others`() {
        val sourceHome = tmp.newFolder("source16", ".claude").toPath()
        val sessions = listOf(
            writeSourceSession(sourceHome, "session-16a", "C:\\Users\\fabio\\Demo16"),
            writeSourceSession(sourceHome, "session-16b", "C:\\Users\\fabio\\Demo16"),
        )
        val archive = tmp.root.toPath().resolve("archive16.zip")
        SessionArchive.export(sessions, archive, home = sourceHome)

        // A folder where the first transcript has to go makes writing it fail.
        val targetHome = tmp.newFolder("target16", ".claude").toPath()
        Files.createDirectories(transcriptOf(targetHome, "C:\\Users\\fabio\\Demo16", "session-16a").resolve("blocker"))
        val existing = SessionScanner.existingTranscripts(targetHome)
        assertTrue(existing.isEmpty())

        val outcome = SessionArchive.import(archive, home = targetHome)
        assertEquals(listOf("session-16a"), outcome.failures.map { it.sessionId })
        assertEquals(listOf("session-16b"), outcome.imported.map { it.writtenId })
    }

    @Test
    fun `a session has the same name in the export and in the import table`() {
        val sourceHome = tmp.newFolder("source17", ".claude").toPath()
        val cwd = "C:\\Users\\fabio\\Demo17"
        val projectDir = sourceHome.resolve("projects").resolve(ClaudePaths.encodeProjectPath(cwd))
        Files.createDirectories(projectDir)
        Files.write(
            projectDir.resolve("session-17.jsonl"),
            listOf(
                """{"type":"user","message":{"role":"user","content":"<command-name>/model</command-name>"},"timestamp":"2026-01-01T00:00:00.000Z","cwd":"${cwd.asJsonString()}","sessionId":"session-17"}""",
                """{"type":"user","message":{"role":"user","content":"Migrate the build to Gradle 9"},"timestamp":"2026-01-01T00:00:01.000Z","cwd":"${cwd.asJsonString()}","sessionId":"session-17"}""",
            ),
        )
        val exported = SessionScanner.listSessions(sourceHome).single()
        assertEquals("Migrate the build to Gradle 9", exported.displayName)

        val archive = tmp.root.toPath().resolve("archive17.zip")
        SessionArchive.export(listOf(exported), archive, home = sourceHome)
        assertEquals(exported.displayName, SessionArchive.readManifest(archive).single().displayName)

        // An archive written by an earlier build stores the raw preview it computed back then: the
        // import table still picks the name from the archived transcript.
        val older = tmp.root.toPath().resolve("archive17-older.zip")
        java.util.zip.ZipFile(archive.toFile()).use { source ->
            java.util.zip.ZipOutputStream(Files.newOutputStream(older)).use { target ->
                for (entry in source.entries()) {
                    target.putNextEntry(java.util.zip.ZipEntry(entry.name))
                    val bytes = source.getInputStream(entry).readBytes()
                    target.write(
                        if (entry.name == "manifest.json") {
                            String(bytes, Charsets.UTF_8)
                                .replace("Migrate the build to Gradle 9", "<command-name>/model</command-name>")
                                .toByteArray(Charsets.UTF_8)
                        } else {
                            bytes
                        },
                    )
                    target.closeEntry()
                }
            }
        }
        assertEquals(exported.displayName, SessionArchive.readManifest(older).single().displayName)
    }

    @Test
    fun `an archive made by another tool is refused by name instead of failing on a null`() {
        // The shape of a GitHub Copilot sessions export: a manifest.json of its own, no Claude Code fields.
        val manifest = """
            {"formatVersion":1,"producer":"IntelliJ Github Copilot sessions","sourceHome":"C:\\Users\\x\\.copilot",
             "sessions":[{"id":"abc","name":"n","cwd":"c:\\x","turnCount":2,"hasState":true,"inStore":true}]}
        """.trimIndent()
        val foreign = tmp.root.toPath().resolve("archive18-foreign.zip")
        java.util.zip.ZipOutputStream(Files.newOutputStream(foreign)).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry("manifest.json"))
            zip.write(manifest.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }

        val error = org.junit.Assert.assertThrows(java.io.IOException::class.java) { SessionArchive.readManifest(foreign) }
        assertTrue(error.message, error.message!!.contains("IntelliJ Github Copilot sessions"))
    }
    /** Reads one entry of [archive] as UTF-8 text, straight from the ZIP, before any import. */
    private fun readArchiveEntry(archive: Path, entryName: String): String =
        java.util.zip.ZipFile(archive.toFile()).use { zip ->
            val entry = zip.getEntry(entryName) ?: error("no entry $entryName in $archive")
            zip.getInputStream(entry).bufferedReader(Charsets.UTF_8).readText()
        }

    @Test
    fun `export removes the account email, git identity and scratch path from the archive itself`() {
        val sourceHome = tmp.newFolder("source18", ".claude").toPath()
        val cwd = "C:\\Users\\fabio\\Demo18"
        val projectDir = sourceHome.resolve("projects").resolve(ClaudePaths.encodeProjectPath(cwd))
        Files.createDirectories(projectDir)
        Files.write(
            projectDir.resolve("session-18.jsonl"),
            listOf(
                """{"type":"user","message":{"role":"user","content":"hello"},"timestamp":"2026-01-01T00:00:00.000Z","cwd":"${cwd.asJsonString()}","sessionId":"session-18"}""",
                """{"type":"attachment","attachment":{"type":"session_context","context":{"userEmail":"The user's email address is fabio.pelliccia@example.com.","gitStatus":"Git user: Fabio Pelliccia\nCurrent branch: develops"}},"rendered":[{"content":"<system-reminder>\n# userEmail\nThe user's email address is fabio.pelliccia@example.com.\n# gitStatus\nGit user: Fabio Pelliccia\nCurrent branch: develops\n</system-reminder>"}],"sessionId":"session-18"}""",
                """{"type":"attachment","attachment":{"type":"environment","snapshot":{"workingDirectory":"${cwd.asJsonString()}","scratchpadDirectory":"C:\\Users\\fabio\\AppData\\Local\\Temp\\claude\\x\\scratchpad","platform":"win32"}},"sessionId":"session-18"}""",
            ),
        )
        val session = SessionScanner.listSessions(sourceHome).single()

        val archive = tmp.root.toPath().resolve("archive18.zip")
        val outcome = SessionArchive.export(listOf(session), archive, home = sourceHome)
        assertEquals(1, outcome.redactedIdentityLines)
        assertEquals(1, outcome.redactedScratchpadPaths)

        // Read the ZIP directly: the archive itself must already be clean, whether or not it is
        // ever imported, and by whom.
        val archivedText = readArchiveEntry(archive, "sessions/session-18/transcript.jsonl")
        assertFalse(archivedText.contains("fabio.pelliccia"))
        assertFalse(archivedText.contains("Fabio Pelliccia"))
        assertTrue("the conversation itself is untouched", archivedText.contains("\"hello\""))

        val lines = archivedText.trim().split("\n").map { JsonParser.parseString(it).asJsonObject }
        val context = lines[1].getAsJsonObject("attachment").getAsJsonObject("context")
        assertFalse(context.has("userEmail"))
        assertFalse(context.has("gitStatus"))
        val rendered = lines[1].getAsJsonArray("rendered").single().asJsonObject.get("content").asString
        assertFalse(rendered.contains("fabio.pelliccia"))
        assertTrue("the block itself survives, only the identity text is gone", rendered.contains("<system-reminder>"))

        val envSnapshot = lines[2].getAsJsonObject("attachment").getAsJsonObject("snapshot")
        assertFalse(envSnapshot.get("scratchpadDirectory").asString.contains("fabio"))
        // The project folder is not stripped but anonymized: a token the import renders for its own machine.
        assertEquals("{{SP:ROOT:win}}", envSnapshot.get("workingDirectory").asString)
    }

    @Test
    fun `a subagent transcript in the auxiliary folder is redacted the same way`() {
        val sourceHome = tmp.newFolder("source19", ".claude").toPath()
        val session = writeSourceSession(sourceHome, "session-19", "C:\\Users\\fabio\\Demo19")
        val subagentsDir = sourceHome.resolve("projects").resolve(session.projectFolderName)
            .resolve("session-19").resolve("subagents")
        Files.createDirectories(subagentsDir)
        Files.writeString(
            subagentsDir.resolve("agent-1.jsonl"),
            """{"type":"attachment","attachment":{"type":"session_context","context":{"userEmail":"fabio.pelliccia@example.com"}}}""" + "\n",
        )

        val archive = tmp.root.toPath().resolve("archive19.zip")
        SessionArchive.export(listOf(session), archive, home = sourceHome)

        val archived = readArchiveEntry(archive, "sessions/session-19/aux/subagents/agent-1.jsonl")
        assertFalse(archived.contains("fabio.pelliccia@example.com"))
    }

    @Test
    fun `export with no producer info writes neither optional manifest field`() {
        val sourceHome = tmp.newFolder("source-p1", ".claude").toPath()
        val session = writeSourceSession(sourceHome, "session-p1", "C:\\Users\\fabio\\DemoP1")
        val archive = tmp.root.toPath().resolve("archive-p1.zip")
        SessionArchive.export(listOf(session), archive, home = sourceHome)

        val manifest = SessionArchive.readArchiveManifest(archive)
        assertEquals(null, manifest.producerComponent)
        assertEquals(null, manifest.producerVersion)
        val raw = java.util.zip.ZipFile(archive.toFile()).use { zip ->
            zip.getInputStream(zip.getEntry("manifest.json")).readBytes().toString(Charsets.UTF_8)
        }
        assertTrue(!raw.contains("producerComponent"))
        assertTrue(!raw.contains("producerVersion"))
        assertTrue(raw.contains("\"producer\": \"${SessionArchive.PRODUCER}\""))
    }

    @Test
    fun `export records the producer component and version in the manifest`() {
        val sourceHome = tmp.newFolder("source-p2", ".claude").toPath()
        val session = writeSourceSession(sourceHome, "session-p2", "C:\\Users\\fabio\\DemoP2")
        val archive = tmp.root.toPath().resolve("archive-p2.zip")
        SessionArchive.export(listOf(session), archive, home = sourceHome, producer = ProducerInfo("cli", "1.2.3"))

        val manifest = SessionArchive.readArchiveManifest(archive)
        assertEquals("cli", manifest.producerComponent)
        assertEquals("1.2.3", manifest.producerVersion)
        assertEquals(SessionArchive.PRODUCER, manifest.producer)
    }

    @Test
    fun `the manifest no longer records the source machine's home path`() {
        val sourceHome = tmp.newFolder("source20-fabio", ".claude").toPath()
        val session = writeSourceSession(sourceHome, "session-20", "C:\\Users\\fabio\\Demo20")
        val archive = tmp.root.toPath().resolve("archive20.zip")
        SessionArchive.export(listOf(session), archive, home = sourceHome)

        assertEquals(null, SessionArchive.readArchiveManifest(archive).sourceHome)
    }

    @Test
    fun `export to import across two machines and two users leaves no trace of either and no conflict`() {
        // "PC_1", used by alice: both her OS home folder and her project path name her.
        val sourceHome = tmp.newFolder("pc1-alice-home", ".claude").toPath()
        val sourceCwd = "C:\\Users\\alice\\Projects\\Shared"
        val projectDir = sourceHome.resolve("projects").resolve(ClaudePaths.encodeProjectPath(sourceCwd))
        Files.createDirectories(projectDir)
        Files.writeString(
            projectDir.resolve("session-21.jsonl"),
            listOf(
                """{"type":"user","message":{"role":"user","content":"add a test"},"timestamp":"2020-06-01T09:00:00.000Z","cwd":"${sourceCwd.asJsonString()}","sessionId":"session-21"}""",
                """{"type":"attachment","attachment":{"type":"session_context","context":{"userEmail":"alice@example.com is the address","gitStatus":"Git user: Alice Example"}},"rendered":[{"content":"alice@example.com is the address / Git user: Alice Example"}],"timestamp":"2020-06-01T09:00:00.500Z","sessionId":"session-21"}""",
                """{"type":"attachment","attachment":{"type":"environment","snapshot":{"workingDirectory":"${sourceCwd.asJsonString()}","additionalWorkingDirectories":["${"$sourceCwd\\vendor".asJsonString()}"],"scratchpadDirectory":"C:\\Users\\alice\\AppData\\Local\\Temp\\claude\\y\\scratchpad"}},"timestamp":"2020-06-01T09:00:01.000Z","sessionId":"session-21"}""",
                """{"type":"assistant","message":{"role":"assistant","content":[{"type":"text","text":"done"}]},"timestamp":"2020-06-01T09:00:05.000Z","sessionId":"session-21"}""",
            ).joinToString("\n") + "\n",
        )
        val session = SessionScanner.listSessions(sourceHome).single()

        val archive = tmp.root.toPath().resolve("archive21.zip")
        SessionArchive.export(listOf(session), archive, home = sourceHome)

        // "PC_2", used by bob: a fresh, unrelated home, on another drive, under another account.
        val targetHome = tmp.newFolder("pc2-bob-home", ".claude").toPath()
        val targetCwd = "D:\\Users\\bob\\Projects\\Shared"
        val outcome = SessionArchive.import(archive, home = targetHome, targetProjectPath = targetCwd)

        val written = outcome.imported.single()
        assertEquals("new", written.action) // no id already existed on PC_2: nothing to conflict with.
        // Check #8 (the cwd folder exists on disk) is orthogonal to this test - "D:\..." is a stand-in
        // path, not a real folder - and is exercised on its own elsewhere; what matters here is that
        // nothing failed to parse and no path or identity from either machine survived (checks 2, 11).
        assertEquals(emptyList<FailedCheck>(), outcome.failedChecks.filter { it.number != 8 })

        val transcript = transcriptOf(targetHome, targetCwd, "session-21")
        val lines = Files.readAllLines(transcript).map { JsonParser.parseString(it).asJsonObject }

        // The conversation itself is untouched.
        assertTrue(Files.readString(transcript).contains("\"add a test\""))

        // Alice's identity is gone, not just remapped.
        val context = lines[1].getAsJsonObject("attachment").getAsJsonObject("context")
        assertFalse(context.has("userEmail"))
        assertFalse(context.has("gitStatus"))
        val rendered = lines[1].getAsJsonArray("rendered").single().asJsonObject.get("content").asString
        assertFalse(rendered.contains("alice@example.com"))

        // Both machines' project folders are gone from what PC_2 now has; bob's is there instead.
        val envSnapshot = lines[2].getAsJsonObject("attachment").getAsJsonObject("snapshot")
        assertEquals(targetCwd, envSnapshot.get("workingDirectory").asString)
        assertEquals("$targetCwd\\vendor", envSnapshot.getAsJsonArray("additionalWorkingDirectories").single().asString)
        assertFalse(envSnapshot.get("scratchpadDirectory").asString.contains("alice"))
        assertEquals(targetCwd, lines[0].get("cwd").asString)

        // The session reads as one that just happened on PC_2, not as a six-year-old one from PC_1.
        val lastTimestamp = lines.mapNotNull { it.get("timestamp")?.asString }.map { Instant.parse(it) }.max()
        assertTrue(Duration.between(lastTimestamp, Instant.now()).abs() < Duration.ofMinutes(1))
    }
}
