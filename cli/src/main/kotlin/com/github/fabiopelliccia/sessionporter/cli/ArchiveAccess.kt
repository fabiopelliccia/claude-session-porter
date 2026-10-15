package com.github.fabiopelliccia.sessionporter.cli

import com.github.fabiopelliccia.claudesessionsimportexport.core.SessionArchive
import com.github.fabiopelliccia.claudesessionsimportexport.core.UnusableArchiveException
import com.github.fabiopelliccia.claudesessionsimportexport.core.UnusableArchiveException.Reason
import java.io.IOException
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.Path

/** An archive the user named, opened far enough to know what it holds. */
internal class OpenedArchive(val path: Path, val manifest: SessionArchive.ArchiveManifest)

/** Opening an archive and sorting what can go wrong with it into the exit codes. */
internal object ArchiveAccess {

    /**
     * Reads the manifest of the archive at [raw]. A file that is not there is an environment problem
     * (exit 4); one that is there but is not an archive this CLI can read - not a ZIP, no manifest, made by
     * another tool, from a newer format, or one that names files outside the Claude home - is an archive
     * problem (exit 3).
     */
    fun open(context: CommandContext, raw: String): OpenedArchive {
        val path = context.path(raw)
        if (!Files.isRegularFile(path)) {
            throw CliException(ExitCode.ENVIRONMENT, "archiveNotFound", CliBundle.message("error.archiveNotFound", path.toAbsolutePath()))
        }
        try {
            return OpenedArchive(path, SessionArchive.readArchiveManifest(path))
        } catch (e: UnusableArchiveException) {
            throw classify(e)
        } catch (e: FileSystemException) {
            // The file is there but cannot be read (permissions, a lock): a problem of the machine, not of the archive.
            throw e
        } catch (_: IOException) {
            // Anything else that fails while reading it, for instance a damaged ZIP structure.
            throw CliException(ExitCode.ARCHIVE, "invalidArchive", CliBundle.message("error.invalidArchive"))
        }
    }

    /** What the CLI reports for an archive the core refused, decided by the kind of refusal and never by its message. */
    fun classify(e: UnusableArchiveException): CliException = when (e.reason) {
        Reason.UNSAFE -> CliException(ExitCode.ARCHIVE, "unsafeArchive", e.message ?: CliBundle.message("error.invalidArchive"))
        Reason.TOO_NEW -> CliException(ExitCode.ARCHIVE, "archiveTooNew", e.message ?: CliBundle.message("error.invalidArchive"))
        // A ZIP library's own message is of no use to a person.
        Reason.NOT_A_ZIP -> CliException(ExitCode.ARCHIVE, "invalidArchive", CliBundle.message("error.invalidArchive"))
        Reason.NO_MANIFEST, Reason.UNREADABLE_MANIFEST, Reason.NOT_CLAUDE ->
            CliException(ExitCode.ARCHIVE, "invalidArchive", e.message ?: CliBundle.message("error.invalidArchive"))
    }
}
