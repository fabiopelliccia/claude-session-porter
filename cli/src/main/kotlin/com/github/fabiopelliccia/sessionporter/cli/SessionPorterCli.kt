package com.github.fabiopelliccia.sessionporter.cli

import java.io.IOException
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
 */
internal class SessionPorterCli(private val environment: CliEnvironment) {

    fun run(args: List<String>): Int {
        // The two global options are read before the command line is parsed, so that a mistake in it is
        // reported in the language and in the form (JSON or plain) that were asked for.
        val options = args.takeWhile { it != "--" }
        val json = CommandSpecs.JSON in options
        val log = CliLog(CommandSpecs.VERBOSE in options, environment.err)

        // The language is global to the JVM: put back what it was, so that a process that runs the CLI twice
        // (the tests do) does not carry one run's language into the next.
        val previousLanguage = Locale.getDefault(Locale.Category.DISPLAY)
        try {
            var command: String? = null
            val result = try {
                applyLanguage(valueOf(options, CommandSpecs.LANG))
                val line = ArgumentParser.parse(args)
                command = line.command
                dispatch(CommandContext(line, environment, log))
            } catch (e: CliException) {
                failure(e.exitCode, e.code, e.message ?: e.code, e.data)
            } catch (e: IOException) {
                failure(ExitCode.ENVIRONMENT, "ioError", CliBundle.message("error.io", e.message ?: e.javaClass.simpleName))
            } catch (e: Throwable) {
                // Whatever else it is, it is a bug in the CLI: say so, and with `--verbose` say where.
                log.info(e.stackTraceToString())
                failure(ExitCode.INTERNAL, "internalError", CliBundle.message("error.internal", "${e.javaClass.simpleName}: ${e.message}"))
            }
            print(command, result, json)
            return result.exitCode
        } finally {
            Locale.setDefault(Locale.Category.DISPLAY, previousLanguage)
        }
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
