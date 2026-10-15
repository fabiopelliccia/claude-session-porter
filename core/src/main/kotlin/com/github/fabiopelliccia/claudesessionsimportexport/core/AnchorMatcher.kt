package com.github.fabiopelliccia.claudesessionsimportexport.core

import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * What the first character of a match can be, so that the matcher can skip a position without trying the
 * alternatives: either a set of characters (a letter stands for both of its cases), or any letter that is
 * followed by a colon or a hyphen (the 8.3 forms on any drive: `X:\Users\...`, `X--Users-...`).
 */
internal class AlternativeStart private constructor(val characters: Set<Char>, val isAnyLetterBeforeColonOrHyphen: Boolean) {

    companion object {
        fun characters(members: String) = AlternativeStart(members.toSet(), false)

        val ANY_LETTER_BEFORE_COLON_OR_HYPHEN = AlternativeStart(emptySet(), true)
    }
}

/** A way an anchor shows up in text: the regex (no capturing group inside), how it starts and the style it stands for. */
internal class PathAlternative(val regex: String, val start: AlternativeStart, val style: (String) -> PathStyle)

/**
 * The 8.3 spelling of a home folder, as regex fragments: its [drive] letter (a letter, or `[A-Za-z]` for any
 * drive), the folder names ([segments]) and the same path as a Claude Code folder name ([encoded]).
 */
internal class ShortPattern(val drive: String, val segments: List<String>, val encoded: String) {

    val isOnAnyDrive: Boolean get() = drive.length > 1
}

/**
 * Every spelling of one anchor, as one regex of alternatives (each in its own capturing group, so the
 * alternative that matched tells the style). Matching is case-insensitive.
 *
 * **Separators.** Between two folder names any run of backslashes and slashes is accepted, in any mix
 * (`C:\Users/alice`, `C:\\Users\\alice`, `C:\/Users\/alice`, `C://Users//alice`), and so is a backslash
 * written as the JSON escape for it (a backslash and `u005c`). The first separator gives the style: one
 * backslash is `win`, two `esc1`, four `esc2`, eight `esc3`, a slash `fwd`.
 *
 * **Boundaries.** Before the anchor: an anchor of two or more folders (`C:\Users\alice`, `/home/alice`)
 * is recognised whatever precedes it, because a home inside a longer path (`/var/home/alice`,
 * `-IC:\Users\alice`) must not stay behind. An anchor of one folder (`/work`, `C:\work`) is too
 * short for that and must not continue a word or a path; it may still follow a compiler flag (`-I/work`),
 * an ANSI colour (`ESC[34m/work`) or the `n`/`t`/`r` of a JSON escape (`\n/work`). After the last
 * folder name no letter, digit, `_` or `~` may follow, nor a `.` or `-` followed by one of them, so
 * `alice2`, `alice.bak`, `alice-admin` and `ALICE~1` are no `alice`, while a sentence's final dot is
 * not part of the name. The encoded form joins its folders with hyphens, so there any character but a
 * letter or digit bounds the name.
 *
 * **Speed.** The regex is only tried at the positions where an alternative can start (see
 * [AlternativeStart]), found with a table lookup per character, instead of at every position: that is the
 * difference between a minute and a few seconds on a transcript of 50 MB.
 */
internal class AnchorMatcher(val kind: PathKind, val path: AnchorPath, shortPatterns: List<ShortPattern>) {

    private val alternatives: List<PathAlternative> = build(path, shortPatterns, withoutDrive = kind == PathKind.HOME && path.drive != null)

    private val pattern: Pattern = Pattern.compile(
        alternatives.joinToString("|") { "(${it.regex})" },
        Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE,
    )

    /** Which ASCII characters an alternative can start with, in either case. */
    private val canStartMatch = BooleanArray(ASCII_SIZE)

    private val canStartWithAnyLetter: Boolean = alternatives.any { it.start.isAnyLetterBeforeColonOrHyphen }

    init {
        for (alternative in alternatives) {
            for (character in alternative.start.characters) {
                canStartMatch[character.lowercaseChar().code] = true
                canStartMatch[character.uppercaseChar().code] = true
            }
        }
    }

    /**
     * Calls [onMatch] with the start, the end and the style of every match in [text], from left to right,
     * none overlapping; the same matches `Matcher.find` would find, without trying the regex at every position.
     */
    fun forEachMatch(text: String, onMatch: (start: Int, end: Int, style: PathStyle) -> Unit) {
        // Transparent bounds: what comes before the position is still seen by the boundaries of the pattern.
        val matcher = pattern.matcher(text).useTransparentBounds(true).useAnchoringBounds(false)
        var position = 0
        while (position < text.length) {
            val candidate = nextCandidate(text, position)
            if (candidate < 0) return
            matcher.region(candidate, text.length)
            if (matcher.lookingAt()) {
                onMatch(candidate, matcher.end(), styleOf(matcher))
                position = maxOf(matcher.end(), candidate + 1)
            } else {
                position = candidate + 1
            }
        }
    }

    /** The first position at or after [from] where a match can start, `-1` when there is none. */
    private fun nextCandidate(text: String, from: Int): Int {
        for (index in from until text.length) {
            val character = text[index]
            if (canStartAt(character)) return index
            if (canStartWithAnyLetter && isLetterBeforeColonOrHyphen(text, index)) return index
        }
        return -1
    }

    private fun canStartAt(character: Char): Boolean {
        if (character.code < ASCII_SIZE) return canStartMatch[character.code]
        // The regex ignores case with Unicode rules: the Kelvin sign is a K, the long s an s.
        val folded = Character.toLowerCase(Character.toUpperCase(character))
        return folded.code < ASCII_SIZE && canStartMatch[folded.code]
    }

    private fun isLetterBeforeColonOrHyphen(text: String, index: Int): Boolean {
        if (index + 1 >= text.length) return false
        val next = text[index + 1]
        if (next != ':' && next != '-') return false
        val character = text[index]
        return character in 'A'..'Z' || character in 'a'..'z' || (character.code >= ASCII_SIZE && canStartAtAnyLetter(character))
    }

    private fun canStartAtAnyLetter(character: Char): Boolean {
        val folded = Character.toLowerCase(Character.toUpperCase(character))
        return folded in 'a'..'z'
    }

    /** The style of the alternative that just matched. */
    private fun styleOf(matcher: Matcher): PathStyle {
        for (index in alternatives.indices) {
            if (matcher.start(index + 1) >= 0) return alternatives[index].style(matcher.group(index + 1))
        }
        error("No alternative matched")
    }

    private companion object {

        // ---- Separators. Possessive quantifiers throughout: a long run of backslashes costs one pass.

        /**
         * One or more separators of any kind: a run of backslashes, which may end in the JSON escape of
         * a backslash or slash (u005c, u002f after the run), or a slash.
         */
        const val ANY_SEPARATOR = """(?:\\++(?:u00(?:5[cC]|2[fF]))?|/)++"""

        /** One or more separators that hold a slash: `/`, the JSON-escaped `\/`, or the escape u002f after a backslash. */
        const val SLASH_SEPARATOR = """(?:\\*+/|\\++u002[fF])++"""

        /**
         * The one separator an anchor starts with. A single one: in `file:///home/alice` only the last slash
         * belongs to the anchor, the other two are the URI's and must stay where they are.
         */
        const val LEADING_SLASH = """(?:\\*+/|\\++u002[fF])"""

        /** What a UNC path starts with: `\\`, `//`, or the extended `\\?\UNC\`, at any escaping level. */
        const val UNC_LEAD = """(?:\\++\?\\++UNC\\++|\\{2,}+|/{2,}+)"""

        /** A path without its drive starts with a backslash (or its u005c escape). */
        const val ROOT_RELATIVE_LEAD = """\\++(?:u005[cC])?"""

        const val URL_SEPARATOR = "(?:/|%2[Ff])"
        const val URL_ENCODED_SLASH = "%2[Ff]"
        const val URL_BACKSLASH_SEPARATOR = "%5[Cc]"
        const val URL_COLON = "(?::|%3[Aa])"

        // ---- What may precede an anchor of one folder (an anchor of two or more needs nothing).

        /** The `n`, `t` or `r` of a JSON escape seen in raw text: `...\nC:\\Users`. */
        const val JSON_ESCAPE_BEFORE = """(?<=\\[ntr])"""

        /** The end of an ANSI colour sequence: ESC[34m (also as the raw JSON text of the ESC character). */
        const val ANSI_COLOUR_BEFORE = """(?<=\[[0-9;]{0,12}m)"""

        /** A compiler or tool flag glued to the path: `-IC:\Users\alice\inc`, `-L/home/alice/lib`. */
        const val FLAG_BEFORE = """(?<=(?:^|[^A-Za-z0-9])-[A-Za-z])"""

        fun shortAnchorBefore(forbiddenBefore: String) =
            "(?:(?<![$forbiddenBefore])|$JSON_ESCAPE_BEFORE|$ANSI_COLOUR_BEFORE|$FLAG_BEFORE)"

        val DRIVE_BEFORE_SHORT_ANCHOR = shortAnchorBefore("A-Za-z0-9_")
        val SLASH_BEFORE_SHORT_ANCHOR = shortAnchorBefore("A-Za-z0-9_.~\\-")
        val URL_BEFORE_SHORT_ANCHOR = shortAnchorBefore("A-Za-z0-9_.~%\\-")

        /** The encoded form is never glued to a word, whatever its length. */
        const val ENC_BEFORE = """(?:(?<![A-Za-z0-9_])|(?<=\\[ntr])|(?<=\[[0-9;]{0,12}m))"""

        // ---- What may follow the last folder name.

        const val AFTER = """(?![\p{L}\p{N}_~]|[.\-][\p{L}\p{N}_])"""
        const val AFTER_SHORT = """(?![\p{L}\p{N}_])"""
        const val AFTER_ENC = """(?![A-Za-z0-9_]|\.[A-Za-z0-9_])"""
        const val AFTER_URL = """(?![\p{L}\p{N}_~]|[.\-][\p{L}\p{N}_]|%(?!2[Ff]|5[Cc])[0-9A-Fa-f]{2})"""

        // ---- How an alternative starts, to find the positions worth trying.

        const val ASCII_SIZE = 128

        val SLASH_OR_BACKSLASH_START = AlternativeStart.characters("""/\""")
        val BACKSLASH_START = AlternativeStart.characters("""\""")
        val SLASH_OR_PERCENT_START = AlternativeStart.characters("/%")

        fun startingWith(character: Char) = AlternativeStart.characters(character.toString())

        fun fixed(style: PathStyle, regex: String, start: AlternativeStart) = PathAlternative(regex, start) { style }

        fun chain(separator: String, parts: List<String>) = parts.joinToString("") { separator + it }

        /**
         * [withoutDrive] adds the path without its drive letter (`\Users\alice`), which is what `HOMEPATH`
         * holds and what follows the admin share of the drive in `\\localhost\c$\Users\alice`.
         */
        fun build(path: AnchorPath, shortPatterns: List<ShortPattern>, withoutDrive: Boolean): List<PathAlternative> {
            val names = path.segments.map { PathNamePatterns.plain(it) }
            val urlNames = path.segments.map { PathNamePatterns.url(it) }
            val encodedFolderName = path.encodedFolderName
            val isShortAnchor = path.segments.size < 2
            val list = mutableListOf<PathAlternative>()
            val drive = path.drive
            if (drive != null) {
                val driveBefore = if (isShortAnchor) DRIVE_BEFORE_SHORT_ANCHOR else ""
                val slashBefore = if (isShortAnchor) SLASH_BEFORE_SHORT_ANCHOR else ""
                val urlBefore = if (isShortAnchor) URL_BEFORE_SHORT_ANCHOR else ""
                val startsWithDrive = startingWith(drive)

                for (short in shortPatterns) list += shortHomeAlternatives(short)
                list += gitBashWslAndCygwinAlternatives(drive.toString(), names, AFTER, slashBefore)
                // C:\Users\alice, C:/Users/alice, C:\\Users\\alice, ... (the style is the first separator's)
                list += PathAlternative(driveBefore + drive + ":" + chain(ANY_SEPARATOR, names) + AFTER, startsWithDrive) { match -> styleOfSeparator(match, 2) }
                // Claude Code project folder: C--Users-alice
                encodedAlternative(encodedFolderName)?.let { list += it }
                // Percent-encoded: C%3A%5CUsers%5Calice, and C:/Users/alice or C%3A%2FUsers%2Falice
                list += fixed(PathStyle.URL_BACKSLASH, urlBefore + drive + URL_COLON + chain(URL_BACKSLASH_SEPARATOR, urlNames) + AFTER_URL, startsWithDrive)
                list += fixed(PathStyle.URL, urlBefore + drive + URL_COLON + chain(URL_SEPARATOR, urlNames) + AFTER_URL, startsWithDrive)
                if (withoutDrive) {
                    list += withoutDriveAlternative(names)
                    list += withoutDriveUrlAlternatives(path, urlNames)
                }
            } else if (path.isUnc) {
                // \\server\share\alice, //server/share/alice, \\?\UNC\server\share\alice, with doubled backslashes inside JSON
                list += PathAlternative(UNC_LEAD + names.first() + chain(ANY_SEPARATOR, names.drop(1)) + AFTER, SLASH_OR_BACKSLASH_START) { match -> styleOfUncLead(match) }
                // Claude Code project folder: --server-share-alice
                encodedAlternative(encodedFolderName)?.let { list += it }
            } else {
                val slashBefore = if (isShortAnchor) SLASH_BEFORE_SHORT_ANCHOR else ""
                val urlBefore = if (isShortAnchor) URL_BEFORE_SHORT_ANCHOR else ""
                // /home/alice, also as the JSON-escaped \/home\/alice
                list += fixed(PathStyle.FWD, slashBefore + LEADING_SLASH + names.first() + chain(SLASH_SEPARATOR, names.drop(1)) + AFTER, SLASH_OR_BACKSLASH_START)
                // Claude Code project folder: -home-alice
                encodedAlternative(encodedFolderName)?.let { list += it }
                // Percent-encoded: %2Fhome%2Falice
                list += fixed(PathStyle.URL, urlBefore + chain(URL_SEPARATOR, urlNames) + AFTER_URL, SLASH_OR_PERCENT_START)
            }
            return list
        }

        /** `\Users\alice` (HOMEPATH): backslashes only, as a slash would be any POSIX path; the level is the lead's. */
        fun withoutDriveAlternative(names: List<String>): PathAlternative =
            PathAlternative(ROOT_RELATIVE_LEAD + names.first() + chain(ANY_SEPARATOR, names.drop(1)) + AFTER, BACKSLASH_START) { match ->
                when (styleOfSeparator(match, 0)) {
                    PathStyle.WIN -> PathStyle.REL
                    PathStyle.ESC1 -> PathStyle.REL1
                    else -> PathStyle.REL2
                }
            }

        /**
         * `Users%5Calice` and `Users%2Falice`: the home without its drive, percent-encoded, and without a separator in front
         * (a separator written before it is not part of the match). Only an encoded separator: a plain slash would be any POSIX
         * path. Left out for a home of one folder, which would be a plain word.
         */
        fun withoutDriveUrlAlternatives(path: AnchorPath, urlNames: List<String>): List<PathAlternative> {
            if (path.segments.size < 2) return emptyList()
            val first = path.segments.first().first().takeIf { it.code < ASCII_SIZE } ?: return emptyList()
            val start = startingWith(first)
            return listOf(
                fixed(PathStyle.REL_URL_BACKSLASH, urlNames.first() + chain(URL_BACKSLASH_SEPARATOR, urlNames.drop(1)) + AFTER_URL, start),
                fixed(PathStyle.REL_URL, urlNames.first() + chain(URL_ENCODED_SLASH, urlNames.drop(1)) + AFTER_URL, start),
            )
        }

        /** The style of a UNC lead: a slash is `fwd`, then two backslashes are `win`, four `esc1`, eight `esc2`, more `esc3`. */
        fun styleOfUncLead(text: String): PathStyle {
            if (text.startsWith("/")) return PathStyle.FWD
            val backslashes = text.takeWhile { it == '\\' }.length
            return when {
                backslashes <= 3 -> PathStyle.WIN
                backslashes <= 6 -> PathStyle.ESC1
                backslashes <= 12 -> PathStyle.ESC2
                else -> PathStyle.ESC3
            }
        }

        /**
         * A Windows folder as the Unix-like layers over Windows spell it, [driveRegex] being the drive letter:
         * WSL `/mnt/c/Users/alice`, Cygwin `/cygdrive/c/Users/alice`, Git Bash and MSYS `/c/Users/alice`.
         */
        fun gitBashWslAndCygwinAlternatives(driveRegex: String, segments: List<String>, after: String, before: String): List<PathAlternative> {
            val folders = chain(SLASH_SEPARATOR, segments)
            return listOf(
                fixed(PathStyle.WSL, before + LEADING_SLASH + "mnt" + SLASH_SEPARATOR + driveRegex + folders + after, SLASH_OR_BACKSLASH_START),
                fixed(PathStyle.CYG, before + LEADING_SLASH + "cygdrive" + SLASH_SEPARATOR + driveRegex + folders + after, SLASH_OR_BACKSLASH_START),
                fixed(PathStyle.MSYS, before + LEADING_SLASH + driveRegex + folders + after, SLASH_OR_BACKSLASH_START),
            )
        }

        /**
         * The 8.3 home, in every spelling the long one has except the percent-encoded ones. The styles
         * `short*` render as the destination's long home (an 8.3 name of another PC is of no use there);
         * Git Bash, WSL, Cygwin, encoded and eight-backslash forms use the style of their plain counterpart.
         */
        fun shortHomeAlternatives(short: ShortPattern): List<PathAlternative> {
            val before = if (short.segments.size < 2) DRIVE_BEFORE_SHORT_ANCHOR else ""
            val slashBefore = if (short.segments.size < 2) SLASH_BEFORE_SHORT_ANCHOR else ""
            val startsWithDrive = if (short.isOnAnyDrive) AlternativeStart.ANY_LETTER_BEFORE_COLON_OR_HYPHEN else AlternativeStart.characters(short.drive)
            val list = mutableListOf<PathAlternative>()
            // C:\Users\ALICE~1, C:/Users/ALICE~1, C:\\Users\\ALICE~1, ...
            list += PathAlternative(before + short.drive + ":" + chain(ANY_SEPARATOR, short.segments) + AFTER_SHORT, startsWithDrive) { match ->
                shortStyleOf(styleOfSeparator(match, 2))
            }
            // Git Bash 8.3 home: /c/Users/ALICE~1, WSL: /mnt/c/Users/ALICE~1, Cygwin: /cygdrive/c/Users/ALICE~1
            list += gitBashWslAndCygwinAlternatives(short.drive, short.segments, AFTER_SHORT, slashBefore)
            // Claude Code project folder of the 8.3 home: C--Users-ALICE-1
            list += fixed(PathStyle.ENC, ENC_BEFORE + short.encoded + AFTER_ENC, startsWithDrive)
            return list
        }

        /**
         * The style the first separator found at [from] stands for: a slash (also the escaped slash, and the
         * escape u002f) is `fwd`, the escape u005c of a backslash counts as two, then one backslash is `win`,
         * two `esc1`, three or four `esc2`, more `esc3`.
         */
        fun styleOfSeparator(text: String, from: Int): PathStyle {
            var index = from
            while (index < text.length && text[index] == '\\') index++
            val backslashes = index - from
            if (index < text.length && text[index] == '/') return PathStyle.FWD
            if (backslashes > 0 && text.regionMatches(index, "u002f", 0, 5, ignoreCase = true)) return PathStyle.FWD
            // The escape of a backslash is what a decoded backslash looks like in raw JSON text: it stands for two.
            if (backslashes > 0 && text.regionMatches(index, "u005c", 0, 5, ignoreCase = true)) return styleOfBackslashes(2 * backslashes)
            return styleOfBackslashes(backslashes)
        }

        fun styleOfBackslashes(count: Int): PathStyle = when {
            count <= 1 -> PathStyle.WIN
            count == 2 -> PathStyle.ESC1
            count <= 4 -> PathStyle.ESC2
            else -> PathStyle.ESC3
        }

        fun shortStyleOf(style: PathStyle): PathStyle = when (style) {
            PathStyle.WIN -> PathStyle.SHORT
            PathStyle.FWD -> PathStyle.SHORT_FWD
            PathStyle.ESC1 -> PathStyle.SHORT_ESC1
            PathStyle.ESC2 -> PathStyle.SHORT_ESC2
            else -> style
        }

        /** The encoded form, left out when it has no letter or digit to tell it from a line of dashes. */
        fun encodedAlternative(encodedFolderName: String?): PathAlternative? =
            encodedFolderName?.let { fixed(PathStyle.ENC, ENC_BEFORE + Pattern.quote(it) + AFTER_ENC, startingWith(it.first())) }
    }
}
