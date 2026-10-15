package com.github.fabiopelliccia.claudesessionsimportexport.core

import java.text.Normalizer
import java.util.regex.Pattern

/**
 * The regex fragments a folder or user name is searched with. Every fragment is meant to be used
 * case-insensitively (see [AnchorMatcher]) and holds no capturing group.
 *
 * A name is spelled by the tools in more ways than the text it was given as:
 *
 * - letters outside ASCII may be composed (`ë`) or decomposed (`e` and a combining diaeresis, how macOS
 *   keeps file names), and may be written as the JSON escape `\u00eb` (Python's `json.dumps`);
 * - a space may be escaped for a shell, with a backslash (`John\ Smith`, one more backslash per escaping
 *   level) or a PowerShell backtick;
 * - in a URL every character outside the unreserved set may be percent-encoded or not, one character at a
 *   time (`file:/C:/Users/Zoë%20Müller`), and a space may be a `+`.
 */
internal object PathNamePatterns {

    /** A folder name as it reads in a plain path. */
    fun plain(name: String): String = build(name, percentEncoding = false)

    /** A folder name as it reads in a URL: plain spellings, and any character outside the unreserved set as `%XX`. */
    fun url(name: String): String = build(name, percentEncoding = true)

    private fun build(name: String, percentEncoding: Boolean): String {
        val composed = Normalizer.normalize(name, Normalizer.Form.NFC)
        val pattern = StringBuilder()
        var literalRun = StringBuilder()

        fun flushLiteralRun() {
            if (literalRun.isNotEmpty()) pattern.append(Pattern.quote(literalRun.toString()))
            literalRun = StringBuilder()
        }

        var index = 0
        while (index < composed.length) {
            val codePoint = composed.codePointAt(index)
            index += Character.charCount(codePoint)
            if (isLiteralAlways(codePoint) || (codePoint < ASCII_LIMIT && !percentEncoding && codePoint != SPACE)) {
                literalRun.appendCodePoint(codePoint)
                continue
            }
            flushLiteralRun()
            pattern.append(if (codePoint == SPACE) spaceAlternative(percentEncoding) else characterAlternative(codePoint, percentEncoding))
        }
        flushLiteralRun()
        return pattern.toString()
    }

    /** Letters, digits and `-._~`: written the same way everywhere, URLs included. */
    private fun isLiteralAlways(codePoint: Int): Boolean =
        codePoint < ASCII_LIMIT && codePoint.toChar().let { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it in "-._~" }

    /**
     * A space: plain, escaped for a shell by a backslash (any number of them, the level of the text) or a
     * PowerShell backtick; in a URL also `%20` and `+`.
     */
    private fun spaceAlternative(percentEncoding: Boolean): String {
        val shellEscapedSpace = """(?:\\*+|`)[ ]"""
        return if (percentEncoding) "(?:$shellEscapedSpace|%20|\\+)" else shellEscapedSpace
    }

    /**
     * One character that is not a letter or digit of ASCII: as it is, as its decomposed form (when it has
     * one), as a JSON unicode escape and, in a URL, percent-encoded.
     */
    private fun characterAlternative(codePoint: Int, percentEncoding: Boolean): String {
        val alternatives = mutableListOf(spellingsOf(codePoint, percentEncoding))
        val decomposed = Normalizer.normalize(String(Character.toChars(codePoint)), Normalizer.Form.NFD)
        if (decomposed != String(Character.toChars(codePoint))) {
            // Base letter and combining marks, each of them spelled in every way of its own.
            alternatives += decomposed.codePoints().toArray().joinToString("") { spellingsOf(it, percentEncoding) }
        }
        return alternatives.joinToString("|", prefix = "(?:", postfix = ")")
    }

    /** The ways one code point is written: itself, `\uXXXX` (two escapes above the basic plane) and `%XX` per UTF-8 byte. */
    private fun spellingsOf(codePoint: Int, percentEncoding: Boolean): String {
        val text = String(Character.toChars(codePoint))
        if (isLiteralAlways(codePoint)) return Pattern.quote(text)
        val spellings = mutableListOf(Pattern.quote(text))
        if (codePoint >= ASCII_LIMIT) {
            // The backslash of the escape may already be eaten by the separator in front of the name
            // (`C:\Users\` + `\u00c9mile` is three backslashes in a row), hence the lookbehind.
            spellings += text.map { """(?:\\+|(?<=\\))u%04x""".format(it.code) }.joinToString("")
        }
        if (percentEncoding) {
            spellings += text.toByteArray(Charsets.UTF_8).joinToString("") { "%%%02X".format(it.toInt() and 0xFF) }
        }
        return if (spellings.size == 1) spellings[0] else spellings.joinToString("|", prefix = "(?:", postfix = ")")
    }

    private const val ASCII_LIMIT = 128
    private const val SPACE = ' '.code
}
