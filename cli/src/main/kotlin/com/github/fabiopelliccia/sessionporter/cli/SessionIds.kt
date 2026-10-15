package com.github.fabiopelliccia.sessionporter.cli

import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files

/** Session ids given to `--ids` and `--ids-file`. */
internal object SessionIds {

    /** Ids are separated by commas, spaces or line breaks; a line that starts with `#` is a comment. The same id twice counts once. */
    fun parse(text: String): List<String> =
        text.removePrefix("﻿").lineSequence()
            .filterNot { it.trimStart().startsWith("#") }
            .flatMap { it.split(',', ' ', '\t').asSequence() }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .toList()

    /** The ids of every `--ids` option. */
    fun fromOption(context: CommandContext): List<String> =
        context.line.values("--ids").flatMap(::parse).distinct()

    /** The ids in the file `--ids-file` names, or on standard input for `-`. */
    fun fromFile(context: CommandContext, source: String): List<String> {
        val bytes = if (source == "-") readStandardInput(context.environment) else readFile(context, source)
        return parse(String(bytes, StandardCharsets.UTF_8))
    }

    private fun readStandardInput(environment: CliEnvironment): ByteArray {
        // Reading a console to its end waits for a person who does not know what is being waited for.
        if (environment.inputIsInteractive) throw usageError("idsFromConsole", "error.usage.idsFromConsole")
        return try {
            environment.input.readBytes()
        } catch (_: IOException) {
            // Standard input that cannot be read (closed, or the NUL device of Windows, which refuses reads).
            throw CliException(ExitCode.ENVIRONMENT, "idsFileUnreadable", CliBundle.message("error.idsFileUnreadable", "-"))
        }
    }

    private fun readFile(context: CommandContext, source: String): ByteArray {
        val file = context.path(source)
        return try {
            Files.readAllBytes(file)
        } catch (_: IOException) {
            throw CliException(ExitCode.ENVIRONMENT, "idsFileUnreadable", CliBundle.message("error.idsFileUnreadable", file.toAbsolutePath()))
        }
    }
}
