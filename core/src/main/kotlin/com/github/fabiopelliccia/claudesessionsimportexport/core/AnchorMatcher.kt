package com.github.fabiopelliccia.claudesessionsimportexport.core

import java.util.regex.Matcher
import java.util.regex.Pattern

/** A way an anchor shows up in text: the regex (no capturing group inside) and the style it stands for. */
internal class PathAlternative(val regex: String, val style: (String) -> PathStyle)

/**
 * The 8.3 spelling of a home folder, as regex fragments: its [drive] letter, the folder names
 * ([segments]) and the same path as a Claude Code folder name ([encoded]).
 */
internal class ShortPattern(val drive: String, val segments: List<String>, val encoded: String)

/**
 * Every spelling of one anchor, as one regex of alternatives (each in its own capturing group, so the
 * alternative that matched tells the style). Matching is case-insensitive.
 *
 * **Separators.** Between two folder names any run of backslashes and slashes is accepted, in any mix
 * (`C:\Users/alice`, `C:\\Users\\alice`, `C:\/Users\/alice`, `C://Users//alice`), and so is a backslash
 * written as the JSON escape `\u005c`. The first separator gives the style: one backslash is `win`,
 * two `esc1`, four `esc2`, eight `esc3`, a slash `fwd`.
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
 */
internal class AnchorMatcher(val kind: PathKind, val path: AnchorPath, shortPatterns: List<ShortPattern>) {

    private val alternatives: List<PathAlternative> = build(path, shortPatterns, withoutDrive = kind == PathKind.HOME && path.drive != null)

    val pattern: Pattern = Pattern.compile(
        alternatives.joinToString("|") { "(${it.regex})" },
        Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE,
    )

    /** The style of the alternative that just matched. */
    fun styleOf(matcher: Matcher): PathStyle {
        for (index in alternatives.indices) {
            if (matcher.start(index + 1) >= 0) return alternatives[index].style(matcher.group(index + 1))
        }
        error("No alternative matched")
    }

    private companion object {

        // ---- Separators. Possessive quantifiers throughout: a long run of backslashes costs one pass.

        /**
         * One or more separators of any kind: a run of backslashes, which may end in the JSON escape of
         * a backslash or slash (`\u005c`, `\u002f`), or a slash.
         */
        const val ANY_SEPARATOR = """(?:\\++(?:u00(?:5[cC]|2[fF]))?|/)++"""

        /** One or more separators that hold a slash: `/`, the JSON-escaped `\/`, or `\u002f`. */
        const val SLASH_SEPARATOR = """(?:\\*+/|\\++u002[fF])++"""

        /**
         * The one separator an anchor starts with. A single one: in `file:///home/alice` only the last slash
         * belongs to the anchor, the other two are the URI's and must stay where they are.
         */
        const val LEADING_SLASH = """(?:\\*+/|\\++u002[fF])"""

        /** What a UNC path starts with: `\\`, `//`, or the extended `\\?\UNC\`, at any escaping level. */
        const val UNC_LEAD = """(?:\\++\?\\++UNC\\++|\\{2,}+|/{2,}+)"""

        /** A path without its drive starts with a backslash (also written as the JSON escape `\`). */
        const val ROOT_RELATIVE_LEAD = """\\++(?:u005[cC])?"""

        const val URL_SEPARATOR = "(?:/|%2[Ff])"
        const val URL_BACKSLASH_SEPARATOR = "%5[Cc]"
        const val URL_COLON = "(?::|%3[Aa])"

        // ---- What may precede an anchor of one folder (an anchor of two or more needs nothing).

        /** The `n`, `t` or `r` of a JSON escape seen in raw text: `...\nC:\\Users`. */
        const val JSON_ESCAPE_BEFORE = """(?<=\\[ntr])"""

        /** The end of an ANSI colour sequence: ESC[34m (also as the raw JSON text `\u001b[34m`). */
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

        fun fixed(style: PathStyle, regex: String) = PathAlternative(regex) { style }

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

                for (short in shortPatterns) list += shortHomeAlternatives(short)
                list += gitBashWslAndCygwinAlternatives(drive.toString(), names, AFTER, slashBefore)
                // C:\Users\alice, C:/Users/alice, C:\\Users\\alice, ... (the style is the first separator's)
                list += PathAlternative(driveBefore + drive + ":" + chain(ANY_SEPARATOR, names) + AFTER) { match -> styleOfSeparator(match, 2) }
                // Claude Code project folder: C--Users-alice
                encodedAlternative(encodedFolderName)?.let { list += it }
                // Percent-encoded: C%3A%5CUsers%5Calice, and C:/Users/alice or C%3A%2FUsers%2Falice
                list += fixed(PathStyle.URL_BACKSLASH, urlBefore + drive + URL_COLON + chain(URL_BACKSLASH_SEPARATOR, urlNames) + AFTER_URL)
                list += fixed(PathStyle.URL, urlBefore + drive + URL_COLON + chain(URL_SEPARATOR, urlNames) + AFTER_URL)
                if (withoutDrive) list += withoutDriveAlternative(names)
            } else if (path.isUnc) {
                // \\server\share\alice, //server/share/alice, \\?\UNC\server\share\alice, with doubled backslashes inside JSON
                list += PathAlternative(UNC_LEAD + names.first() + chain(ANY_SEPARATOR, names.drop(1)) + AFTER) { match -> styleOfUncLead(match) }
                // Claude Code project folder: --server-share-alice
                encodedAlternative(encodedFolderName)?.let { list += it }
            } else {
                val slashBefore = if (isShortAnchor) SLASH_BEFORE_SHORT_ANCHOR else ""
                val urlBefore = if (isShortAnchor) URL_BEFORE_SHORT_ANCHOR else ""
                // /home/alice, also as the JSON-escaped \/home\/alice
                list += fixed(PathStyle.FWD, slashBefore + LEADING_SLASH + names.first() + chain(SLASH_SEPARATOR, names.drop(1)) + AFTER)
                // Claude Code project folder: -home-alice
                encodedAlternative(encodedFolderName)?.let { list += it }
                // Percent-encoded: %2Fhome%2Falice
                list += fixed(PathStyle.URL, urlBefore + chain(URL_SEPARATOR, urlNames) + AFTER_URL)
            }
            return list
        }

        /** `\Users\alice` (HOMEPATH): backslashes only, as a slash would be any POSIX path; the level is the lead's. */
        fun withoutDriveAlternative(names: List<String>): PathAlternative =
            PathAlternative(ROOT_RELATIVE_LEAD + names.first() + chain(ANY_SEPARATOR, names.drop(1)) + AFTER) { match ->
                when (styleOfSeparator(match, 0)) {
                    PathStyle.WIN -> PathStyle.REL
                    PathStyle.ESC1 -> PathStyle.REL1
                    else -> PathStyle.REL2
                }
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
                fixed(PathStyle.WSL, before + LEADING_SLASH + "mnt" + SLASH_SEPARATOR + driveRegex + folders + after),
                fixed(PathStyle.CYG, before + LEADING_SLASH + "cygdrive" + SLASH_SEPARATOR + driveRegex + folders + after),
                fixed(PathStyle.MSYS, before + LEADING_SLASH + driveRegex + folders + after),
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
            val list = mutableListOf<PathAlternative>()
            // C:\Users\ALICE~1, C:/Users/ALICE~1, C:\\Users\\ALICE~1, ...
            list += PathAlternative(before + short.drive + ":" + chain(ANY_SEPARATOR, short.segments) + AFTER_SHORT) { match ->
                shortStyleOf(styleOfSeparator(match, 2))
            }
            // Git Bash 8.3 home: /c/Users/ALICE~1, WSL: /mnt/c/Users/ALICE~1, Cygwin: /cygdrive/c/Users/ALICE~1
            list += gitBashWslAndCygwinAlternatives(short.drive, short.segments, AFTER_SHORT, slashBefore)
            // Claude Code project folder of the 8.3 home: C--Users-ALICE-1
            list += fixed(PathStyle.ENC, ENC_BEFORE + short.encoded + AFTER_ENC)
            return list
        }

        /**
         * The style the first separator found at [from] stands for: a slash (also `\/` and `\u002f`) is
         * `fwd`, an escaped backslash `\u005c` counts as two, then one backslash is `win`, two `esc1`,
         * three or four `esc2`, more `esc3`.
         */
        fun styleOfSeparator(text: String, from: Int): PathStyle {
            var index = from
            while (index < text.length && text[index] == '\\') index++
            val backslashes = index - from
            if (index < text.length && text[index] == '/') return PathStyle.FWD
            if (backslashes > 0 && text.regionMatches(index, "u002f", 0, 5, ignoreCase = true)) return PathStyle.FWD
            // `\u005c` is what a decoded backslash looks like in raw JSON text: it stands for two.
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
            encodedFolderName?.let { fixed(PathStyle.ENC, ENC_BEFORE + Pattern.quote(it) + AFTER_ENC) }
    }
}
