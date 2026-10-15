package com.github.fabiopelliccia.sessionporter.cli

import com.google.gson.GsonBuilder

/** What the JSON mode writes, see [Envelope]. Bumped only when a key is removed or changes its meaning. */
internal const val SCHEMA_VERSION = 1

/** The `error` of the envelope: [code] is stable and in English, [message] is for a person and follows `--lang`. */
internal class CliError(val code: String, val message: String)

/**
 * What a command hands back to the runner: the [data] of the JSON envelope and the same content as
 * [humanLines] for the plain mode. [exitCode] is `0` unless the command ran to the end with a
 * problem worth a code of its own (a partial import, a refused strict export), in which case
 * [error] says which; the data is then still there.
 */
internal class CommandResult(
    val data: Map<String, Any?>?,
    val humanLines: List<String>,
    val warnings: List<String> = emptyList(),
    val exitCode: Int = ExitCode.OK,
    val error: CliError? = null,
)

/** The one JSON document of the `--json` mode. */
internal object Envelope {

    private val gson = GsonBuilder().serializeNulls().disableHtmlEscaping().create()

    fun render(command: String?, result: CommandResult): String {
        val document = linkedMapOf<String, Any?>(
            "schemaVersion" to SCHEMA_VERSION,
            "ok" to (result.exitCode == ExitCode.OK),
            "command" to command,
            "data" to result.data,
            "warnings" to result.warnings,
            "error" to result.error?.let { linkedMapOf("code" to it.code, "message" to it.message) },
        )
        return gson.toJson(document)
    }
}

/** Plain text columns, each as wide as its widest cell. */
internal object TextTable {

    fun render(headers: List<String>, rows: List<List<String>>): List<String> {
        val widths = headers.indices.map { column -> (rows.map { it[column].length } + headers[column].length).max() }
        fun format(cells: List<String>) = cells.indices.joinToString("  ") { cells[it].padEnd(widths[it]) }.trimEnd()
        return listOf(format(headers)) + rows.map(::format)
    }
}
