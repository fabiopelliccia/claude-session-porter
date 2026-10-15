package com.github.fabiopelliccia.sessionporter.cli

import com.github.fabiopelliccia.claudesessionsimportexport.core.ClaudeSessionsBundle
import com.github.fabiopelliccia.claudesessionsimportexport.core.ExportOutcome
import com.github.fabiopelliccia.claudesessionsimportexport.core.ImportLog
import com.github.fabiopelliccia.claudesessionsimportexport.core.ProducerInfo
import com.github.fabiopelliccia.claudesessionsimportexport.core.SessionArchive
import com.github.fabiopelliccia.claudesessionsimportexport.core.SessionInfo
import com.github.fabiopelliccia.claudesessionsimportexport.core.SessionScanner
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * `export (--ids a,b | --ids-file <f|-> | --all | --project <path>) --out <file.zip|dir> [--force] [--strict-privacy]`.
 *
 * The archive is written next to its destination under a temporary name and put in place only once it
 * is known to be acceptable: a destination that exists is never touched without `--force`, and a
 * strict privacy check that fails leaves no archive behind.
 */
internal class ExportCommand(private val context: CommandContext) {

    private val line = context.line

    fun run(): CommandResult {
        val selector = selectorOption()
        val out = line.value("--out") ?: throw usageError("outRequired", "error.usage.outRequired")
        val destination = destinationOf(out)
        val force = line.flag("--force")
        if (Files.exists(destination) && !force) throw outputExists(destination)

        val home = context.requireHome()
        val sessions = selectSessions(selector, SessionScanner.listSessions(home))
        if (sessions.isEmpty()) throw usageError("noSessions", "error.usage.noSessions")
        context.log.info("exporting ${sessions.size} sessions to $destination")

        val strict = line.flag("--strict-privacy")
        val startedAt = System.currentTimeMillis()
        val parent = destination.toAbsolutePath().parent
        Files.createDirectories(parent)
        // The core writes through a temporary file of its own and moves it over the path it is given: that path is
        // itself a temporary one, so nothing exists at the destination until the whole export is accepted.
        val pending = parent.resolve(".session-porter-${UUID.randomUUID()}.zip.part")
        try {
            val outcome = SessionArchive.export(
                sessions, pending, home, context.log.progress, ProducerInfo(CliVersion.COMPONENT, CliVersion.value),
            )
            val refused = strict && outcome.hasPrivacyFindings
            if (!refused) moveIntoPlace(pending, destination, force)
            val size = if (refused) null else Files.size(destination)
            val logFile = writeLog(selector, sessions.size, outcome, refused, System.currentTimeMillis() - startedAt)
            return resultOf(destination, size, sessions.size, outcome, refused, logFile)
        } finally {
            Files.deleteIfExists(pending)
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

    private fun moveIntoPlace(pending: Path, destination: Path, force: Boolean) {
        if (force) {
            Files.move(pending, destination, StandardCopyOption.REPLACE_EXISTING)
        } else {
            // Without REPLACE_EXISTING a file that appeared in the meantime makes this fail instead of being overwritten.
            try {
                Files.move(pending, destination)
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
    private fun writeLog(selector: String, requested: Int, outcome: ExportOutcome, refused: Boolean, durationMs: Long): String? =
        LogLocations.withLog(context.logDirectory, "export", context.now()) { log, file ->
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
            LogLocations.existing(file)
        }

    private fun writeEnvironment(log: ImportLog) {
        log.section(ClaudeSessionsBundle.message("log.section.environment"))
        log.kv("cli.version", CliVersion.value)
        log.kv("java.version", System.getProperty("java.version"))
        log.kv("os.name", System.getProperty("os.name"))
        log.kv("os.arch", System.getProperty("os.arch"))
    }

    private companion object {
        val SELECTORS = listOf("--ids", "--ids-file", "--all", "--project")
        val FILE_STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
    }
}
