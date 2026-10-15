package com.github.fabiopelliccia.sessionporter.cli

/** What one command accepts besides the global options. */
internal class CommandSpec(
    val name: String,
    /** Options that are followed by a value (`--out file.zip` or `--out=file.zip`). */
    val valueOptions: Set<String> = emptySet(),
    /** Options that stand alone. */
    val flagOptions: Set<String> = emptySet(),
    /** The positional arguments, by the name the error message uses. All of them are required. */
    val positionals: List<String> = emptyList(),
)

/** The commands and options of the CLI, written once so that the parser, the help text and the tests agree. */
internal object CommandSpecs {

    const val HOME = "--home"
    const val JSON = "--json"
    const val LANG = "--lang"
    const val VERBOSE = "--verbose"

    val globalValueOptions = setOf(HOME, LANG)
    val globalFlags = setOf(JSON, VERBOSE)

    const val HELP = "help"

    val all: List<CommandSpec> = listOf(
        CommandSpec("list", valueOptions = setOf("--project", "--since", "--limit")),
        CommandSpec("inspect", positionals = listOf("<zip>")),
        CommandSpec(
            "export",
            valueOptions = setOf("--ids", "--ids-file", "--project", "--out", "--log-dir"),
            flagOptions = setOf("--all", "--force", "--strict-privacy"),
        ),
        CommandSpec(
            "import",
            valueOptions = setOf("--target", "--ids", "--policy", "--log-dir"),
            flagOptions = setOf("--no-shift", "--keep-original-folder"),
            positionals = listOf("<zip>"),
        ),
        CommandSpec("doctor", valueOptions = setOf("--log-dir")),
        CommandSpec("version"),
        CommandSpec(HELP),
    )

    fun find(name: String): CommandSpec? = all.firstOrNull { it.name == name }
}

/** A command line after parsing: the command, its positional arguments and the options that were given. */
internal class CommandLine(
    val command: String,
    val positionals: List<String>,
    private val values: Map<String, List<String>>,
    private val flags: Set<String>,
) {
    fun flag(option: String): Boolean = option in flags

    fun isGiven(option: String): Boolean = option in flags || values.containsKey(option)

    /** The value of [option], or `null` when it was not given; giving it twice is an error. */
    fun value(option: String): String? {
        val given = values[option] ?: return null
        if (given.size > 1) throw usageError("repeatedOption", "error.usage.repeatedOption", option)
        return given.single()
    }

    /** Every value [option] was given, in order (only `--ids` may be repeated). */
    fun values(option: String): List<String> = values[option].orEmpty()
}

/**
 * The argument parser, written by hand so that the jar needs no library for it.
 *
 * Global options (`--home`, `--json`, `--lang`, `--verbose`) may stand before or after the command,
 * the options of a command after it; a value follows its option as the next word or after `=`. A word
 * that does not start with `-` is the command, then a positional argument; `--` makes everything after
 * it positional. `--help` and `-h` anywhere ask for the help text.
 */
internal object ArgumentParser {

    fun parse(args: List<String>): CommandLine {
        if (args.any { it == "--help" || it == "-h" }) return CommandLine(CommandSpecs.HELP, emptyList(), emptyMap(), emptySet())

        val commandIndex = indexOfCommand(args)
        val command = args[commandIndex]
        val spec = CommandSpecs.find(command) ?: throw usageError("unknownCommand", "error.usage.unknownCommand", command)

        val valueOptions = CommandSpecs.globalValueOptions + spec.valueOptions
        val flagOptions = CommandSpecs.globalFlags + spec.flagOptions
        val values = linkedMapOf<String, MutableList<String>>()
        val flags = linkedSetOf<String>()
        val positionals = mutableListOf<String>()

        var onlyPositionals = false
        var index = 0
        while (index < args.size) {
            val word = args[index]
            index++
            if (index - 1 == commandIndex) continue
            if (onlyPositionals || !word.startsWith("-") || word == "-") {
                positionals += word
                continue
            }
            if (word == "--") {
                onlyPositionals = true
                continue
            }

            val name = word.substringBefore('=')
            val inlineValue = if (word.contains('=')) word.substringAfter('=') else null
            when (name) {
                in valueOptions -> {
                    val value = inlineValue ?: args.getOrNull(index)?.also { index++ }
                        ?: throw usageError("missingValue", "error.usage.missingValue", name)
                    values.getOrPut(name) { mutableListOf() } += value
                }
                in flagOptions -> {
                    if (inlineValue != null) throw usageError("unexpectedValue", "error.usage.unexpectedValue", name)
                    flags += name
                }
                else -> throw usageError("unknownOption", "error.usage.unknownOption", name, command)
            }
        }

        if (positionals.size > spec.positionals.size) {
            throw usageError("unexpectedArgument", "error.usage.unexpectedArgument", positionals[spec.positionals.size])
        }
        if (positionals.size < spec.positionals.size) {
            throw usageError("missingArgument", "error.usage.missingArgument", command, spec.positionals[positionals.size])
        }
        return CommandLine(command, positionals, values, flags)
    }

    /** The first word that is neither a global option nor the value of one: only those may stand before the command. */
    private fun indexOfCommand(args: List<String>): Int {
        var index = 0
        while (index < args.size) {
            val word = args[index]
            if (!word.startsWith("-")) return index
            val name = word.substringBefore('=')
            index += when {
                name in CommandSpecs.globalValueOptions -> if (word.contains('=')) 1 else 2
                name in CommandSpecs.globalFlags -> 1
                else -> throw usageError("unknownOption", "error.usage.optionBeforeCommand", name)
            }
        }
        throw usageError("noCommand", "error.usage.noCommand")
    }

    /**
     * The command [args] name, for the report of a command line that did not parse: `null` when there is none or
     * it is not one of ours. It reads like [indexOfCommand] but never fails.
     */
    fun commandWord(args: List<String>): String? {
        return try {
            args[indexOfCommand(args)].takeIf { CommandSpecs.find(it) != null }
        } catch (_: CliException) {
            null
        }
    }
}
