package com.github.fabiopelliccia.sessionporter.cli

import com.github.fabiopelliccia.claudesessionsimportexport.core.ClaudeSessionsBundle
import com.github.fabiopelliccia.claudesessionsimportexport.core.ExportOutcome
import com.github.fabiopelliccia.claudesessionsimportexport.core.ImportLog
import com.github.fabiopelliccia.claudesessionsimportexport.core.ProducerInfo
import com.github.fabiopelliccia.claudesessionsimportexport.core.SessionArchive
import com.github.fabiopelliccia.claudesessionsimportexport.core.SessionInfo
import com.github.fabiopelliccia.claudesessionsimportexport.core.SessionLabeler
import com.github.fabiopelliccia.claudesessionsimportexport.core.SessionScanner
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.NotDirectoryException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * `export (--ids a,b | --ids-file <f|-> | --all | --project <path>) --out <file.zip|dir> [--force] [--strict-privacy]`.
 *
 * The archive is built in a temporary folder of the system and put at its destination only once it is known
 * to be acceptable: a destination that exists is never touched without `--force`, a strict privacy check that
 * fails leaves no archive and no folder behind, and an interrupted run removes its temporary files.
 */
internal class ExportCommand(private val context: CommandContext) {

    private val line = context.line

    fun run(): CommandResult {
        val selector = selectorOption()
        val out = line.value("--out") ?: throw usageError("outRequired", "error.usage.outRequired")
        val destination = destinationOf(out)
        // Every path option is read before any work is done: a bad one must not be found out afterwards.
        val logDirectory = context.logDirectory
        val force = line.flag("--force")
        if (Files.exists(destination) && !force) throw outputExists(destination)
        requireFolderCanBeCreated(destination.toAbsolutePath().parent)

        val home = context.requireHome()
        val sessions = selectSessions(selector, SessionScanner.listSessions(home))
        if (sessions.isEmpty()) throw usageError("noSessions", "error.usage.noSessions")
        context.log.info("exporting ${sessions.size} sessions to $destination")

        val strict = line.flag("--strict-privacy")
        val startedAt = System.currentTimeMillis()
        // The archive is built in a folder of its own in the system temp folder, which no sync client watches: an
        // archive that the strict check then refuses never shows up in a OneDrive or Dropbox folder, not even for a
        // moment. The core builds it through a temporary file of its own in the same folder, so one removal takes both.
        val workFolder = context.pending.track(Files.createTempDirectory(context.environment.tempDirectory, "session-porter-"))
        try {
            val pending = workFolder.resolve(PENDING_ARCHIVE)
            val outcome = SessionArchive.export(
                sessions, pending, home, context.log.progress, ProducerInfo(CliVersion.COMPONENT, CliVersion.value),
                labeler = SessionLabeler.ID,
            )
            val refused = strict && outcome.hasPrivacyFindings
            val size = if (refused) null else publish(pending, destination, force)
            val logFile = writeLog(logDirectory, selector, sessions.size, outcome, refused, System.currentTimeMillis() - startedAt)
            return resultOf(destination, size, sessions.size, outcome, refused, logFile)
        } finally {
            context.pending.discard(workFolder)
        }
    }

    // ----------------------------------------------------------------------------- selection --

    private fun selectorOption(): String {
        val given = SELECTORS.filter(line::isGiven)
        if (given.isEmpty()) throw usageError("selectorRequired", "error.usage.selectorRequired")
        if (given.size > 1) throw usageError("selectorConflict", "error.usage.selectorConflict", given.joinToString(", "))
        return given.single()
    }

    private fun selectSessions(selector: String, all: List<SessionInfo>): List<SessionInfo> = when (selector) {
        "--all" -> all
        "--project" -> {
            val project = context.projectPath(line.value("--project")!!)
            all.filter { SessionViews.isInProject(it, project) }
        }
        "--ids" -> byId(SessionIds.fromOption(context), all)
        else -> byId(SessionIds.fromFile(context, line.value("--ids-file")!!), all)
    }

    /** The sessions with these ids, in the order given; an id this home does not have is a mistake, not something to skip. */
    private fun byId(ids: List<String>, all: List<SessionInfo>): List<SessionInfo> {
        val unknown = ids.filter { id -> all.none { it.id == id } }
        if (unknown.isNotEmpty()) throw usageError("unknownSessions", "error.usage.unknownSessions", unknown.joinToString(", "))
        return ids.map { id -> all.first { it.id == id } }
    }

    // ----------------------------------------------------------------------------- destination --

    /** `--out` is a folder (an existing one, or a name ending in a separator) or a `.zip` file. */
    private fun destinationOf(raw: String): Path {
        val path = context.path(raw)
        val isFolder = raw.endsWith("/") || raw.endsWith("\\") || Files.isDirectory(path)
        val destination = if (isFolder) path.resolve(defaultFileName()) else path
        if (!destination.fileName.toString().endsWith(".${SessionArchive.ARCHIVE_EXTENSION}", ignoreCase = true)) {
            throw usageError("outNotZip", "error.usage.outNotZip", raw)
        }
        if (Files.isDirectory(destination)) throw usageError("outIsFolder", "error.usage.outIsFolder", destination.toAbsolutePath())
        return destination
    }

    private fun defaultFileName(): String = "claude-sessions-${FILE_STAMP.format(context.now())}.zip"

    private fun outputExists(destination: Path) =
        CliException(ExitCode.ENVIRONMENT, "outputExists", CliBundle.message("error.outputExists", destination.toAbsolutePath()))

    /** The folders of the destination that do not exist yet can only be made if what is in their way is a folder. */
    private fun requireFolderCanBeCreated(folder: Path) {
        var existing: Path? = folder
        while (existing != null && !Files.exists(existing)) existing = existing.parent
        if (existing != null && !Files.isDirectory(existing)) throw NotDirectoryException(existing.toString())
    }

    /**
     * Puts the finished archive at [destination] and returns its size. This is the one step that makes anything
     * appear there, and it runs as a whole or not at all (see [PendingFiles.commit]).
     *
     * The archive first goes to a temporary name in the folder of the destination, and only then takes its final
     * name: that second step is a rename inside one folder, so a half-copied archive (the temp folder is often on
     * another volume) never carries the name of the real one. The folders that had to be created are removed again
     * when something fails.
     */
    private fun publish(pending: Path, destination: Path, force: Boolean): Long = context.pending.commit {
        val folder = destination.toAbsolutePath().parent
        val createdFolders = createMissingFolders(folder)
        val staged = context.pending.track(folder.resolve(".session-porter-${UUID.randomUUID()}.zip.tmp"))
        try {
            moveOrCopy(pending, staged)
            moveIntoPlace(staged, destination, force)
            Files.size(destination)
        } catch (e: Throwable) {
            createdFolders.asReversed().forEach { runCatching { Files.deleteIfExists(it) } }
            throw e
        } finally {
            context.pending.discard(staged)
        }
    }

    /** Creates the folders of [folder] that are missing and returns them, the outermost first; the ones that were there stay out. */
    private fun createMissingFolders(folder: Path): List<Path> {
        val missing = generateSequence(folder) { it.parent }.takeWhile { !Files.exists(it) }.toList().asReversed()
        val created = mutableListOf<Path>()
        try {
            for (path in missing) {
                Files.createDirectory(path)
                created.add(path)
            }
        } catch (e: IOException) {
            created.asReversed().forEach { runCatching { Files.deleteIfExists(it) } }
            throw e
        }
        return created
    }

    /** A rename when the two are on one volume; otherwise a copy, after which the original is of no use. */
    private fun moveOrCopy(source: Path, target: Path) {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: IOException) {
            Files.copy(source, target)
            Files.deleteIfExists(source)
        }
    }

    private fun moveIntoPlace(staged: Path, destination: Path, force: Boolean) {
        if (force) {
            Files.move(staged, destination, StandardCopyOption.REPLACE_EXISTING)
        } else {
            // Without REPLACE_EXISTING a file that appeared in the meantime makes this fail instead of being overwritten.
            try {
                Files.move(staged, destination)
            } catch (_: FileAlreadyExistsException) {
                throw outputExists(destination)
            }
        }
    }

    // ---------------------------------------------------------------------------------- result --

    private fun resultOf(
        destination: Path,
        sizeBytes: Long?,
        requested: Int,
        outcome: ExportOutcome,
        refused: Boolean,
        logFile: String?,
    ): CommandResult {
        val data = linkedMapOf<String, Any?>(
            "archive" to if (refused) null else destination.toAbsolutePath().toString(),
            "sizeBytes" to sizeBytes,
            "requestedCount" to requested,
            "exportedCount" to outcome.exportedCount,
            "unreadableFiles" to outcome.unreadableFiles,
            "redactedIdentityLines" to outcome.redactedIdentityLines,
            "redactedScratchpadPaths" to outcome.redactedScratchpadPaths,
            "partialLinesDropped" to outcome.partialLinesDropped,
            "identityValuesScrubbed" to outcome.identityValuesScrubbed,
            "anonymization" to linkedMapOf(
                "replacedByKind" to outcome.replacedByKind,
                "replacedByStyle" to outcome.replacedByStyle,
                "residualUserNameHits" to outcome.residualUserNameHits,
                "residualEntries" to outcome.residualEntries,
                "binaryEntriesWithHits" to outcome.binaryEntriesWithHits,
                "keyCollisions" to outcome.keyCollisions,
            ),
            "privacyFindings" to outcome.hasPrivacyFindings,
            "logFile" to logFile,
        )
        val warnings = outcome.warnings.toMutableList()
        if (outcome.hasPrivacyFindings && !refused) warnings += CliBundle.message("warning.privacyFindings")

        val lines = humanLines(destination, outcome, refused)
        val problem = when {
            refused -> CliError("privacyFindings", CliBundle.message("error.privacyFindings", outcome.residualUserNameHits, outcome.binaryEntriesWithHits.size))
            outcome.exportedCount < requested || outcome.unreadableFiles > 0 ->
                CliError("partialExport", CliBundle.message("error.partialExport", outcome.exportedCount, requested, outcome.unreadableFiles))
            else -> null
        }
        val exitCode = when {
            refused -> ExitCode.PRIVACY
            problem != null -> ExitCode.PARTIAL
            else -> ExitCode.OK
        }
        return CommandResult(data, lines, warnings, exitCode, problem)
    }

    private fun humanLines(destination: Path, outcome: ExportOutcome, refused: Boolean): List<String> {
        val lines = mutableListOf<String>()
        if (!refused) lines += CliBundle.message("export.done", outcome.exportedCount, destination.toAbsolutePath())
        val replaced = outcome.replacedByKind.values.sum()
        lines += CliBundle.message("export.anonymized", replaced, outcome.replacedByKind.entries.joinToString(", ") { "${it.key} ${it.value}" })
        if (outcome.residualUserNameHits > 0) {
            lines += CliBundle.message("export.residual", outcome.residualUserNameHits, outcome.residualEntries.joinToString(", "))
        }
        if (outcome.binaryEntriesWithHits.isNotEmpty()) {
            lines += CliBundle.message("export.binaryHits", outcome.binaryEntriesWithHits.joinToString(", "))
        }
        return lines
    }

    // -------------------------------------------------------------------------------------- log --

    /** `export-*.log`: counts only, never a title or a line of a conversation. Returns the file when it could be written. */
    private fun writeLog(logDirectory: Path, selector: String, requested: Int, outcome: ExportOutcome, refused: Boolean, durationMs: Long): String? =
        LogLocations.withLog(logDirectory, "export", context.now()) { log ->
            writeEnvironment(log)
            log.section(ClaudeSessionsBundle.message("log.section.context"))
            log.kv("selector", selector)
            log.kv("strictPrivacy", line.flag("--strict-privacy"))
            log.kv("force", line.flag("--force"))
            log.section(ClaudeSessionsBundle.message("log.section.summary"))
            log.kv("requestedSessions", requested)
            log.kv("exportedSessions", outcome.exportedCount)
            log.kv("unreadableFiles", outcome.unreadableFiles)
            log.kv("warnings", outcome.warnings.size)
            log.kv("partialLinesDropped", outcome.partialLinesDropped)
            log.kv("redactedIdentityLines", outcome.redactedIdentityLines)
            log.kv("redactedScratchpadPaths", outcome.redactedScratchpadPaths)
            log.kv("identityValuesScrubbed", outcome.identityValuesScrubbed)
            log.kv("replacedByKind", outcome.replacedByKind)
            log.kv("replacedByStyle", outcome.replacedByStyle)
            log.kv("keyCollisions", outcome.keyCollisions)
            log.kv("residualUserNameHits", outcome.residualUserNameHits)
            log.kv("residualEntries", outcome.residualEntries.size)
            log.kv("binaryEntriesWithHits", outcome.binaryEntriesWithHits.size)
            log.kv("privacyFindings", outcome.hasPrivacyFindings)
            log.kv("archiveWritten", !refused)
            log.kv("durationMs", durationMs)
        }.logFile

    private fun writeEnvironment(log: ImportLog) {
        log.section(ClaudeSessionsBundle.message("log.section.environment"))
        log.kv("cli.version", CliVersion.value)
        log.kv("java.version", System.getProperty("java.version"))
        log.kv("os.name", System.getProperty("os.name"))
        log.kv("os.arch", System.getProperty("os.arch"))
    }

    private companion object {
        const val PENDING_ARCHIVE = "archive.zip.part"
        val SELECTORS = listOf("--ids", "--ids-file", "--all", "--project")
        val FILE_STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
    }
}
