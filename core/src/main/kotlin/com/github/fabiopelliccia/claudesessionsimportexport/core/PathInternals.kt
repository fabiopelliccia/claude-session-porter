package com.github.fabiopelliccia.claudesessionsimportexport.core

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.StringReader
import java.util.regex.Pattern

/**
 * An absolute path taken apart: an optional drive letter and the folder names below it. Only what the
 * anonymization needs; a relative path or a UNC share (`\\server\share`) is not an anchor and does
 * not parse.
 */
internal class AnchorPath private constructor(val drive: Char?, val segments: List<String>) {

    val isWindows: Boolean get() = drive != null

    val leaf: String get() = segments.last()

    fun startsWith(other: AnchorPath): Boolean =
        drive?.lowercaseChar() == other.drive?.lowercaseChar() &&
            segments.size >= other.segments.size &&
            other.segments.indices.all { segments[it].equals(other.segments[it], ignoreCase = true) }

    /** Same path in the key the duplicates of an anchor list are detected with. */
    val key: String get() = (drive?.lowercaseChar()?.toString() ?: "") + "|" + segments.joinToString("/") { it.lowercase() }

    companion object {
        private val SEPARATORS = Regex("[\\\\/]+")

        fun parse(path: String): AnchorPath? {
            val text = path.trim()
            val drive = text.firstOrNull()?.takeIf { text.length >= 2 && text[1] == ':' && (it in 'A'..'Z' || it in 'a'..'z') }
            val rest = if (drive != null) text.substring(2) else text
            if (drive != null && rest.isNotEmpty() && rest[0] != '\\' && rest[0] != '/') return null
            if (drive == null && !rest.startsWith("/")) return null
            val segments = rest.split(SEPARATORS).filter { it.isNotEmpty() }
            if (segments.isEmpty()) return null
            return AnchorPath(drive, segments)
        }
    }
}

/**
 * Reading and writing transcripts line by line, shared by [PathAnonymizer] and [PathDeanonymizer].
 * The Gson settings are the ones [SessionRedactor] and [TranscriptRewriter] re-emit a line with.
 */
internal object JsonLines {

    private val gson = GsonBuilder().serializeNulls().disableHtmlEscaping().create()

    /** An object or array line parsed strictly, or `null` for anything else (partial, not JSON). */
    fun parse(line: String): JsonElement? {
        val first = line.firstOrNull { !it.isWhitespace() } ?: return null
        if (first != '{' && first != '[') return null
        return runCatching {
            val reader = JsonReader(StringReader(line))
            reader.strictness = Strictness.STRICT
            val element = JsonParser.parseReader(reader)
            if (reader.peek() != JsonToken.END_DOCUMENT) null else element
        }.getOrNull()
    }

    fun toJson(element: JsonElement): String = gson.toJson(element)

    /**
     * Applies [transform] to every string value and every object key, recursively. Returns [element]
     * itself when nothing changed; a changed object is rebuilt keeping its key order. When two keys
     * would collapse into one, the later one keeps its original name and [onKeyCollision] is called.
     */
    fun mapStrings(element: JsonElement, transform: (String) -> String, onKeyCollision: () -> Unit): JsonElement {
        if (element.isJsonPrimitive) {
            if (!element.asJsonPrimitive.isString) return element
            val text = element.asString
            val mapped = transform(text)
            return if (mapped == text) element else JsonPrimitive(mapped)
        }
        if (element.isJsonArray) {
            val array = element.asJsonArray
            val children = array.map { mapStrings(it, transform, onKeyCollision) }
            if (children.indices.all { children[it] === array[it] }) return element
            return JsonArray(children.size).also { out -> children.forEach(out::add) }
        }
        if (element.isJsonObject) {
            val obj = element.asJsonObject
            var changed = false
            val rebuilt = JsonObject()
            for ((key, value) in obj.entrySet()) {
                var newKey = transform(key)
                val newValue = mapStrings(value, transform, onKeyCollision)
                if (newKey != key && rebuilt.has(newKey)) {
                    onKeyCollision()
                    newKey = key
                }
                if (newKey != key || newValue !== value) changed = true
                rebuilt.add(newKey, newValue)
            }
            return if (changed) rebuilt else element
        }
        return element
    }

    /**
     * Applies [transform] to every non-blank line of [text], leaving everything else - the `\r` of a
     * `\r\n` ending, a missing final newline, blank lines, a line [transform] returns unchanged - byte
     * for byte. Returns [text] itself when no line changed.
     */
    fun mapLines(text: String, transform: (String) -> String): String {
        var out: StringBuilder? = null
        var copied = 0
        var lineStart = 0
        val length = text.length
        while (lineStart <= length) {
            val newline = text.indexOf('\n', lineStart)
            val end = if (newline < 0) length else newline
            val lineEnd = if (end > lineStart && text[end - 1] == '\r') end - 1 else end
            if (lineEnd > lineStart) {
                val line = text.substring(lineStart, lineEnd)
                if (line.isNotBlank()) {
                    val mapped = transform(line)
                    if (mapped != line) {
                        if (out == null) out = StringBuilder(text.length + 256)
                        out.append(text, copied, lineStart).append(mapped)
                        copied = lineEnd
                    }
                }
            }
            if (newline < 0) break
            lineStart = newline + 1
        }
        if (out == null) return text
        return out.append(text, copied, length).toString()
    }

    /** `true` when [text] holds [needle] whatever the case; needles are short, so a plain scan is enough. */
    fun containsIgnoreCase(text: String, needle: String): Boolean {
        val n = needle.length
        if (n == 0) return true
        val last = text.length - n
        val lower = needle[0].lowercaseChar()
        val upper = needle[0].uppercaseChar()
        var i = 0
        while (i <= last) {
            val c = text[i]
            if ((c == lower || c == upper || c.lowercaseChar() == lower) && text.regionMatches(i, needle, 0, n, ignoreCase = true)) return true
            i++
        }
        return false
    }
}

/** A way an anchor shows up in text: the regex (no capturing group inside) and the style it stands for. */
internal class PathAlternative(val regex: String, val style: (String) -> PathStyle)

/** The 8.3 spelling of a home folder: its drive and folder names as regex fragments. */
internal class ShortPattern(val drive: String, val segments: List<String>)

/**
 * Every spelling of one anchor, as one regex of alternatives (each in its own capturing group, so the
 * alternative that matched tells the style). Matching is case-insensitive.
 *
 * Boundaries: before a drive or a slash the previous character must not continue a word or a path
 * (or it is the `n`/`t`/`r` of a JSON escape, so `\nC:\\Users` is found in raw text too); after the
 * last folder name no letter, digit, `_` or `~` may follow, nor a `.` followed by one of them, so
 * `alice2`, `alice.bak` and `ALICE~1` are no `alice`, while a sentence's final dot is not part of the
 * name. The encoded form is bounded by anything but a letter or digit (a `-` is therefore a boundary).
 */
internal class AnchorMatcher(val kind: PathKind, val path: AnchorPath, shortPatterns: List<ShortPattern>) {

    private val alternatives: List<PathAlternative> = build(path, shortPatterns)

    val pattern: Pattern = Pattern.compile(
        alternatives.joinToString("|") { "(${it.regex})" },
        Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE,
    )

    /** The style of the alternative that just matched. */
    fun styleOf(matcher: java.util.regex.Matcher): PathStyle {
        for (index in alternatives.indices) {
            if (matcher.start(index + 1) >= 0) return alternatives[index].style(matcher.group(index + 1))
        }
        error("No alternative matched")
    }

    private companion object {
        const val BACKSLASH = "\\\\"
        const val DRIVE_BEFORE = "(?:(?<![A-Za-z0-9_])|(?<=\\\\[ntr]))"
        const val SLASH_BEFORE = "(?:(?<![A-Za-z0-9_.~\\-])|(?<=\\\\[ntr]))"
        const val URL_BEFORE = "(?:(?<![A-Za-z0-9_.~%\\-])|(?<=\\\\[ntr]))"
        const val ENC_BEFORE = "(?<![A-Za-z0-9_])"
        const val AFTER = "(?![\\p{L}\\p{N}_~]|\\.[\\p{L}\\p{N}_])"
        const val AFTER_SHORT = "(?![\\p{L}\\p{N}_])"
        const val AFTER_ENC = "(?![A-Za-z0-9_]|\\.[A-Za-z0-9_])"
        const val AFTER_URL = "(?![\\p{L}\\p{N}_~]|\\.[\\p{L}\\p{N}_]|%(?!2[Ff]|5[Cc])[0-9A-Fa-f]{2})"
        const val PLAIN_SEPARATOR = "[\\\\/]"
        const val URL_SEPARATOR = "(?:/|%2[Ff])"
        const val URL_COLON = "(?::|%3[Aa])"

        fun fixed(style: PathStyle, regex: String) = PathAlternative(regex) { style }

        fun chain(separator: String, parts: List<String>) = parts.joinToString("") { separator + it }

        fun backslashes(count: Int) = BACKSLASH.repeat(count)

        fun firstSeparator(match: String, from: Int): Char? =
            match.substring(from).firstOrNull { it == '\\' || it == '/' }

        fun build(path: AnchorPath, shortPatterns: List<ShortPattern>): List<PathAlternative> {
            val names = path.segments.map { Pattern.quote(it) }
            val urlNames = path.segments.map { urlName(it) }
            val encoded = Pattern.quote(ClaudePaths.encodeProjectPath(nativeOf(path)))
            val list = mutableListOf<PathAlternative>()
            val drive = path.drive
            if (drive != null) {
                for ((style, count) in listOf(PathStyle.ESC3 to 8, PathStyle.ESC2 to 4, PathStyle.ESC1 to 2)) {
                    list += fixed(style, DRIVE_BEFORE + drive + ":" + chain(backslashes(count), names) + AFTER)
                }
                for ((style, prefix) in listOf(PathStyle.WSL to "/mnt/", PathStyle.CYG to "/cygdrive/")) {
                    list += fixed(style, SLASH_BEFORE + prefix + drive + chain("/", names) + AFTER)
                }
                list += fixed(PathStyle.MSYS, SLASH_BEFORE + "/" + drive + chain("/", names) + AFTER)
                for (short in shortPatterns) {
                    for ((style, count) in listOf(PathStyle.SHORT_ESC2 to 4, PathStyle.SHORT_ESC1 to 2)) {
                        list += fixed(style, DRIVE_BEFORE + short.drive + ":" + chain(backslashes(count), short.segments) + AFTER_SHORT)
                    }
                    list += PathAlternative(DRIVE_BEFORE + short.drive + ":" + chain(PLAIN_SEPARATOR, short.segments) + AFTER_SHORT) { match ->
                        if (firstSeparator(match, 2) == '\\') PathStyle.SHORT else PathStyle.SHORT_FWD
                    }
                }
                list += PathAlternative(DRIVE_BEFORE + drive + ":" + chain(PLAIN_SEPARATOR, names) + AFTER) { match ->
                    if (firstSeparator(match, 2) == '\\') PathStyle.WIN else PathStyle.FWD
                }
                list += fixed(PathStyle.ENC, ENC_BEFORE + encoded + AFTER_ENC)
                list += fixed(PathStyle.URL_BACKSLASH, URL_BEFORE + drive + URL_COLON + chain("%5[Cc]", urlNames) + AFTER_URL)
                list += fixed(PathStyle.URL, URL_BEFORE + drive + URL_COLON + chain(URL_SEPARATOR, urlNames) + AFTER_URL)
            } else {
                list += fixed(PathStyle.FWD, SLASH_BEFORE + chain("/", names) + AFTER)
                list += fixed(PathStyle.ENC, ENC_BEFORE + encoded + AFTER_ENC)
                list += fixed(PathStyle.URL, URL_BEFORE + chain(URL_SEPARATOR, urlNames) + AFTER_URL)
            }
            return list
        }

        fun nativeOf(path: AnchorPath): String =
            if (path.drive != null) "${path.drive}:\\" + path.segments.joinToString("\\") else "/" + path.segments.joinToString("/")

        /** A folder name as it reads in a URL: every byte outside the unreserved set is `%XX`. */
        fun urlName(name: String): String {
            val out = StringBuilder()
            var run = StringBuilder()
            var index = 0
            while (index < name.length) {
                val codePoint = name.codePointAt(index)
                index += Character.charCount(codePoint)
                if (codePoint < 128 && (codePoint.toChar().let { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it in "-._~" })) {
                    run.append(codePoint.toChar())
                } else {
                    if (run.isNotEmpty()) out.append(Pattern.quote(run.toString()))
                    run = StringBuilder()
                    for (byte in String(Character.toChars(codePoint)).toByteArray(Charsets.UTF_8)) {
                        out.append('%').append("%02X".format(byte.toInt() and 0xFF))
                    }
                }
            }
            if (run.isNotEmpty()) out.append(Pattern.quote(run.toString()))
            return out.toString()
        }
    }
}
