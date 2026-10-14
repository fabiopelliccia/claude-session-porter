package com.github.fabiopelliccia.claudesessionsimportexport.core

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Duration
import java.time.Instant
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.streams.asSequence

/**
 * Answers "why doesn't the imported session show up in `claude --resume`" without access to the
 * machine that produced the archive.
 *
 * [diagnose] re-reads everything from disk - never from the objects the import just built in
 * memory - and writes an explicit `OK`/`KO` outcome for every check of [CHECK_COUNT], numbered
 * exactly as `documentation/TASK.md` lists them: the number is the reference used when a log is
 * read without access to the machine. It then compares the imported sessions against the most
 * recent session that was already in the target project folder. It is invoked once, after every
 * session has been imported, and is free of any IntelliJ dependency so it can be unit tested.
 */
class ImportDiagnostics(private val home: Path) {

    companion object {
        const val CHECK_COUNT = 13

        /** Tolerance for "the session ends at import time": the import itself takes a moment. */
        private val TIME_TOLERANCE: Duration = Duration.ofMinutes(1)
    }

    /** One imported session and what the import was asked to do with it. */
    data class Target(
        val session: ImportedSession,
        /** Root the session was recorded from on the source machine. */
        val sourceRoot: String?,
        /** Folder the session was attached to, already normalized; `null` when it kept its own. */
        val attachedFolder: String?,
        val timestampsShifted: Boolean,
    )

    data class Check(val number: Int, val ok: Boolean, val detail: String? = null) {
        val description: String get() = ClaudeSessionsBundle.message("diagnosis.check.$number")
    }

    /**
     * Runs every check for [targets] and returns one [FailedCheck] per `KO`. [nativeTranscript] is
     * the most recent session of the target folder, captured by the caller *before* the import.
     */
    fun diagnose(
        targets: List<Target>,
        nativeTranscript: Path?,
        importedAt: Instant,
        log: ImportLog,
    ): List<FailedCheck> {
        log.section(ClaudeSessionsBundle.message("log.section.diagnosis"))
        val failures = ArrayList<FailedCheck>()
        for (target in targets) {
            // The id, not the display name: that may be a preview of the conversation itself.
            val sessionLog = log.child("Session ${target.session.writtenId}")
            for (check in runChecks(target, importedAt)) {
                val status = if (check.ok) "OK" else "KO"
                val suffix = check.detail?.let { " - $it" }.orEmpty()
                sessionLog.line("$status  #${check.number}  ${check.description}$suffix")
                if (!check.ok) {
                    failures.add(FailedCheck(target.session.writtenId, check.number, check.detail ?: check.description))
                }
            }
        }

        log.section(ClaudeSessionsBundle.message("log.section.comparison"))
        compareWithNative(targets.map { it.session }, nativeTranscript, log)
        return failures
    }

    /** The checks of one session, in order; exposed so the notification can name the failed ones. */
    fun runChecks(target: Target, importedAt: Instant): List<Check> {
        val session = target.session
        val projectDir = ClaudePaths.projectsDir(home).resolve(session.projectFolderName)
        val transcript = projectDir.resolve("${session.writtenId}.jsonl")
        val text = runCatching { Files.readString(transcript, StandardCharsets.UTF_8) }.getOrNull()
        val lines = text?.split('\n')?.map { it.removeSuffix("\r") }?.filter { it.isNotBlank() }.orEmpty()
        val parsed = lines.map { line -> runCatching { JsonParser.parseString(line).asJsonObject }.getOrNull() }
        val objects = parsed.filterNotNull()
        val cwds = objects.mapNotNull { it.string("cwd") }
        val checks = ArrayList<Check>()

        checks += Check(1, text != null && lines.isNotEmpty(), transcript.toString().takeIf { text == null || lines.isEmpty() })

        val invalid = parsed.count { it == null }
        checks += Check(2, invalid == 0, "invalid lines = $invalid".takeIf { invalid > 0 })

        val foreignIds = objects.flatMap { obj -> TranscriptRewriter.ID_KEYS.mapNotNull { obj.string(it) } }
            .filter { it != session.writtenId }.distinct()
        checks += Check(3, foreignIds.isEmpty(), "other ids = $foreignIds".takeIf { foreignIds.isNotEmpty() })

        // The decisive one: `claude --resume` only reads the folder named after the directory it is
        // started from, so a transcript anywhere else exists on disk and is never listed.
        val firstCwd = cwds.firstOrNull()
        val expectedFolder = (target.attachedFolder ?: firstCwd)?.let { ClaudePaths.encodeProjectPath(it) }
        val folderOk = expectedFolder != null && expectedFolder.equals(projectDir.name, ignoreCase = true)
        checks += Check(4, folderOk, "folder = ${projectDir.name}, expected = $expectedFolder".takeIf { !folderOk })

        checks += Check(5, !firstCwd.isNullOrBlank())

        val attached = target.attachedFolder
        val outside = if (attached == null) {
            emptyList()
        } else {
            cwds.filter { it != attached && !it.startsWith(attached + separatorOf(attached)) }.distinct()
        }
        checks += Check(6, outside.isEmpty(), outside.firstOrNull()?.let { diffHex(it, attached.orEmpty()) })

        val notCanonical = cwds.filter { ClaudePaths.normalizeProjectPath(it) != it }.distinct()
        checks += Check(7, cwds.isNotEmpty() && notCanonical.isEmpty(), notCanonical.firstOrNull()?.let { "cwd = $it" })

        val folderExists = firstCwd != null && runCatching { Paths.get(firstCwd).isDirectory() }.getOrDefault(false)
        checks += Check(8, folderExists, firstCwd?.takeIf { !folderExists })

        val conversation = objects.count { obj ->
            obj.string("type") in setOf("user", "assistant") && obj.get("isSidechain")?.takeIf { it.isJsonPrimitive }?.asBoolean != true
        }
        checks += Check(9, conversation > 0, "messages = $conversation".takeIf { conversation == 0 })

        val endsWithNewline = text?.endsWith("\n") == true
        checks += Check(10, endsWithNewline)

        val residual = countResidualPaths(objects, target.sourceRoot, attached)
        checks += Check(11, residual == 0, "residual occurrences = $residual".takeIf { residual > 0 })

        val timestamps = objects.mapNotNull { it.string("timestamp")?.let(TimestampShift::parse) }
        val latest = timestamps.maxOrNull()
        val inFuture = latest != null && latest.isAfter(importedAt.plus(TIME_TOLERANCE))
        val notRecent = target.timestampsShifted && latest != null &&
            Duration.between(latest, importedAt).abs() > TIME_TOLERANCE
        checks += Check(12, !inFuture && !notRecent, "latest = $latest, import = $importedAt".takeIf { inFuture || notRecent })

        val missingBackups = missingBackupFiles(objects, session.writtenId)
        checks += Check(13, missingBackups.isEmpty(), "missing = ${missingBackups.size}, e.g. ${missingBackups.firstOrNull()}".takeIf { missingBackups.isNotEmpty() })

        return checks
    }

    /**
     * Counts the machine facing values that still point below [sourceRoot] after the rewrite: the
     * working directories, the file history paths and an `environment` attachment's own working
     * directories, never the conversation. Nothing counts when the session stayed in its own folder,
     * or was attached below the folder it came from.
     */
    private fun countResidualPaths(objects: List<JsonObject>, sourceRoot: String?, attached: String?): Int {
        if (sourceRoot.isNullOrEmpty() || attached == null) return 0
        if (attached.startsWith(ClaudePaths.normalizeProjectPath(sourceRoot), ignoreCase = true)) return 0
        fun stale(value: String?) = value != null && value.startsWith(sourceRoot, ignoreCase = true)
        return objects.sumOf { obj ->
            var hits = 0
            if (stale(obj.string("cwd"))) hits++
            if (stale(obj.string("trackingPath"))) hits++
            obj.obj("backup")?.let { if (stale(it.string("realParentDir"))) hits++ }
            obj.obj("snapshot")?.obj("trackedFileBackups")?.entrySet()?.forEach { (path, value) ->
                if (stale(path)) hits++
                if (value.isJsonObject && stale(value.asJsonObject.string("realParentDir"))) hits++
            }
            val attachment = obj.obj("attachment")?.takeIf { it.string("type") == "environment" }
            attachment?.obj("snapshot")?.let { envSnapshot ->
                if (stale(envSnapshot.string("workingDirectory"))) hits++
                envSnapshot.get("additionalWorkingDirectories")?.takeIf { it.isJsonArray }?.asJsonArray?.forEach {
                    if (it.isJsonPrimitive && it.asJsonPrimitive.isString && stale(it.asString)) hits++
                }
            }
            hits
        }
    }

    /** Backup files the snapshots refer to that are not in `<home>/file-history/<id>/`. */
    private fun missingBackupFiles(objects: List<JsonObject>, sessionId: String): List<String> {
        val dir = ClaudePaths.fileHistoryDir(home).resolve(sessionId)
        val referenced = objects.flatMap { obj ->
            val fromSnapshot = obj.obj("snapshot")?.obj("trackedFileBackups")?.entrySet()
                ?.mapNotNull { (_, value) -> value.takeIf { it.isJsonObject }?.asJsonObject?.string("backupFileName") }
                .orEmpty()
            fromSnapshot + listOfNotNull(obj.obj("backup")?.string("backupFileName"))
        }.distinct()
        return referenced.filter { !dir.resolve(it).isRegularFile() }
    }

    /**
     * Compares every imported session against [nativeTranscript], the most recent session that was
     * already in the target folder: a different Claude Code version or a different key set on the
     * first conversation line is the quickest hint of an archive the local CLI does not expect.
     */
    private fun compareWithNative(imported: List<ImportedSession>, nativeTranscript: Path?, log: ImportLog) {
        if (nativeTranscript == null || !nativeTranscript.isRegularFile()) {
            log.line("no native session available for comparison (the target folder held no other session)")
            return
        }
        val native = firstConversationLine(nativeTranscript)
        log.kv("native.sessionId", nativeTranscript.fileName.toString().removeSuffix(".jsonl"))
        log.kv("native.version", native?.string("version"))
        log.kv("native.cwd", native?.string("cwd"))
        log.kv("native.keys", native?.keySet()?.sorted())
        for (session in imported) {
            val transcript = ClaudePaths.projectsDir(home).resolve(session.projectFolderName).resolve("${session.writtenId}.jsonl")
            val line = firstConversationLine(transcript)
            val nativeKeys = native?.keySet().orEmpty()
            val importedKeys = line?.keySet().orEmpty()
            log.line(
                "vs ${session.writtenId}: version = ${line?.string("version")}; " +
                    "only in native = ${(nativeKeys - importedKeys).sorted()}; only in imported = ${(importedKeys - nativeKeys).sorted()}",
            )
            log.kv("imported.${session.writtenId}.cwd", line?.string("cwd"))
        }
    }

    private fun firstConversationLine(transcript: Path): JsonObject? = runCatching {
        Files.newBufferedReader(transcript, StandardCharsets.UTF_8).use { reader ->
            reader.lineSequence().asSequence()
                .filter { it.isNotBlank() }
                .mapNotNull { runCatching { JsonParser.parseString(it).asJsonObject }.getOrNull() }
                .firstOrNull { it.string("type") == "user" && it.has("cwd") }
        }
    }.getOrNull()

    private fun separatorOf(path: String): Char = if (ClaudePaths.isWindowsStyle(path)) '\\' else '/'

    /** Prints both strings' code points in hex starting at the first character that differs. */
    private fun diffHex(written: String, expected: String): String {
        var i = 0
        while (i < written.length && i < expected.length && written[i] == expected[i]) i++
        val hexA = written.drop(i).map { "%04x".format(it.code) }.joinToString(" ")
        val hexB = expected.drop(i).map { "%04x".format(it.code) }.joinToString(" ")
        return "written=\"$written\" (from char $i: $hexA), expected=\"$expected\" (from char $i: $hexB)"
    }

    private fun JsonObject.string(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

    private fun JsonObject.obj(key: String): JsonObject? = get(key)?.takeIf { it.isJsonObject }?.asJsonObject
}
