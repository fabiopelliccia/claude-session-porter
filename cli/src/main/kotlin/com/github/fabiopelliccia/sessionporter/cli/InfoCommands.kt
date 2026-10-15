package com.github.fabiopelliccia.sessionporter.cli

import com.github.fabiopelliccia.claudesessionsimportexport.core.ClaudePaths
import com.github.fabiopelliccia.claudesessionsimportexport.core.SessionArchive
import com.github.fabiopelliccia.claudesessionsimportexport.core.SessionScanner
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

/** `version`: which CLI this is and which archive format it writes. */
internal object VersionCommand {

    fun run(): CommandResult {
        val data = linkedMapOf<String, Any?>(
            "version" to CliVersion.value,
            "formatVersion" to SessionArchive.FORMAT_VERSION,
            "java" to System.getProperty("java.version"),
        )
        return CommandResult(data, listOf(CliBundle.message("version.line", CliVersion.value, SessionArchive.FORMAT_VERSION)))
    }
}

/** `help`, `--help`, `-h`. The syntax is the same in every language, so it is not part of the bundle. */
internal object HelpCommand {

    val usage: List<String> = listOf(
        "session-porter [global options] <command> [options]",
        "",
        "Commands:",
        "  list [--project <path>] [--since <ISO|7d>] [--limit N]",
        "  inspect <zip>",
        "  export (--ids a,b | --ids-file <file|-> | --all | --project <path>) --out <file.zip|dir> [--force] [--strict-privacy] [--log-dir <dir>]",
        "  import <zip> --target <folder> [--ids a,b] [--policy skip|replace|duplicate] [--no-shift] [--keep-original-folder] [--log-dir <dir>]",
        "  doctor [--log-dir <dir>]",
        "  version",
        "",
        "Global options:",
        "  --home <dir>    Claude home (default: claude.home, CLAUDE_CONFIG_DIR, ~/.claude)",
        "  --json          print one JSON document on standard output",
        "  --lang <tag>    language of the messages (default: SESSION_PORTER_LANG, then the system language)",
        "  --verbose       print what is going on to standard error",
        "",
        "Exit codes: 0 ok, 2 usage, 3 invalid archive, 4 I/O or environment, 5 partial failure, 6 privacy check failed, 10 internal error.",
    )

    fun run(): CommandResult = CommandResult(linkedMapOf("usage" to usage), usage)
}

/** `doctor`: whether this machine can run the other commands. */
internal class DoctorCommand(private val context: CommandContext) {

    private class Check(val id: String, val ok: Boolean, val detail: String)

    fun run(): CommandResult {
        val home = context.home
        val projects = ClaudePaths.projectsDir(home)
        val homeExists = Files.isDirectory(home)
        val projectsExists = Files.isDirectory(projects)
        // `projects/` is created by the first session, so a Claude home without it is fine as long as the home itself can be written.
        val projectsWritable = if (projectsExists) Files.isWritable(projects) else homeExists && Files.isWritable(home)
        val logDirectory = context.logDirectory
        val logWritable = canWrite(logDirectory)
        val javaVersion = Runtime.version().feature()

        val checks = listOf(
            Check("java", javaVersion >= MINIMUM_JAVA, System.getProperty("java.version")),
            Check("home", homeExists, home.toAbsolutePath().toString()),
            Check("projects", projectsWritable, projects.toAbsolutePath().toString()),
            Check("logs", logWritable, logDirectory.toAbsolutePath().toString()),
        )

        val data = linkedMapOf<String, Any?>(
            "cli" to linkedMapOf("version" to CliVersion.value, "formatVersion" to SessionArchive.FORMAT_VERSION),
            "java" to linkedMapOf(
                "version" to System.getProperty("java.version"),
                "vendor" to System.getProperty("java.vendor"),
                "home" to System.getProperty("java.home"),
            ),
            "os" to linkedMapOf("name" to System.getProperty("os.name"), "arch" to System.getProperty("os.arch")),
            "home" to linkedMapOf(
                "path" to home.toAbsolutePath().toString(),
                "exists" to homeExists,
                "projectsDir" to projects.toAbsolutePath().toString(),
                "projectsDirExists" to projectsExists,
                "projectsDirWritable" to projectsWritable,
                "sessionCount" to if (projectsExists) SessionScanner.existingTranscripts(home).size else 0,
            ),
            "logDir" to linkedMapOf("path" to logDirectory.toAbsolutePath().toString(), "writable" to logWritable),
            "language" to Locale.getDefault(Locale.Category.DISPLAY).toLanguageTag(),
            "checks" to checks.map { linkedMapOf("id" to it.id, "ok" to it.ok, "detail" to it.detail) },
        )

        val lines = checks.map { CliBundle.message(if (it.ok) "doctor.ok" else "doctor.failed", CliBundle.message("doctor.check.${it.id}"), it.detail) } +
            CliBundle.message("version.line", CliVersion.value, SessionArchive.FORMAT_VERSION)
        val failed = checks.filterNot { it.ok }
        if (failed.isEmpty()) return CommandResult(data, lines)
        val problem = CliError("environmentProblem", CliBundle.message("error.environmentProblem", failed.joinToString(", ") { it.id }))
        return CommandResult(data, lines, exitCode = ExitCode.ENVIRONMENT, error = problem)
    }

    /** Whether [directory] can be written, or created: the nearest folder that exists above it must be writable. */
    private fun canWrite(directory: Path): Boolean {
        var existing: Path? = directory.toAbsolutePath()
        while (existing != null && !Files.exists(existing)) existing = existing.parent
        return existing != null && Files.isDirectory(existing) && Files.isWritable(existing)
    }

    private companion object {
        const val MINIMUM_JAVA = 21
    }
}
