package com.github.fabiopelliccia.claudesessionsimportexport.core

import java.util.regex.Pattern

/**
 * Replaces every path anchor of a [SourceIdentity] with a typed token (see [PathTokens]), so what
 * leaves the machine names neither the account nor its folders, in whatever spelling it was written
 * (the styles are listed on [PathStyle]). [PathDeanonymizer] renders the tokens for another machine.
 *
 * Matching is case-insensitive, per anchor and per style, the longest anchor first: a project root
 * below the home wins over the home, and `C--Users-alice-IdeaProjects-X` is `{{SP:ROOT:enc}}` when X
 * is the root but `{{SP:HOME:enc}}-Other` for any other folder. Boundaries keep `alice2`,
 * `alice.bak` and `ALICE~1` from being read as `alice`; see [AnchorMatcher] for the exact rules.
 * What follows an anchor is left as it was, so `C:\Users\alice\note.txt` becomes
 * `{{SP:HOME:win}}\note.txt`.
 *
 * **Escaping levels.** The anonymizer never decodes anything itself, it matches the text it is given
 * and names the level it finds: `win` has one backslash per separator, `esc1` two, `esc2` four.
 *
 * - [anonymizeJsonLine] decodes the line, so a path of a decoded string is `win` (or `fwd`...) and a JSON
 *   document stored inside a string, which spells its own paths `C:\\Users\\alice`, is `esc1`.
 * - [anonymizeText] sees the text as it is: on the raw, undecoded text of a transcript line (the
 *   fallback for a line that is not valid JSON) the same paths show one level deeper, `esc1` and `esc2`.
 *   Both are consistent with their own [PathDeanonymizer] counterpart, which renders `escN` with N
 *   doublings *of the text it is applied to*; mixing the entry points on one line is not supported.
 *
 * Counts go into the [Stats] passed in; the instance itself holds no state, so one anonymizer can be
 * shared. The residual user-name count is taken on what is left after the replacement.
 */
class PathAnonymizer(
    val identity: SourceIdentity,
    val tokenPrefix: String = PathTokens.DEFAULT_PREFIX,
) {

    /** What a pass did: counts only, never a value of the text. */
    class Stats {
        val replacedByKind: MutableMap<String, Int> = sortedMapOf()
        val replacedByStyle: MutableMap<String, Int> = sortedMapOf()

        /** Whole-word occurrences of the bare user name left after the replacement (name of 4+ characters). */
        var residualUserNameHits = 0

        /** JSON lines parsed (those that passed the pre-filter), rewritten, and passed over as raw text. */
        var parsedLines = 0
        var rewrittenLines = 0
        var unparsableLines = 0

        /** Object keys that two anonymized names would have collapsed into one, left unrenamed. */
        var keyCollisions = 0

        val totalReplacements: Int get() = replacedByKind.values.sum()

        internal fun count(kind: PathKind, style: PathStyle) {
            replacedByKind.merge(kind.name, 1, Int::plus)
            replacedByStyle.merge(style.token, 1, Int::plus)
        }
    }

    private val matchers: List<AnchorMatcher>
    private val needles: List<String>
    private val residual: Pattern?

    init {
        PathTokens.requireValidPrefix(tokenPrefix)
        val candidates = mutableListOf<Pair<PathKind, AnchorPath>>()
        identity.roots.forEach { root -> AnchorPath.parse(root)?.let { candidates += PathKind.ROOT to it } }
        identity.claudeHome?.let { AnchorPath.parse(it) }?.let { candidates += PathKind.CLAUDE_HOME to it }
        AnchorPath.parse(identity.home)?.let { candidates += PathKind.HOME to it }

        val seen = HashSet<String>()
        val anchors = candidates.filter { (_, path) -> seen.add(path.key) }
            .sortedByDescending { (_, path) -> path.segments.size }

        val shorts = shortPatterns(identity)
        matchers = anchors.map { (kind, path) ->
            AnchorMatcher(kind, path, if (kind == PathKind.HOME) shorts else emptyList())
        }
        needles = needlesOf(anchors.map { it.second }, identity, shorts)
        residual = residualPatternOf(identity.userName)
    }

    /**
     * Cheap pre-filter: `false` guarantees [text] holds no anchor, so it need not be parsed or matched.
     * It looks for the leaf name of each anchor (plain, encoded as a project folder, percent-encoded
     * and JSON-escaped), the user name and the 8.3 stem, whatever the case.
     */
    fun mayContainCandidate(text: String): Boolean = needles.any { JsonLines.containsIgnoreCase(text, it) }

    /**
     * Replaces the anchors of [text] taken as it is, see the class doc for the escaping levels. Used
     * for text files, entry names, manifest values and the lines that are not valid JSON.
     */
    fun anonymizeText(text: String, stats: Stats = Stats()): String {
        if (!mayContainCandidate(text)) return text
        var current = text
        for (matcher in matchers) current = replace(matcher, current, stats)
        if (residual != null) stats.residualUserNameHits += countMatches(residual, current)
        return current
    }

    /**
     * Anonymizes one transcript line: parses it, rewrites every string value **and** every object key
     * (`trackedFileBackups` is keyed by file path), and re-emits it with the settings of
     * [SessionRedactor] only if something changed; any other line comes back as the very same string.
     * A line that is not a JSON object or array, or does not parse, goes through [anonymizeText].
     */
    fun anonymizeJsonLine(line: String, stats: Stats = Stats()): String {
        if (!mayContainCandidate(line)) return line
        val element = JsonLines.parse(line)
        if (element == null) {
            stats.unparsableLines++
            return anonymizeText(line, stats)
        }
        stats.parsedLines++
        val mapped = JsonLines.mapStrings(element, { anonymizeText(it, stats) }, { stats.keyCollisions++ })
        if (mapped === element) return line
        stats.rewrittenLines++
        return JsonLines.toJson(mapped)
    }

    /**
     * A whole transcript: [anonymizeJsonLine] on every non-blank line, keeping `\r\n` or `\n` as each
     * line had it, a missing final newline, blank lines, and every unchanged line byte for byte.
     */
    fun anonymizeJsonl(text: String, stats: Stats = Stats()): String =
        JsonLines.mapLines(text) { anonymizeJsonLine(it, stats) }

    /**
     * Number of anchor hits in a binary file, in UTF-8 and in UTF-16LE (both byte alignments), which
     * is how Windows tools store text. Counts only: a binary is never rewritten.
     */
    fun scanBytes(bytes: ByteArray): Int {
        if (bytes.isEmpty()) return 0
        val views = listOf(
            String(bytes, Charsets.UTF_8),
            String(bytes, Charsets.UTF_16LE),
            if (bytes.size > 1) String(bytes, 1, bytes.size - 1, Charsets.UTF_16LE) else "",
        )
        return views.sumOf { view ->
            val stats = Stats()
            anonymizeText(view, stats)
            stats.totalReplacements
        }
    }

    /** Whole-word hits of the user name in [text]; zero when the name is shorter than 4 characters. */
    fun countResidualUserName(text: String): Int = residual?.let { countMatches(it, text) } ?: 0

    private fun replace(matcher: AnchorMatcher, text: String, stats: Stats): String {
        val found = matcher.pattern.matcher(text)
        if (!found.find()) return text
        val out = StringBuilder(text.length + 32)
        var last = 0
        do {
            out.append(text, last, found.start())
            val style = matcher.styleOf(found)
            out.append(PathTokens.render(matcher.kind, style, tokenPrefix))
            stats.count(matcher.kind, style)
            last = found.end()
        } while (found.find())
        return out.append(text, last, text.length).toString()
    }

    private fun countMatches(pattern: Pattern, text: String): Int {
        val found = pattern.matcher(text)
        var count = 0
        while (found.find()) count++
        return count
    }

    private companion object {
        const val MIN_RESIDUAL_LENGTH = 4

        /**
         * The whole-word search for the bare user name, or `null` for a name too short to be told from
         * ordinary words. A dotted name (`alice.smith`, a corporate account) is looked for as a whole and
         * by each of its parts, since a conversation says "alice" more often than "alice.smith".
         */
        fun residualPatternOf(userName: String): Pattern? {
            val names = residualNamesOf(userName)
            if (names.isEmpty()) return null
            val alternatives = names.joinToString("|") { PathNamePatterns.plain(it) }
            return Pattern.compile("(?<![\\p{L}\\p{N}_])(?:$alternatives)(?![\\p{L}\\p{N}_])", Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE)
        }

        /** The user name and, for a dotted one, its dot-separated parts: what the residual check looks for. Empty for a short name. */
        fun residualNamesOf(userName: String): List<String> {
            if (userName.length < MIN_RESIDUAL_LENGTH) return emptyList()
            val parts = userName.split('.').filter { it != userName && it.length >= MIN_RESIDUAL_LENGTH }
            return listOf(userName) + parts
        }

        /**
         * The explicit 8.3 homes plus the generic `<drive>:\Users\<STEM>~<n>[.<EXT>]` fallback for each
         * stem of the identity (see [SourceIdentity.shortStems]), Windows only.
         */
        fun shortPatterns(identity: SourceIdentity): List<ShortPattern> {
            if (identity.os != PathOs.WINDOWS) return emptyList()
            val list = mutableListOf<ShortPattern>()
            for (short in identity.shortHomes) {
                val path = AnchorPath.parse(short) ?: continue
                val drive = path.drive ?: continue
                list += ShortPattern(drive.toString(), path.segments.map { Pattern.quote(it) }, Pattern.quote(ClaudePaths.encodeProjectPath(short)))
            }
            // The folders above the home are spelled in full (`Users`): only the home's own name gets an 8.3 name.
            val foldersAboveHome = AnchorPath.parse(identity.home)?.segments?.dropLast(1).orEmpty()
            for (stem in identity.shortStems) {
                list += genericShortPattern(foldersAboveHome, stem, identity.shortExtension)
            }
            return list
        }

        /** `<any drive>:\<folders>\STEM~<n>`, with the extension of a dotted name optional (`ALICE~1.SMI`). */
        fun genericShortPattern(foldersAboveHome: List<String>, stem: String, extension: String): ShortPattern {
            val optionalExtension = if (extension.isEmpty()) "" else "(?:\\." + Pattern.quote(extension) + ")?"
            val optionalEncodedExtension = if (extension.isEmpty()) "" else "(?:-" + Pattern.quote(extension) + ")?"
            val shortName = Pattern.quote(stem) + "~[0-9]+" + optionalExtension
            val encodedFolders = foldersAboveHome.joinToString("") { "-" + Pattern.quote(ClaudePaths.encodeProjectPath(it)) }
            // The 8.3 name `ALICE~1` reads `ALICE-1` once encoded as a project folder name.
            val encoded = "[A-Za-z]-" + encodedFolders + "-" + Pattern.quote(stem) + "-[0-9]+" + optionalEncodedExtension
            return ShortPattern("[A-Za-z]", foldersAboveHome.map { Pattern.quote(it) } + shortName, encoded)
        }

        fun needlesOf(anchors: List<AnchorPath>, identity: SourceIdentity, shorts: List<ShortPattern>): List<String> {
            val names = linkedSetOf<String>()
            fun add(name: String) {
                if (name.isNotEmpty()) names += name.lowercase()
            }
            // An anchor below another one is spelled with the other's folder names too (every form of
            // a project root under the home names the home), so its own leaf, often a one-letter
            // `src` or `X`, would only let every line through the filter.
            val outermost = anchors.filter { anchor -> anchors.none { other -> other !== anchor && anchor.startsWith(other) } }
            for (leaf in outermost.map { it.leaf } + identity.userName + residualNamesOf(identity.userName)) {
                add(leaf)
                add(ClaudePaths.encodeProjectPath(leaf))
                add(percentEncode(leaf))
                add(jsonEscape(leaf))
            }
            identity.shortStems.forEach(::add)
            // `ZO5F2~1` is `ZO5F2-1` as a project folder name: only what comes before the tilde is in every spelling.
            identity.shortHomes.mapNotNull { AnchorPath.parse(it)?.leaf?.substringBefore('~') }.forEach(::add)
            return names.toList()
        }

        fun percentEncode(text: String): String {
            val out = StringBuilder()
            for (byte in text.toByteArray(Charsets.UTF_8)) {
                val c = (byte.toInt() and 0xFF).toChar()
                if (c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c in "-._~") out.append(c) else out.append('%').append("%02X".format(byte.toInt() and 0xFF))
            }
            return out.toString()
        }

        /** Non-ASCII characters as a JSON writer without `ensure ASCII off` spells them: `\u00eb`. */
        fun jsonEscape(text: String): String =
            text.map { if (it.code < 128) it.toString() else "\\u%04x".format(it.code) }.joinToString("")
    }
}
