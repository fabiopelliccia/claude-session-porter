package com.github.fabiopelliccia.sessionporter.cli

import com.github.fabiopelliccia.claudesessionsimportexport.core.ClaudePaths
import com.github.fabiopelliccia.claudesessionsimportexport.core.FileImportLog
import com.github.fabiopelliccia.claudesessionsimportexport.core.LogFiles
import com.github.fabiopelliccia.claudesessionsimportexport.core.TransferProgress
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.nio.file.Paths
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Progress and steps on stderr, only with `--verbose`; never anything out of a conversation, only names and counts. */
internal class CliLog(private val verbose: Boolean, private val err: java.io.PrintStream) {

    fun info(message: String) {
        if (verbose) err.println("[session-porter] $message")
    }

    /** What the core reports while it works, as the same kind of line. */
    val progress = TransferProgress { fraction, text -> info("${(fraction * 100).toInt()}% $text") }
}

/** What every command needs: its arguments, the process it runs in, and the places the global options point to. */
internal class CommandContext(
    val line: CommandLine,
    val environment: CliEnvironment,
    val log: CliLog,
) {
    /** A path given on the command line, with a Git Bash or WSL drive turned into a Windows one. */
    fun path(raw: String): Path = try {
        Paths.get(PathInput.toNativePath(raw, environment.isWindows))
    } catch (_: InvalidPathException) {
        throw usageError("invalidPath", "error.usage.invalidPath", raw)
    }

    /** The Claude home: `--home`, else what the core resolves (`claude.home`, `CLAUDE_CONFIG_DIR`, `~/.claude`). */
    val home: Path by lazy {
        line.value(CommandSpecs.HOME)?.let(::path) ?: ClaudePaths.resolveHome()
    }

    /** [home], after making sure it is there: every command but `doctor` needs the folder to exist. */
    fun requireHome(): Path {
        if (!Files.isDirectory(home)) {
            throw CliException(ExitCode.ENVIRONMENT, "homeNotFound", CliBundle.message("error.homeNotFound", home.toAbsolutePath()))
        }
        return home
    }

    /** `--log-dir`, else the folder of the operating system. */
    val logDirectory: Path by lazy {
        line.value("--log-dir")?.let(::path) ?: LogLocations.defaultDirectory(environment)
    }

    /** A project folder given on the command line, as Claude Code records it. */
    fun projectPath(raw: String): String = PathInput.normalize(raw, environment.isWindows, workingDirectory = System.getProperty("user.dir"))

    fun now(): LocalDateTime = LocalDateTime.now(environment.clock)
}

/** Where the logs go and how they are named. */
internal object LogLocations {

    /** How many logs of each kind are kept. */
    const val KEEP = 20

    private val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

    /** The default folder: the one each operating system keeps logs of an application in. */
    fun defaultDirectory(environment: CliEnvironment): Path {
        val userHome = environment.userHome
        return when {
            environment.isWindows -> {
                val localAppData = environment.getenv("LOCALAPPDATA")?.takeIf { it.isNotBlank() }
                    ?: Paths.get(userHome, "AppData", "Local").toString()
                Paths.get(localAppData, "SessionPorter", "logs")
            }
            environment.isMac -> Paths.get(userHome, "Library", "Logs", "SessionPorter")
            else -> {
                val stateHome = environment.getenv("XDG_STATE_HOME")?.takeIf { it.isNotBlank() }
                    ?: Paths.get(userHome, ".local", "state").toString()
                Paths.get(stateHome, "session-porter", "logs")
            }
        }
    }

    /** `<prefix>-yyyyMMdd-HHmmss.log`, the name [LogFiles.rotate] sorts by. */
    fun fileName(prefix: String, time: LocalDateTime): String = "$prefix-${STAMP.format(time)}.log"

    /**
     * Runs [block] with the log file of this operation and then keeps only the last [KEEP] logs of its kind.
     * The log is best effort, as the core's own: a folder that cannot be written is no reason to fail.
     */
    fun <T> withLog(directory: Path, prefix: String, time: LocalDateTime, block: (FileImportLog, Path) -> T): T {
        val file = directory.resolve(fileName(prefix, time))
        try {
            return FileImportLog(file).use { block(it, file) }
        } finally {
            LogFiles.rotate(directory, prefix, KEEP)
        }
    }

    /** [file] when the log really got written, `null` when the folder could not be used. */
    fun existing(file: Path): String? = file.takeIf { Files.isRegularFile(it) }?.toAbsolutePath()?.toString()
}
