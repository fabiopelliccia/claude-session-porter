package com.github.fabiopelliccia.sessionporter.cli

import com.github.fabiopelliccia.claudesessionsimportexport.core.ConflictPolicy
import com.github.fabiopelliccia.claudesessionsimportexport.core.ImportEnvironment
import com.github.fabiopelliccia.claudesessionsimportexport.core.ImportOutcome
import com.github.fabiopelliccia.claudesessionsimportexport.core.SessionArchive
import com.github.fabiopelliccia.claudesessionsimportexport.core.SessionLabeler
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Paths

/**
 * `import <zip> --target <folder> [--ids ...] [--policy skip|replace|duplicate] [--no-shift]
 * [--keep-original-folder] [--log-dir <dir>]`.
 *
 * A format 2 archive holds no paths of its own, so `--target` (the project folder the sessions are
 * attached to) is required. A format 1 archive keeps the folder each session was recorded in unless
 * `--target` says otherwise; `--keep-original-folder` states that choice and is refused for format 2.
 */
internal class ImportCommand(private val context: CommandContext) {

    private val line = context.line

    fun run(): CommandResult {
        val policy = policyOption()
        val archive = ArchiveAccess.open(context, line.positionals.single())
        val manifest = archive.manifest
        val target = targetOption(manifest.isAnonymized)
        val ids = idsOption(manifest)
        val home = context.requireHome()
        // Every path option is read before anything is written: a bad one must not be found out afterwards.
        val logDirectory = context.logDirectory

        val warnings = mutableListOf<String>()
        if (target != null && !isExistingFolder(target)) warnings += CliBundle.message("warning.targetNotFound", target)

        context.log.info("importing ${ids?.size ?: manifest.sessions.size} sessions (policy ${policy.name}) into $home")
        val shift = !line.flag("--no-shift")
        val logged = LogLocations.withLog(logDirectory, "import", context.now()) { log ->
            try {
                SessionArchive.import(
                    archive = archive.path,
                    sessionIds = ids,
                    conflictPolicy = policy,
                    targetProjectPath = target,
                    shiftTimestampsToNow = shift,
                    home = home,
                    progress = context.log.progress,
                    log = log,
                    environment = ImportEnvironment(pluginVersion = CliVersion.value, ideBuild = null, productName = CliVersion.PRODUCT_NAME),
                    userHome = context.environment.userHome,
                    labeler = SessionLabeler.ID,
                )
            } catch (e: Exception) {
                // The log is what a person reads to find out what went wrong, so it gets the failure too.
                log.failure("import failed", e)
                throw e
            }
        }
        return resultOf(logged.result, policy, target, logged.logFile, warnings)
    }

    // ----------------------------------------------------------------------------------- options --

    private fun policyOption(): ConflictPolicy {
        val text = line.value("--policy") ?: return ConflictPolicy.DUPLICATE
        return ConflictPolicy.entries.firstOrNull { it.name.equals(text.trim(), ignoreCase = true) }
            ?: throw usageError("invalidPolicy", "error.usage.invalidPolicy", text)
    }

    /** The project folder to attach to, as Claude Code records it; `null` keeps the original folder of a format 1 archive. */
    private fun targetOption(anonymized: Boolean): String? {
        val raw = line.value("--target")
        val keepOriginal = line.flag("--keep-original-folder")
        if (anonymized) {
            if (keepOriginal) throw usageError("keepOriginalFolderNotAllowed", "error.usage.keepOriginalNeedsFormat1")
            if (raw == null) throw usageError("targetRequired", "error.usage.targetRequired")
        } else if (keepOriginal && raw != null) {
            throw usageError("targetAndKeepOriginal", "error.usage.targetAndKeepOriginal")
        }
        return raw?.let(context::projectPath)
    }

    /** The sessions to import, `null` for all of them; an id the archive does not hold is a mistake. */
    private fun idsOption(manifest: SessionArchive.ArchiveManifest): Set<String>? {
        if (!line.isGiven("--ids")) return null
        val ids = SessionIds.fromOption(context)
        // `--ids ""` is a selection of nothing, not of everything: with REPLACE the difference is deleted sessions.
        if (ids.isEmpty()) throw usageError("noSessions", "error.usage.noSessionsToImport")
        val unknown = ids.filter { id -> manifest.sessions.none { it.id == id } }
        if (unknown.isNotEmpty()) throw usageError("unknownSessions", "error.usage.unknownArchiveSessions", unknown.joinToString(", "))
        return ids.toSet()
    }

    private fun isExistingFolder(target: String): Boolean = try {
        Files.isDirectory(Paths.get(target))
    } catch (_: InvalidPathException) {
        false
    }

    // ------------------------------------------------------------------------------------ result --

    private fun resultOf(
        outcome: ImportOutcome,
        policy: ConflictPolicy,
        target: String?,
        logFile: String?,
        cliWarnings: List<String>,
    ): CommandResult {
        val imported = outcome.imported.map {
            linkedMapOf(
                "sourceId" to it.sourceId,
                "writtenId" to it.writtenId,
                "action" to it.action,
                "projectFolderName" to it.projectFolderName,
                "cwd" to it.cwd,
                "messageCount" to it.messageCount,
                "fileHistoryFiles" to it.fileHistoryFiles,
                "resumeCommand" to "claude --resume ${it.writtenId}",
            )
        }
        val data = linkedMapOf<String, Any?>(
            "imported" to imported,
            "skipped" to outcome.skipped,
            "failures" to outcome.failures.map { linkedMapOf("sessionId" to it.sessionId, "message" to it.message) },
            "failedChecks" to outcome.failedChecks.map { linkedMapOf("sessionId" to it.sessionId, "number" to it.number, "detail" to it.detail) },
            "policy" to policy.name.lowercase(),
            "logFile" to logFile,
            "target" to target,
        )
        val problem = if (outcome.failures.isEmpty() && outcome.failedChecks.isEmpty()) {
            null
        } else {
            CliError("partialImport", CliBundle.message("error.partialImport", outcome.failures.size, outcome.failedChecks.size))
        }
        return CommandResult(
            data = data,
            humanLines = humanLines(outcome, target, logFile),
            warnings = cliWarnings + outcome.warnings,
            exitCode = if (problem == null) ExitCode.OK else ExitCode.PARTIAL,
            error = problem,
        )
    }

    private fun humanLines(outcome: ImportOutcome, target: String?, logFile: String?): List<String> {
        val lines = mutableListOf(CliBundle.message("import.done", outcome.imported.size, outcome.skipped.size, outcome.failures.size))
        for (session in outcome.imported) {
            lines += CliBundle.message("import.session", session.action, session.sourceId, session.writtenId, session.messageCount)
        }
        if (outcome.imported.isNotEmpty()) {
            lines += if (target != null) CliBundle.message("import.resumeFrom", target) else CliBundle.message("import.resume")
        }
        for (failure in outcome.failures) lines += CliBundle.message("import.failure", failure.sessionId, failure.message)
        for (check in outcome.failedChecks) lines += CliBundle.message("import.failedCheck", check.number, check.sessionId, check.detail)
        if (logFile != null) lines += CliBundle.message("import.log", logFile)
        return lines
    }
}
