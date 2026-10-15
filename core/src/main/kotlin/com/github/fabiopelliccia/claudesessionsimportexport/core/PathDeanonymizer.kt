package com.github.fabiopelliccia.claudesessionsimportexport.core

/**
 * Renders the tokens of an anonymized archive (see [PathTokens]) for the machine importing it: what
 * [PathAnonymizer] replaced becomes the destination's own folders, in the spelling the token names.
 *
 * - `win` is the destination path as the destination writes it (`C:\Users\bob`, `/home/bob`),
 *   `fwd` the same with forward slashes, `escN` the native path with every separator doubled N times
 *   (the level of the *text the renderer is applied to*, see [PathAnonymizer]), `short*` the destination's
 *   long home in the corresponding spelling (the destination has no use for an 8.3 name of another PC);
 * - `msys` (`/c/Users/bob`), `wsl` (`/mnt/c/...`) and `cyg` (`/cygdrive/c/...`) keep that form on a
 *   Windows destination and are the native path on a POSIX one;
 * - `enc` is [ClaudePaths.encodeProjectPath] of the destination, `url` and `urlbs` its percent-encoded
 *   spellings (`Zo%C3%AB%20M%C3%BCller`).
 *
 * The roots: [root] stands for [PathKind.ROOT] (the folder the sessions are attached to), [home] for
 * [PathKind.HOME] and [claudeHome] for [PathKind.CLAUDE_HOME]. A token whose destination is `null`
 * is left in place and counted as unresolved.
 *
 * What follows a token keeps its source separators, with one exception that makes a path usable:
 * when the destination is POSIX and the token was a backslash style (`win`, `escN`, `short`, `short1`...),
 * or when the source was POSIX ([sourceOs]) and the destination is Windows, the separators of the path
 * run right after the token (up to a space, quote or other character a path does not hold) are turned
 * into the destination's. So `{{SP:HOME:win}}\proj\a.txt` is `/Users/bob/proj/a.txt` on a Mac and
 * `{{SP:HOME:fwd}}/proj/a.txt` of a Linux source is `C:\Users\bob\proj\a.txt` on Windows - while on a
 * Windows source and destination the tail is left untouched. A `fwd` token of a Windows source
 * (`C:/Users/alice`, a URI) stays forward-slashed.
 *
 * Like the anonymizer, [deanonymizeJsonLine] works on decoded strings and keys and [deanonymizeText]
 * on the text as it is; the escaping of what it writes is the caller's text level, so on a JSON line
 * everything is re-encoded by the serializer.
 */
class PathDeanonymizer(
    root: String?,
    home: String,
    claudeHome: String,
    val os: PathOs = PathOs.of(home),
    val tokenPrefix: String = PathTokens.DEFAULT_PREFIX,
    val sourceOs: PathOs? = null,
) {

    /** What a pass did: counts only. */
    class Stats {
        val renderedByKind: MutableMap<String, Int> = sortedMapOf()
        val renderedByStyle: MutableMap<String, Int> = sortedMapOf()

        /** Tokens of a kind the deanonymizer has no destination for, left as they were. */
        var unresolvedTokens = 0
        var parsedLines = 0
        var rewrittenLines = 0
        var unparsableLines = 0
        var keyCollisions = 0

        val totalRendered: Int get() = renderedByKind.values.sum()
    }

    private class Destination(raw: String) {
        val native: String = ClaudePaths.normalizeProjectPath(raw)
        val path: AnchorPath? = AnchorPath.parse(native)

        /** The drive letter of a destination that is a bare drive (`D:\`): it has no folder, hence no [path]. */
        val driveRoot: Char? = if (native.length == DRIVE_ROOT_LENGTH && native[1] == ':' && native[2] == '\\') native[0] else null
    }

    private val destinations: Map<PathKind, Destination> = buildMap {
        root?.takeIf { it.isNotBlank() }?.let { put(PathKind.ROOT, Destination(it)) }
        put(PathKind.HOME, Destination(home))
        put(PathKind.CLAUDE_HOME, Destination(claudeHome))
    }

    private val tokenRegex = PathTokens.regex(tokenPrefix)
    private val marker = "{{$tokenPrefix:"

    /** The destination spelled as the token [style] asks, or `null` when no destination of [kind] is known. */
    fun render(kind: PathKind, style: PathStyle): String? {
        val destination = destinations[kind] ?: return null
        val windows = os == PathOs.WINDOWS
        val native = destination.native
        val path = destination.path
        val driveRoot = destination.driveRoot
        return when (style) {
            PathStyle.WIN, PathStyle.SHORT -> native
            PathStyle.FWD ->
                if (sourceOs == PathOs.POSIX && windows) native else native.replace('\\', '/')
            PathStyle.SHORT_FWD -> native.replace('\\', '/')
            PathStyle.ESC1, PathStyle.SHORT_ESC1 -> native.replace("\\", "\\".repeat(2))
            PathStyle.ESC2, PathStyle.SHORT_ESC2 -> native.replace("\\", "\\".repeat(4))
            PathStyle.ESC3 -> native.replace("\\", "\\".repeat(8))
            PathStyle.MSYS -> unixLike(path, driveRoot, "/", windows, native)
            PathStyle.WSL -> unixLike(path, driveRoot, "/mnt/", windows, native)
            PathStyle.CYG -> unixLike(path, driveRoot, "/cygdrive/", windows, native)
            PathStyle.ENC -> ClaudePaths.encodeProjectPath(native)
            PathStyle.URL -> url(path, driveRoot, "/", ":", native)
            PathStyle.URL_BACKSLASH -> url(path, driveRoot, "%5C", "%3A", native)
            PathStyle.REL -> withoutDrive(path, native, backslashesPerSeparator = 1)
            PathStyle.REL1 -> withoutDrive(path, native, backslashesPerSeparator = 2)
            PathStyle.REL2 -> withoutDrive(path, native, backslashesPerSeparator = 4)
        }
    }

    /** `\Users\bob` for a destination `C:\Users\bob`, the path itself when it has no drive letter. */
    private fun withoutDrive(path: AnchorPath?, native: String, backslashesPerSeparator: Int): String {
        if (path?.drive == null) return native
        return native.substring(DRIVE_PREFIX_LENGTH).replace("\\", "\\".repeat(backslashesPerSeparator))
    }

    /** Renders every token of [text] (taken as it is) for the destination. */
    fun deanonymizeText(text: String, stats: Stats = Stats()): String {
        if (!text.contains(marker)) return text
        val found = tokenRegex.toPattern().matcher(text)
        var index = 0
        var out: StringBuilder? = null
        var copied = 0
        while (index <= text.length && found.find(index)) {
            val kind = PathKind.valueOf(found.group(1))
            val style = PathStyle.fromToken(found.group(2))!!
            val rendered = render(kind, style)
            if (rendered == null) {
                stats.unresolvedTokens++
                index = found.end()
                continue
            }
            if (out == null) out = StringBuilder(text.length + 64)
            val context = contextOf(text, found.start(), copied, style, rendered)
            out.append(text, copied, context.prefixStart).append(context.prefixReplacement).append(context.rendered)
            stats.renderedByKind.merge(kind.name, 1, Int::plus)
            stats.renderedByStyle.merge(style.token, 1, Int::plus)
            var end = found.end()
            if (context.convertsTail) end = appendTail(out, text, end, style, context.rendered)
            copied = end
            index = end
        }
        if (out == null) return text
        return out.append(text, copied, text.length).toString()
    }

    /**
     * What stands in front of a token and changes how it must be rendered: the text from [prefixStart] to
     * the token is replaced by [prefixReplacement], the token itself by [rendered].
     */
    private class TokenContext(
        val prefixStart: Int,
        val prefixReplacement: String,
        val rendered: String,
        val convertsTail: Boolean = true,
    )

    /**
     * A token is usually rendered as it is. Two prefixes ask for more: a `file:` URI, which has forward
     * slashes only and puts a drive letter after three of them (`file:///C:/Users/bob`, `file:///Users/bob`),
     * and the extended-length prefix `\\?\` or `\\.\`, which means nothing on a POSIX destination.
     */
    private fun contextOf(text: String, tokenStart: Int, copied: Int, style: PathStyle, rendered: String): TokenContext {
        val plain = TokenContext(tokenStart, "", rendered)
        val lookBehindStart = maxOf(copied, tokenStart - PREFIX_LOOK_BEHIND)
        val before = text.substring(lookBehindStart, tokenStart)
        if (style == PathStyle.FWD || style == PathStyle.URL) {
            val fileUri = FILE_URI_PREFIX.find(before) ?: return plain
            return fileUriContext(lookBehindStart + fileUri.range.first, fileUri.value, rendered)
        }
        if (os == PathOs.POSIX && style.backslashes > 0) {
            val extended = EXTENDED_LENGTH_PREFIX.find(before) ?: return plain
            return TokenContext(lookBehindStart + extended.range.first, "", rendered)
        }
        return plain
    }

    /** [prefix] is `file:/`, `file://`, `file:///` or `file://host/`; the path is written with forward slashes only. */
    private fun fileUriContext(prefixStart: Int, prefix: String, rendered: String): TokenContext {
        val path = rendered.replace('\\', '/')
        val hasHost = prefix.startsWith("file://") && !prefix.startsWith("file:///") && prefix.length > "file://".length
        val replacement = when {
            // file://localhost/ + /Users/bob: the path brings its own slash
            hasHost -> if (path.startsWith("/")) prefix.dropLast(1) else prefix
            // file:/, file:// or file:/// + /Users/bob: three slashes in all
            path.startsWith("/") -> prefix.substring(0, "file:".length) + "//"
            // file:// + C:/Users/bob: the drive needs a slash of its own
            prefix.length == "file://".length -> "file:///"
            else -> prefix
        }
        return TokenContext(prefixStart, replacement, path, convertsTail = false)
    }

    /**
     * Copies what follows a token and returns where the copy ends. A destination that is a root (`D:\`, `/`)
     * ends with a separator already, so the tail's first one is dropped; and the tail may be converted to
     * the destination's separators, see [tailSeparators].
     */
    private fun appendTail(out: StringBuilder, text: String, from: Int, style: PathStyle, rendered: String): Int {
        val separators = tailSeparators(style)
        val sourceSeparator = separators?.first ?: (if (style.backslashes > 0) "\\".repeat(style.backslashes) else "/")
        var position = from
        val dropsFirstSeparator = (rendered.endsWith("\\") || rendered.endsWith("/")) && text.startsWith(sourceSeparator, position)
        if (dropsFirstSeparator) position += sourceSeparator.length
        if (separators != null && (dropsFirstSeparator || text.startsWith(separators.first, position))) {
            val tail = convertTail(text, position, separators.first, separators.second)
            out.append(tail.first)
            return tail.second
        }
        return position
    }

    /**
     * Renders the tokens of one transcript line: every string value and every object key of the
     * parsed line, re-emitted (with [SessionRedactor]'s settings) only if something changed, and any
     * other line untouched. A line that does not parse as JSON goes through [deanonymizeText].
     */
    fun deanonymizeJsonLine(line: String, stats: Stats = Stats()): String {
        if (!line.contains(marker)) return line
        val element = JsonLines.parse(line)
        if (element == null) {
            stats.unparsableLines++
            return deanonymizeText(line, stats)
        }
        stats.parsedLines++
        val mapped = JsonLines.mapStrings(element, { deanonymizeText(it, stats) }, { stats.keyCollisions++ })
        if (mapped === element) return line
        stats.rewrittenLines++
        return JsonLines.toJson(mapped)
    }

    /** A whole transcript, line endings and unchanged lines kept as they were, like [PathAnonymizer.anonymizeJsonl]. */
    fun deanonymizeJsonl(text: String, stats: Stats = Stats()): String =
        JsonLines.mapLines(text) { deanonymizeJsonLine(it, stats) }

    private fun unixLike(path: AnchorPath?, driveRoot: Char?, prefix: String, windows: Boolean, native: String): String {
        if (windows && driveRoot != null) return prefix + driveRoot.lowercaseChar()
        if (!windows || path?.drive == null) return native
        return prefix + path.drive.lowercaseChar() + path.segments.joinToString("") { "/$it" }
    }

    private fun url(path: AnchorPath?, driveRoot: Char?, separator: String, colon: String, native: String): String {
        if (driveRoot != null) return "$driveRoot$colon"
        if (path == null) return native
        val names = path.segments.joinToString("") { separator + percentEncode(it) }
        return if (path.drive != null) "${path.drive}$colon$names" else if (separator == "/") names else "%2F" + path.segments.joinToString("%2F") { percentEncode(it) }
    }

    /** The source and destination separators of a tail that must be converted, `null` when it is kept. */
    private fun tailSeparators(style: PathStyle): Pair<String, String>? = when {
        os == PathOs.POSIX && style.backslashes > 0 -> "\\".repeat(style.backslashes) to "/"
        // `C:/Users/alice\sub`: a Windows path that mixes both separators
        os == PathOs.POSIX && style == PathStyle.FWD && sourceOs == PathOs.WINDOWS -> "\\" to "/"
        os == PathOs.WINDOWS && style == PathStyle.FWD && sourceOs == PathOs.POSIX -> "/" to "\\"
        else -> null
    }

    /** Copies the path run starting at [from], replacing [source] separators with [destination]. */
    private fun convertTail(text: String, from: Int, source: String, destination: String): Pair<String, Int> {
        val out = StringBuilder()
        var i = from
        while (i < text.length) {
            if (text.startsWith(source, i)) {
                out.append(destination)
                i += source.length
                continue
            }
            val c = text[i]
            if (c.isWhitespace() || c in STOP_CHARS) break
            out.append(c)
            i++
        }
        return out.toString() to i
    }

    private companion object {
        /**
         * Characters that end a path run: a backslash left over is a separator of another level. A slash
         * does not end it: it is a separator too, a path may mix both (`C:\Users\alice\sub/dir\x.txt`).
         */
        const val STOP_CHARS = "\\\"'`<>|*?:;,{}"

        /** How far before a token the prefixes of [contextOf] are looked for. */
        const val PREFIX_LOOK_BEHIND = 64

        /** `file:/`, `file://`, `file:///` or `file://localhost/`, right before a token. */
        val FILE_URI_PREFIX = Regex("""file:(?://[^/\s"'<>|]+/|/{1,3})$""", RegexOption.IGNORE_CASE)

        /** `\\?\` or `\\.\`, at any escaping level, right before a token. */
        val EXTENDED_LENGTH_PREFIX = Regex("""\\{2,}[?.]\\+$""")

        /** The length of `C:`. */
        const val DRIVE_PREFIX_LENGTH = 2

        /** The length of `D:\`. */
        const val DRIVE_ROOT_LENGTH = 3

        fun percentEncode(text: String): String {
            val out = StringBuilder()
            for (byte in text.toByteArray(Charsets.UTF_8)) {
                val c = (byte.toInt() and 0xFF).toChar()
                if (c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c in "-._~") out.append(c) else out.append('%').append("%02X".format(byte.toInt() and 0xFF))
            }
            return out.toString()
        }
    }
}
