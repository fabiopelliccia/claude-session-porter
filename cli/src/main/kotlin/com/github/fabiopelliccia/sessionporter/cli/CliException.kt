package com.github.fabiopelliccia.sessionporter.cli

/** The exit codes of the CLI, see the README of the plugin and `documentation/PLAN.md` §2. */
internal object ExitCode {
    const val OK = 0
    const val USAGE = 2
    const val ARCHIVE = 3
    const val ENVIRONMENT = 4
    const val PARTIAL = 5
    const val PRIVACY = 6
    const val INTERNAL = 10

    /** The command was stopped (Ctrl+C, SIGTERM): the number a shell gives to a process ended by SIGINT. */
    const val INTERRUPTED = 130
}

/**
 * A failure the CLI reports by itself: it ends the command with [exitCode] and, in JSON mode, an
 * `error` whose [code] never changes between versions. [data] is set when the command got far enough
 * to have something to show (a refused strict export still reports its counts).
 */
internal class CliException(
    val exitCode: Int,
    val code: String,
    message: String,
    val data: Map<String, Any?>? = null,
) : Exception(message)

/** A wrong command line: an unknown option, a missing value, a combination that makes no sense. */
internal fun usageError(code: String, messageKey: String, vararg arguments: Any?): CliException =
    CliException(ExitCode.USAGE, code, CliBundle.message(messageKey, *arguments))
