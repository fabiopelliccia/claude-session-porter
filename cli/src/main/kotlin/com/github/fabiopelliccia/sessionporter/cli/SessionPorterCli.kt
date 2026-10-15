package com.github.fabiopelliccia.sessionporter.cli

import com.github.fabiopelliccia.claudesessionsimportexport.core.UnusableArchiveException
import java.io.IOException
import java.io.UncheckedIOException
import java.util.IllformedLocaleException
import java.util.Locale

/**
 * Runs one command line and returns its exit code. Nothing here exits the process or reads the
 * real standard streams: everything comes from the [CliEnvironment], which is what lets the tests run
 * whole commands in-process.
 *
 * In `--json` mode standard output holds exactly one document, the envelope, for a success and for
 * every failure alike; in plain mode the result goes to standard output and warnings and errors to
 * standard error. Standard error also carries the progress, but only with `--verbose`.
 *
 * An instance runs one command line. If the process is stopped meanwhile, [interrupt] (called from a
 * shutdown hook) removes the temporary files and prints the envelope of the interruption instead of the result.
 */
internal class SessionPorterCli(private val environment: CliEnvironment) {

    private val pending = PendingFiles()

    /** Held while deciding who prints the one result: the command that finished, or the interruption. */
    private val outputLock = Any()
    private var outputClaimed = false

    // What the interruption needs to know, whenever it comes.
    @Volatile private var jsonMode = false
    @Volatile private var commandName: String? = null

    fun run(args: List<String>): Int {
        // The two global options are read before the command line is parsed, so that a mistake in it is
        // reported in the language and in the form (JSON or plain) that were asked for.
        val options = args.takeWhile { it != "--" }
        val json = CommandSpecs.JSON in options
        jsonMode = json
        val log = CliLog(CommandSpecs.VERBOSE in options, environment.err)

        // The language is global to the JVM: put back what it was, so that a process that runs the CLI twice
        // (the tests do) does not carry one run's language into the next.
        val previousLanguage = Locale.getDefault(Locale.Category.DISPLAY)
        try {
            val result = try {
                applyLanguage(valueOf(options, CommandSpecs.LANG))
                val line = try {
                    ArgumentParser.parse(args)
                } catch (e: CliException) {
                    // A mistake after the command word is still a mistake of that command.
                    commandName = ArgumentParser.commandWord(args)
                    throw e
                }
                commandName = line.command
                dispatch(CommandContext(line, environment, log, pending))
            } catch (e: CommandInterrupted) {
                // The interruption has printed its own result (or is about to): there is nothing left to say.
                return ExitCode.INTERRUPTED
            } catch (e: CliException) {
                failure(e.exitCode, e.code, e.message ?: e.code, e.data)
            } catch (e: UnusableArchiveException) {
                val problem = ArchiveAccess.classify(e)
                failure(problem.exitCode, problem.code, problem.message ?: problem.code)
            } catch (e: IOException) {
                ioFailure(e.message ?: e.javaClass.simpleName)
            } catch (e: UncheckedIOException) {
                ioFailure(e.cause?.message ?: e.message ?: e.javaClass.simpleName)
            } catch (e: OutOfMemoryError) {
                // Not a bug of the CLI: the sessions are too big for the memory Java was given.
                failure(ExitCode.INTERNAL, "outOfMemory", CliBundle.message("error.outOfMemory"))
            } catch (e: Throwable) {
                // Whatever else it is, it is a bug in the CLI: say so, and with `--verbose` say where.
                log.info(e.stackTraceToString())
                failure(ExitCode.INTERNAL, "internalError", CliBundle.message("error.internal", "${e.javaClass.simpleName}: ${e.message}"))
            }
            if (!claimOutput()) return ExitCode.INTERRUPTED
            print(commandName, result, json)
            return result.exitCode
        } finally {
            Locale.setDefault(Locale.Category.DISPLAY, previousLanguage)
        }
    }

    /**
     * Called when the process is being stopped (Ctrl+C, SIGTERM) from the shutdown hook: removes the temporary files of
     * the run and prints the result of an interrupted command. Returns `false` when the command had already printed
     * its own result, in which case there is nothing to add.
     */
    fun interrupt(): Boolean {
        pending.abandon()
        if (!claimOutput()) return false
        print(commandName, failure(ExitCode.INTERRUPTED, "interrupted", CliBundle.message("error.interrupted")), jsonMode)
        environment.out.flush()
        environment.err.flush()
        return true
    }

    /** The first to ask gets to print the result; the other must stay silent, or standard output would hold two documents. */
    private fun claimOutput(): Boolean = synchronized(outputLock) {
        if (outputClaimed) false else true.also { outputClaimed = true }
    }

    private fun dispatch(context: CommandContext): CommandResult = when (context.line.command) {
        "list" -> ListCommand(context).run()
        "inspect" -> InspectCommand(context).run()
        "export" -> ExportCommand(context).run()
        "import" -> ImportCommand(context).run()
        "doctor" -> DoctorCommand(context).run()
        "version" -> VersionCommand.run()
        else -> HelpCommand.run()
    }

    private fun failure(exitCode: Int, code: String, message: String, data: Map<String, Any?>? = null) =
        CommandResult(data, emptyList(), exitCode = exitCode, error = CliError(code, message))

    private fun ioFailure(detail: String) = failure(ExitCode.ENVIRONMENT, "ioError", CliBundle.message("error.io", detail))

    // ----------------------------------------------------------------------------------- output --

    private fun print(command: String?, result: CommandResult, json: Boolean) {
        if (json) {
            environment.out.println(Envelope.render(command, result))
            return
        }
        result.humanLines.forEach(environment.out::println)
        result.warnings.forEach { environment.err.println(CliBundle.message("label.warning", it)) }
        result.error?.let { environment.err.println(CliBundle.message("label.error", it.message)) }
        if (result.exitCode == ExitCode.USAGE) environment.err.println(CliBundle.message("hint.help"))
    }

    // ---------------------------------------------------------------------------------- language --

    /** `--lang`, else `SESSION_PORTER_LANG`, else the language of the system (which the bundles already follow). */
    private fun applyLanguage(option: String?) {
        val tag = option ?: environment.getenv("SESSION_PORTER_LANG")
        if (tag.isNullOrBlank()) return
        val locale = try {
            Locale.Builder().setLanguageTag(tag.trim().replace('_', '-')).build()
        } catch (_: IllformedLocaleException) {
            null
        }
        if (locale == null || locale.language.isEmpty()) {
            // A language from the environment that makes no sense is ignored; one given on the command line is a mistake.
            if (option != null) throw usageError("invalidLanguage", "error.usage.invalidLanguage", option)
            return
        }
        Locale.setDefault(Locale.Category.DISPLAY, locale)
    }

    /** The value of [name] (`--lang x` or `--lang=x`) wherever it stands in [words]. */
    private fun valueOf(words: List<String>, name: String): String? {
        for (index in words.indices) {
            if (words[index] == name) return words.getOrNull(index + 1)
            if (words[index].startsWith("$name=")) return words[index].substringAfter('=')
        }
        return null
    }
}
