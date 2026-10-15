package com.github.fabiopelliccia.sessionporter.cli

import com.github.fabiopelliccia.claudesessionsimportexport.core.ClaudeSessionsBundle
import com.github.fabiopelliccia.claudesessionsimportexport.core.SessionArchive
import java.io.IOException
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
     * problem (exit 3), with the core's own message.
     */
    fun open(context: CommandContext, raw: String): OpenedArchive {
        val path = context.path(raw)
        if (!Files.isRegularFile(path)) {
            throw CliException(ExitCode.ENVIRONMENT, "archiveNotFound", CliBundle.message("error.archiveNotFound", path.toAbsolutePath()))
        }
        try {
            return OpenedArchive(path, SessionArchive.readArchiveManifest(path))
        } catch (e: IOException) {
            val code = if (e.message == ClaudeSessionsBundle.message("error.unsafeArchive")) "unsafeArchive" else "invalidArchive"
            throw CliException(ExitCode.ARCHIVE, code, e.message ?: CliBundle.message("error.invalidArchive"))
        }
    }
}
