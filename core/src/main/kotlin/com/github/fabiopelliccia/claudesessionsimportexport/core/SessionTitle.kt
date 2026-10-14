package com.github.fabiopelliccia.claudesessionsimportexport.core

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.BufferedReader

/**
 * Picks the human readable name of a session from its transcript, the same way for the local
 * sessions (export) and for the ones inside an archive (import), so a session reads the same in
 * both tables.
 *
 * Current Claude Code versions write no `summary` line, and the first `user` line of a session is
 * seldom what the user asked: it is usually the markup of a slash command (`<command-name>/model`),
 * a caveat, a system reminder or IDE context. The candidates are therefore, in order:
 *
 * 1. a custom title (`customTitle`), set when the session was renamed;
 * 2. a `summary` line, written by earlier Claude Code versions;
 * 3. the first genuine user prompt, with that markup removed;
 * 4. the slash command the session started with, with its arguments, when no prompt was typed;
 * 5. the last prompt Claude Code recorded (`last-prompt` line).
 *
 * Only `null` is returned when none of them exists; callers then fall back to the session id.
 */
class SessionTitle {

    companion object {
        const val MAX_LENGTH = 140

        /** Blocks that carry context or output, never what the user asked: dropped with their content. */
        private val CONTEXT_BLOCK = Regex(
            "<(system-reminder|local-command-caveat|local-command-stdout|local-command-stderr|bash-stdout|bash-stderr|" +
                "ide_opened_file|ide_selection|ide_diagnostics|user-prompt-submit-hook)>.*?</\\1>",
            RegexOption.DOT_MATCHES_ALL,
        )
        private val COMMAND_NAME = Regex("<command-name>(.*?)</command-name>", RegexOption.DOT_MATCHES_ALL)
        private val COMMAND_ARGS = Regex("<command-args>(.*?)</command-args>", RegexOption.DOT_MATCHES_ALL)
        private val ANY_TAG = Regex("</?[A-Za-z][A-Za-z0-9_-]*>")
        private val WHITESPACE = Regex("\\s+")

        /** Lines Claude Code writes on the user's behalf, which look like a prompt but are not one. */
        private val NOT_A_PROMPT = listOf("Caveat:", "[Request interrupted", "This session is being continued")

        /** Reads a whole transcript and returns its title, see [SessionTitle]. */
        fun of(reader: BufferedReader): String? {
            val title = SessionTitle()
            reader.lineSequence().forEach { line ->
                if (line.isBlank()) return@forEach
                runCatching { JsonParser.parseString(line) }.getOrNull()
                    ?.takeIf { it.isJsonObject }?.let { title.accept(it.asJsonObject) }
            }
            return title.value()
        }

        /** Collapses whitespace and cuts [text] to [MAX_LENGTH] characters. */
        fun shorten(text: String): String? {
            val singleLine = text.replace(WHITESPACE, " ").trim()
            if (singleLine.isEmpty()) return null
            return if (singleLine.length > MAX_LENGTH) singleLine.take(MAX_LENGTH).trimEnd() + "…" else singleLine
        }
    }

    private var customTitle: String? = null
    private var summary: String? = null
    private var firstPrompt: String? = null
    private var firstCommand: String? = null
    private var lastPrompt: String? = null

    fun accept(line: JsonObject) {
        line.string("customTitle")?.let(::shorten)?.let { customTitle = it }
        when (line.string("type")) {
            "summary" -> line.string("summary")?.let(::shorten)?.let { summary = it }
            "last-prompt" -> line.string("lastPrompt")?.let(::prompt)?.let { lastPrompt = it }
            "user" -> if (firstPrompt == null && !line.flag("isMeta") && !line.flag("isSidechain")) {
                messageText(line)?.let(::acceptUserText)
            }
        }
    }

    fun value(): String? = customTitle ?: summary ?: firstPrompt ?: firstCommand ?: lastPrompt

    private fun acceptUserText(text: String) {
        COMMAND_NAME.find(text)?.let { command ->
            if (firstCommand == null) {
                val arguments = COMMAND_ARGS.find(text)?.groupValues?.get(1).orEmpty()
                firstCommand = shorten("${command.groupValues[1]} $arguments")
            }
            return
        }
        firstPrompt = prompt(text)
    }

    /** The prompt [text] actually contains, or `null` when it is only markup or a generated line. */
    private fun prompt(text: String): String? {
        val cleaned = text.replace(CONTEXT_BLOCK, " ").replace(ANY_TAG, " ")
        val candidate = shorten(cleaned) ?: return null
        return candidate.takeIf { value -> NOT_A_PROMPT.none { value.startsWith(it) } }
    }

    /** The text of a `user` line: a plain string, or its text blocks (tool results are not text). */
    private fun messageText(line: JsonObject): String? {
        val content = line.get("message")?.takeIf { it.isJsonObject }?.asJsonObject?.get("content") ?: return null
        return when {
            content.isJsonPrimitive -> content.asString
            content.isJsonArray -> content.asJsonArray
                .filter { it.isJsonObject && it.asJsonObject.string("type") == "text" }
                .mapNotNull { it.asJsonObject.string("text") }
                .joinToString(" ")
                .takeIf { it.isNotBlank() }
            else -> null
        }
    }

    private fun JsonObject.string(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

    private fun JsonObject.flag(key: String): Boolean =
        get(key)?.takeIf(JsonElement::isJsonPrimitive)?.asJsonPrimitive?.let { it.isBoolean && it.asBoolean } == true
}
