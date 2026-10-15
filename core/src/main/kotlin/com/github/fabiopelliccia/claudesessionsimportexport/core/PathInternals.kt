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

/**
 * An absolute path taken apart: a drive letter, or a UNC share (`\\server\share`, then [isUnc] is set and
 * the server and the share are the first two [segments]), or neither for a POSIX path; and the folder
 * names below it. Only what the anonymization needs; a relative path is not an anchor and does not parse.
 */
internal class AnchorPath private constructor(val drive: Char?, val segments: List<String>, val isUnc: Boolean = false) {

    val isWindows: Boolean get() = drive != null || isUnc

    val leaf: String get() = segments.last()

    /** The folder that holds this one, or `null` when this is already the top (a drive, a share, `/`). */
    val parent: AnchorPath? get() = if (segments.size > 1) AnchorPath(drive, segments.dropLast(1), isUnc) else null

    /** The path as its own OS writes it: `C:\Users\alice`, `\\server\share\alice`, `/home/alice`. */
    val native: String
        get() = when {
            drive != null -> "$drive:\\" + segments.joinToString("\\")
            isUnc -> "\\\\" + segments.joinToString("\\")
            else -> "/" + segments.joinToString("/")
        }

    /**
     * The path as a Claude Code project folder name, or `null` when that name would hold no letter or digit
     * at all (a home `/Алиса` encodes to `------`, which says nothing and would match every line of dashes).
     */
    val encodedFolderName: String?
        get() = ClaudePaths.encodeProjectPath(native).takeIf { name -> name.any { it != '-' } }

    fun startsWith(other: AnchorPath): Boolean =
        drive?.lowercaseChar() == other.drive?.lowercaseChar() &&
            isUnc == other.isUnc &&
            segments.size >= other.segments.size &&
            other.segments.indices.all { segments[it].equals(other.segments[it], ignoreCase = true) }

    /** Same path in the key the duplicates of an anchor list are detected with. */
    val key: String
        get() = (drive?.lowercaseChar()?.toString() ?: if (isUnc) "unc" else "") + "|" + segments.joinToString("/") { it.lowercase() }

    companion object {
        private val SEPARATORS = Regex("[\\\\/]+")

        fun parse(path: String): AnchorPath? {
            val text = path.trim()
            if (text.startsWith("\\\\") && text.length > 2 && text[2] != '\\' && text[2] != '/') return parseUnc(text)
            val drive = text.firstOrNull()?.takeIf { text.length >= 2 && text[1] == ':' && (it in 'A'..'Z' || it in 'a'..'z') }
            val rest = if (drive != null) text.substring(2) else text
            if (drive != null && rest.isNotEmpty() && rest[0] != '\\' && rest[0] != '/') return null
            if (drive == null && !rest.startsWith("/")) return null
            val segments = rest.split(SEPARATORS).filter { it.isNotEmpty() }
            if (segments.isEmpty()) return null
            return AnchorPath(drive, segments)
        }

        /** `\\server\share\folder`: the server and the share count as folders; a bare server is no anchor. */
        private fun parseUnc(text: String): AnchorPath? {
            val segments = text.split(SEPARATORS).filter { it.isNotEmpty() }
            if (segments.size < 2) return null
            return AnchorPath(null, segments, isUnc = true)
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
     * would collapse into one, the later one gets a counter (`name~2`, `name~3`, the first free one) and
     * [onKeyCollision] is called: its original name is never kept, because that name is the very thing
     * that is being removed from the text (a path of the user's home), and neither value is lost.
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
                if (rebuilt.has(newKey)) {
                    onKeyCollision()
                    newKey = firstFreeKey(rebuilt, newKey)
                }
                if (newKey != key || newValue !== value) changed = true
                rebuilt.add(newKey, newValue)
            }
            return if (changed) rebuilt else element
        }
        return element
    }

    /** `key~2`, `key~3`, ... the first one [taken] does not hold yet. */
    private fun firstFreeKey(taken: JsonObject, key: String): String {
        var counter = 2
        while (taken.has("$key~$counter")) counter++
        return "$key~$counter"
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
