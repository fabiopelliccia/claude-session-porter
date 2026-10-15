package com.github.fabiopelliccia.sessionporter.cli

import com.github.fabiopelliccia.claudesessionsimportexport.core.ClaudePaths
import com.github.fabiopelliccia.claudesessionsimportexport.core.FileImportLog
import com.github.fabiopelliccia.claudesessionsimportexport.core.LogFiles
import com.github.fabiopelliccia.claudesessionsimportexport.core.TransferProgress
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.LinkOption
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
    /** Temporary files of this run, which are removed if it is interrupted. */
    val pending: PendingFiles = PendingFiles(),
) {
    /**
     * A path given on the command line, with a Git Bash or WSL drive turned into a Windows one. An empty
     * value is refused: it would otherwise mean "the current folder", which nobody types on purpose.
     */
    fun path(raw: String): Path {
        if (raw.isBlank()) throw usageError("invalidPath", "error.usage.invalidPath", raw)
        return try {
            Paths.get(PathInput.toNativePath(raw, environment.isWindows))
        } catch (_: InvalidPathException) {
            throw usageError("invalidPath", "error.usage.invalidPath", raw)
        }
    }

    /**
     * The Claude home: `--home`, else `claude.home`, else `CLAUDE_CONFIG_DIR`, else `~/.claude`. The same order
     * the core uses, but read from the [environment] so that a Git Bash path in the variable is understood too.
     */
    val home: Path by lazy {
        line.value(CommandSpecs.HOME)?.let(::path)
            ?: (System.getProperty(ClaudePaths.SYSTEM_PROPERTY) ?: environment.getenv(ClaudePaths.ENV_VAR))
                ?.takeIf { it.isNotBlank() }?.let(::path)
            ?: Paths.get(environment.userHome, ".claude")
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

    /**
     * A project folder given on the command line, as Claude Code records it. A folder that exists is written
     * the way the disk spells it (the long name for an 8.3 one such as `PELLIC~1`, the case it was created with).
     */
    fun projectPath(raw: String): String {
        if (raw.isBlank()) throw usageError("invalidPath", "error.usage.invalidPath", raw)
        val normalized = PathInput.normalize(raw, environment.isWindows, workingDirectory = System.getProperty("user.dir"))
        return spelledByDisk(normalized)
    }

    private fun spelledByDisk(path: String): String = try {
        val folder = Paths.get(path)
        // Links are not followed: the folder is the one named, not wherever it points to.
        if (Files.isDirectory(folder)) ClaudePaths.normalizeProjectPath(folder.toRealPath(LinkOption.NOFOLLOW_LINKS).toString()) else path
    } catch (_: IOException) {
        path
    } catch (_: InvalidPathException) {
        path
    }

    fun now(): LocalDateTime = LocalDateTime.now(environment.clock)
}

/** Where the logs go and how they are named. */
internal object LogLocations {

    /** How many logs of each kind are kept. */
    const val KEEP = 20

    private const val MAX_SAME_SECOND_LOGS = 1000

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

    /** `<prefix>-yyyyMMdd-HHmmss.log`, or with `-2`, `-3`... for the logs of the same second: the names [LogFiles.rotate] knows. */
    fun fileName(prefix: String, time: LocalDateTime, counter: Int = 1): String {
        val suffix = if (counter > 1) "-$counter" else ""
        return "$prefix-${STAMP.format(time)}$suffix.log"
    }

    /** What [withLog] returns: what the block returned, and the log file when it really got written. */
    class Logged<T>(val result: T, val logFile: String?)

    /**
     * Runs [block] with the log of this operation and then keeps only the last [KEEP] logs of its kind. A log that
     * already exists (another run in the same second) is never overwritten: this one gets the next free name.
     * The log is best effort, as the core's own: a folder that cannot be written is no reason to fail.
     */
    fun <T> withLog(directory: Path, prefix: String, time: LocalDateTime, block: (FileImportLog) -> T): Logged<T> {
        val file = claimFile(directory, prefix, time)
        val result = try {
            FileImportLog(file).use(block)
        } finally {
            LogFiles.rotate(directory, prefix, KEEP)
        }
        // Looked at last, after the rotation, so that it says whether this log is still there.
        return Logged(result, existing(file))
    }

    /** Creates the empty log file under the first name nobody has used, so that two runs never share one. */
    private fun claimFile(directory: Path, prefix: String, time: LocalDateTime): Path {
        var candidate = directory.resolve(fileName(prefix, time))
        try {
            Files.createDirectories(directory)
            var counter = 1
            while (counter < MAX_SAME_SECOND_LOGS) {
                candidate = directory.resolve(fileName(prefix, time, counter))
                try {
                    Files.createFile(candidate)
                    return candidate
                } catch (_: FileAlreadyExistsException) {
                    counter++
                }
            }
        } catch (_: IOException) {
            // The folder cannot be used: the log will not be written, which is all right.
        }
        return candidate
    }

    /** [file] when the log really got written, `null` when the folder could not be used. */
    fun existing(file: Path): String? = file.takeIf { Files.isRegularFile(it) }?.toAbsolutePath()?.toString()
}
