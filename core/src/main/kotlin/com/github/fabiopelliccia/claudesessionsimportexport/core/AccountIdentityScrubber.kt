package com.github.fabiopelliccia.claudesessionsimportexport.core

import com.google.gson.JsonParser
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.regex.Pattern

/**
 * Removes the account email and the git user name of the exporting user from every text of the archive.
 *
 * [SessionRedactor] takes them out of the `session_context` lines that carry them, but the same values are
 * everywhere the conversation touched them: a `git log` in a `toolUseResult`, a `Co-Authored-By:` trailer
 * in a saved tool result, a commit author in a file of the history. This class finds them where the
 * redactor sees them ([collect]) and replaces them, in every text entry of every session, by the same
 * [SessionRedactor.PLACEHOLDER].
 *
 * An email is replaced wherever it is, whatever the case. A name is replaced as a whole word only
 * (`Alice Rossi` is not `Alice Rossini`) and only when it has [MIN_NAME_LENGTH] characters or more: a
 * name that short is a word of the conversation. Names are never replaced in entry names, which have to
 * stay names a file system accepts.
 */
internal class AccountIdentityScrubber private constructor(emails: Collection<String>, names: Collection<String>) {

    /** What was replaced: a count only, never a value. */
    class Stats {
        var replacements = 0
    }

    private val literals: List<String> = (emails + names).distinctBy { it.lowercase() }

    private val pattern: Pattern? = if (literals.isEmpty()) {
        null
    } else {
        val emailAlternatives = emails.map { Pattern.quote(it) }
        val nameAlternatives = names.map { "(?<![\\p{L}\\p{N}])" + Pattern.quote(it) + "(?![\\p{L}\\p{N}])" }
        Pattern.compile((emailAlternatives + nameAlternatives).joinToString("|"), Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE)
    }

    /** A value with a character outside ASCII may be written in a transcript as a unicode escape, which a plain search does not see. */
    private val hasNonAsciiValue: Boolean = literals.any { value -> value.any { it.code >= ASCII_LIMIT } }

    val isEmpty: Boolean get() = pattern == null

    /** Cheap pre-filter: `false` guarantees that [text] holds none of the values. */
    private fun mayContainValue(text: String): Boolean =
        literals.any { JsonLines.containsIgnoreCase(text, it) } || (hasNonAsciiValue && text.contains(ESCAPE_MARKER))

    /** [text] as it is (a text file, a manifest value, a line that is not JSON) without the values. */
    fun scrubText(text: String, stats: Stats): String {
        val pattern = pattern ?: return text
        if (!mayContainValue(text)) return text
        val found = pattern.matcher(text)
        var out: StringBuilder? = null
        var last = 0
        while (found.find()) {
            val builder = out ?: StringBuilder(text.length).also { out = it }
            builder.append(text, last, found.start()).append(SessionRedactor.PLACEHOLDER)
            last = found.end()
            stats.replacements++
        }
        return out?.append(text, last, text.length)?.toString() ?: text
    }

    /**
     * One transcript line: every string value and every object key is scrubbed, the line is re-emitted only if something
     * changed, and a line that is not JSON goes through [scrubText] as it is, like [PathAnonymizer.anonymizeJsonLine] does.
     */
    fun scrubJsonLine(line: String, stats: Stats): String {
        if (isEmpty || !mayContainValue(line)) return line
        val element = JsonLines.parse(line) ?: return scrubText(line, stats)
        val mapped = JsonLines.mapStrings(element, { scrubText(it, stats) }, {})
        return if (mapped === element) line else JsonLines.toJson(mapped)
    }

    /** A whole transcript, line by line, with the line endings and every unchanged line as they were. */
    fun scrubJsonl(text: String, stats: Stats): String =
        if (isEmpty) text else JsonLines.mapLines(text) { scrubJsonLine(it, stats) }

    companion object {

        /** Nothing to remove. */
        val NONE = AccountIdentityScrubber(emptyList(), emptyList())

        const val MIN_NAME_LENGTH = 3

        private const val ASCII_LIMIT = 128
        private const val ESCAPE_MARKER = "\\u"

        private const val SESSION_CONTEXT = "session_context"

        private val EMAIL = Regex("[A-Za-z0-9._%+'-]+@[A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)+")
        private val GIT_USER = Regex("(?m)^Git user:[ \\t]*(.+?)[ \\t]*\\r?$")

        /**
         * The email and the git user name the `session_context` lines of the transcripts [files] carry: the same
         * two values [SessionRedactor] removes from them, taken from the text Claude Code wrote them in
         * (`The user's email address is a@b.c.`, a line `Git user: Name`). A file that cannot be read is skipped.
         */
        fun collect(files: List<Path>): AccountIdentityScrubber {
            val emails = linkedSetOf<String>()
            val names = linkedSetOf<String>()
            for (file in files) {
                try {
                    // A reader that replaces what is not UTF-8: one bad byte must not hide the lines after it.
                    InputStreamReader(Files.newInputStream(file), StandardCharsets.UTF_8).buffered().use { reader ->
                        reader.lineSequence().filter { it.contains(SESSION_CONTEXT) }.forEach { line -> collectFromLine(line, emails, names) }
                    }
                } catch (e: java.io.IOException) {
                    // A file that cannot be read tells nothing; the export reports it where it reads it for real.
                }
            }
            return AccountIdentityScrubber(emails, names.filter { it.length >= MIN_NAME_LENGTH })
        }

        private fun collectFromLine(line: String, emails: MutableSet<String>, names: MutableSet<String>) {
            val attachment = runCatching { JsonParser.parseString(line).asJsonObject.getAsJsonObject("attachment") }.getOrNull() ?: return
            if (attachment.get("type")?.takeIf { it.isJsonPrimitive }?.asString != SESSION_CONTEXT) return
            val context = attachment.get("context")?.takeIf { it.isJsonObject }?.asJsonObject ?: return
            context.stringOrNull("userEmail")?.let { text -> EMAIL.findAll(text).forEach { emails += it.value } }
            context.stringOrNull("gitStatus")?.let { text -> GIT_USER.findAll(text).forEach { names += it.groupValues[1] } }
        }

        private fun com.google.gson.JsonObject.stringOrNull(key: String): String? =
            get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
    }
}
