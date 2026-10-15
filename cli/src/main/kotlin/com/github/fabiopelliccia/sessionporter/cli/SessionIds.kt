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
        val bytes = if (source == "-") {
            context.environment.input.readBytes()
        } else {
            val file = context.path(source)
            try {
                Files.readAllBytes(file)
            } catch (e: IOException) {
                throw CliException(ExitCode.ENVIRONMENT, "idsFileUnreadable", CliBundle.message("error.idsFileUnreadable", file.toAbsolutePath()))
            }
        }
        return parse(String(bytes, StandardCharsets.UTF_8))
    }
}
