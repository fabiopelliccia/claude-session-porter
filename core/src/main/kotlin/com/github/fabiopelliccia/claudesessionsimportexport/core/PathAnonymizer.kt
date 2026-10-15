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
 * `alice.bak` and `alice-admin` from being read as `alice`; see [AnchorMatcher] for the exact rules.
 * What follows an anchor is left as it was, so `C:\Users\alice\note.txt` becomes
 * `{{SP:HOME:win}}\note.txt`.
 *
 * Privacy wins over tidiness: an anchor of two or more folders is replaced wherever it appears, even
 * inside a longer path (`/var/home/alice`), and a name is looked for in every spelling a tool writes it
 * in (see [PathNamePatterns]). What is not found stays and is counted by the residual check.
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

        /** Object keys that two anonymized names would have collapsed into one: the later one got a `~2`-style counter. */
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

    /** What the residual search needs in a text to find anything, so that the regex is not run on every text. */
    private val residualNeedles: List<String>

    init {
        PathTokens.requireValidPrefix(tokenPrefix)
        val candidates = mutableListOf<Pair<PathKind, AnchorPath>>()
        identity.roots.forEach { root -> AnchorPath.parse(root)?.let { candidates += PathKind.ROOT to it } }
        identity.claudeHome?.let { AnchorPath.parse(it) }?.let { candidates += PathKind.CLAUDE_HOME to it }
        identity.allHomes.forEach { home -> AnchorPath.parse(home)?.let { candidates += PathKind.HOME to it } }

        val seen = HashSet<String>()
        val anchors = candidates.filter { (_, path) -> seen.add(path.key) }
            .sortedByDescending { (_, path) -> path.segments.size }

        val shorts = shortPatterns(identity)
        matchers = anchors.map { (kind, path) ->
            AnchorMatcher(kind, path, if (kind == PathKind.HOME) shorts else emptyList())
        }
        needles = needlesOf(anchors.map { it.second }, identity)
        val residualNames = residualNamesFor(identity)
        residual = residualPatternOf(residualNames)
        residualNeedles = PathNeedles.of(emptyList(), residualNames, emptyList(), emptyList())
    }

    /**
     * Cheap pre-filter: `false` guarantees [text] holds no anchor, so it need not be parsed or matched.
     * It looks for a text every spelling of the leaf name of each anchor, of the user name and of the 8.3
     * stem has in common, whatever the case; see [PathNeedles].
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
        stats.residualUserNameHits += countResidualUserName(current)
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
     * Number of hits in a binary file, in UTF-8 and in UTF-16LE (both byte alignments), which is how Windows
     * tools store text: anchors, and the bare name of the user (a name of 4 characters or more). Counts only: a
     * binary is never rewritten.
     */
    fun scanBytes(bytes: ByteArray): Int {
        if (bytes.isEmpty() || !bytesMayContainCandidate(bytes)) return 0
        // One view at a time: a binary of many megabytes is not copied three times over.
        val views: Sequence<() -> String> = sequenceOf(
            { String(bytes, Charsets.UTF_8) },
            { String(bytes, Charsets.UTF_16LE) },
            { if (bytes.size > 1) String(bytes, 1, bytes.size - 1, Charsets.UTF_16LE) else "" },
        )
        return views.sumOf { decode ->
            val stats = Stats()
            anonymizeText(decode(), stats)
            // An anchor, or the bare name of the user: either one is something of the user in a file nothing can be removed from.
            stats.totalReplacements + stats.residualUserNameHits
        }
    }

    /**
     * The pre-filter on raw bytes: `false` when none of the needles is in [bytes], as UTF-8 or as UTF-16LE
     * (each character followed by a byte of its own, which is not checked), so that no view of them is
     * worth decoding. A needle with a character outside ASCII is not looked for in bytes: the answer is `true`.
     */
    private fun bytesMayContainCandidate(bytes: ByteArray): Boolean =
        needles.any { needle -> needle.any { it.code >= ASCII_LIMIT } || containsAscii(bytes, needle, 1) || containsAscii(bytes, needle, 2) }

    /** The ASCII [needle] in [bytes], whatever the case, its characters [stride] bytes apart. */
    private fun containsAscii(bytes: ByteArray, needle: String, stride: Int): Boolean {
        val lastStart = bytes.size - (needle.length - 1) * stride - 1
        for (start in 0..lastStart) {
            var matched = 0
            while (matched < needle.length && asciiLowercase(bytes[start + matched * stride].toInt()) == asciiLowercase(needle[matched].code)) matched++
            if (matched == needle.length) return true
        }
        return false
    }

    private fun asciiLowercase(code: Int): Int = if (code in 'A'.code..'Z'.code) code + CASE_OFFSET else code

    /**
     * Whole-word hits of the user name in [text]; zero when the name is shorter than 4 characters. The tokens
     * of this anonymizer do not count: a user called `root` or `home` is not in every `ROOT` or `HOME` token.
     */
    fun countResidualUserName(text: String): Int {
        if (residual == null) return 0
        val withoutOurTokens = withoutTokens(text)
        if (residualNeedles.none { JsonLines.containsIgnoreCase(withoutOurTokens, it) }) return 0
        return countMatches(residual, withoutOurTokens)
    }

    /** [text] with every token of this anonymizer replaced by a space. */
    private fun withoutTokens(text: String): String {
        if (!PathTokens.contains(text, tokenPrefix)) return text
        return PathTokens.regex(tokenPrefix).replace(text, " ")
    }

    private fun replace(matcher: AnchorMatcher, text: String, stats: Stats): String {
        var out: StringBuilder? = null
        var last = 0
        matcher.forEachMatch(text) { start, end, style ->
            val builder = out ?: StringBuilder(text.length + 32).also { out = it }
            builder.append(text, last, start).append(PathTokens.render(matcher.kind, style, tokenPrefix))
            stats.count(matcher.kind, style)
            last = end
        }
        return out?.append(text, last, text.length)?.toString() ?: text
    }

    private fun countMatches(pattern: Pattern, text: String): Int {
        val found = pattern.matcher(text)
        var count = 0
        while (found.find()) count++
        return count
    }

    private companion object {
        const val MIN_RESIDUAL_LENGTH = 4
        const val ASCII_LIMIT = 128

        /** The distance between `A` and `a`. */
        const val CASE_OFFSET = 'a'.code - 'A'.code

        /**
         * The whole-word search for the bare user name, or `null` for a name too short to be told from
         * ordinary words. A dotted name (`alice.smith`, a corporate account) is looked for as a whole and
         * by each of its parts, since a conversation says "alice" more often than "alice.smith".
         */
        fun residualPatternOf(names: List<String>): Pattern? {
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

        /** [residualNamesOf] for every name of [identity]: the account, its other homes and its other names. */
        fun residualNamesFor(identity: SourceIdentity): List<String> =
            identity.allUserNames.flatMap { residualNamesOf(it) }.distinctBy { it.lowercase() }

        /**
         * The explicit 8.3 homes plus the generic `<drive>:\Users\<STEM>~<n>[.<EXT>]` fallback for each
         * stem of the identity (see [SourceIdentity.shortStems]), Windows only.
         */
        fun shortPatterns(identity: SourceIdentity): List<ShortPattern> {
            val windowsHomes = identity.allHomes.withIndex().mapNotNull { (index, home) -> AnchorPath.parse(home)?.takeIf { it.isWindows }?.let { index to it } }
            if (windowsHomes.isEmpty()) return emptyList()
            val list = mutableListOf<ShortPattern>()
            for (short in identity.shortHomes) {
                val path = AnchorPath.parse(short) ?: continue
                val drive = path.drive ?: continue
                list += ShortPattern(drive.toString(), path.segments.map { Pattern.quote(it) }, Pattern.quote(ClaudePaths.encodeProjectPath(short)))
            }
            for ((index, path) in windowsHomes) {
                // The folders above the home are spelled in full (`Users`): only the home's own name gets an 8.3 name.
                val foldersAboveHome = path.segments.dropLast(1)
                // The first home is the identity's own (its user name may be given); the others are named by their folder.
                val shortName = if (index == 0) SourceIdentity.ShortName(identity.shortStem, identity.shortExtension, identity.shortStems) else SourceIdentity.ShortName.of(path.leaf)
                for (stem in shortName.stems) {
                    list += genericShortPattern(foldersAboveHome, stem, shortName.extension)
                }
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

        fun needlesOf(anchors: List<AnchorPath>, identity: SourceIdentity): List<String> =
            PathNeedles.of(
                anchors = anchors,
                userNames = identity.allUserNames + residualNamesFor(identity),
                shortStems = identity.shortStems + identity.otherHomes.flatMap { home -> AnchorPath.parse(home)?.let { SourceIdentity.ShortName.of(it.leaf).stems }.orEmpty() },
                shortHomeLeaves = identity.shortHomes.mapNotNull { AnchorPath.parse(it)?.leaf },
            )
    }
}
