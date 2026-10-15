package com.github.fabiopelliccia.claudesessionsimportexport.core

import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/** What an anonymized path anchor stands for, see [SourceIdentity]. */
enum class PathKind {
    /** The exporting user's home folder (`user.home`). */
    HOME,

    /** A session's project root, wherever it lives (it may well be outside the home). */
    ROOT,

    /** The Claude Code home, only when it is not below [HOME]. */
    CLAUDE_HOME,
}

/** How a path is spelled: a drive letter and backslashes, or a leading slash. */
enum class PathOs {
    WINDOWS,
    POSIX,
    ;

    companion object {
        fun of(path: String): PathOs = if (ClaudePaths.isWindowsStyle(path)) WINDOWS else POSIX
    }
}

/**
 * The spelling an anchor was found in, which is also what the import renders it back as.
 *
 * Escaping levels are counted against the text the pattern is applied to: `win` is `C:\Users\alice`
 * (one backslash per separator), `esc1` is `C:\\Users\\alice` (two - the path as it reads inside a
 * JSON string, or inside a JSON document stored in a string), `esc2` is four, `esc3` eight. A
 * transcript line scanned as raw text shows every decoded path one level deeper, so a plain `win`
 * path of a decoded string is `esc1` there; both entry points of [PathAnonymizer] and
 * [PathDeanonymizer] are consistent with themselves, see their class docs.
 *
 * [SHORT], [SHORT_FWD], [SHORT_ESC1] and [SHORT_ESC2] are the 8.3 form of the home (`C:\Users\ALICE~1`)
 * at the corresponding level; [URL] and [URL_BACKSLASH] are the percent-encoded forms (separators
 * `/` or `%2F`, respectively `%5C`).
 *
 * [REL], [REL1] and [REL2] are the home without its drive letter, at one, two and four backslashes per
 * separator: `HOMEPATH=\Users\alice`, or what follows the admin share in `\\localhost\c$\Users\alice`. It is
 * rendered as the destination's path without its drive, so whatever stood in front of it stays valid.
 */
enum class PathStyle(
    val token: String,
    /** Backslashes making up one separator in this style, 0 when it is not a backslash style. */
    val backslashes: Int = 0,
) {
    WIN("win", 1),
    FWD("fwd"),
    ESC1("esc1", 2),
    ESC2("esc2", 4),
    ESC3("esc3", 8),
    MSYS("msys"),
    WSL("wsl"),
    CYG("cyg"),
    SHORT("short", 1),
    SHORT_FWD("shortfwd"),
    SHORT_ESC1("short1", 2),
    SHORT_ESC2("short2", 4),
    ENC("enc"),
    URL("url"),
    URL_BACKSLASH("urlbs"),
    REL("rel", 1),
    REL1("rel1", 2),
    REL2("rel2", 4),
    ;

    companion object {
        private val byToken = entries.associateBy { it.token }

        fun fromToken(token: String): PathStyle? = byToken[token]
    }
}

/** A parsed token: what it stands for and how the original was spelled. */
data class PathToken(val kind: PathKind, val style: PathStyle)

/**
 * The token grammar that replaces an anchor in an anonymized archive: `{{<prefix>:<KIND>:<style>}}`,
 * for example `{{SP:HOME:win}}\note.txt`, `{{SP:ROOT:enc}}`, `{{SP:ROOT:esc2}}`. The token holds no
 * backslash, quote or other character a JSON string would escape, so it can sit in a string value, a
 * key or raw text alike.
 *
 * The prefix is `SP` unless the source already contains `{{SP` somewhere - then [choosePrefix] picks
 * a nonce such as `SP7f3a`, which the manifest records, and a literal `{{SP:HOME:win}}` of the
 * source is left alone by the import because it only recognises the archive's own prefix.
 */
object PathTokens {

    const val DEFAULT_PREFIX = "SP"

    /** What a source must not contain for [DEFAULT_PREFIX] to be safe. */
    const val MARKER = "{{SP"

    private val PREFIX_SHAPE = Regex("[A-Za-z][A-Za-z0-9]*")
    private val regexes = ConcurrentHashMap<String, Regex>()
    private val random = SecureRandom()

    fun render(kind: PathKind, style: PathStyle, prefix: String = DEFAULT_PREFIX): String =
        "{{$prefix:${kind.name}:${style.token}}}"

    /** The token spelled by the whole of [text], or `null` when [text] is anything else. */
    fun parse(text: String, prefix: String = DEFAULT_PREFIX): PathToken? {
        val match = regex(prefix).matchEntire(text) ?: return null
        return PathToken(PathKind.valueOf(match.groupValues[1]), PathStyle.fromToken(match.groupValues[2])!!)
    }

    /** Finds every token of [prefix]: group 1 is the kind, group 2 the style. */
    fun regex(prefix: String = DEFAULT_PREFIX): Regex {
        requireValidPrefix(prefix)
        return regexes.getOrPut(prefix) {
            val styles = PathStyle.entries.joinToString("|") { Regex.escape(it.token) }
            val kinds = PathKind.entries.joinToString("|") { it.name }
            Regex("""\{\{${Regex.escape(prefix)}:($kinds):($styles)}}""")
        }
    }

    fun contains(text: String, prefix: String = DEFAULT_PREFIX): Boolean =
        text.contains("{{$prefix:") && regex(prefix).containsMatchIn(text)

    fun count(text: String, prefix: String = DEFAULT_PREFIX): Int =
        if (text.contains("{{$prefix:")) regex(prefix).findAll(text).count() else 0

    /**
     * [DEFAULT_PREFIX] when no source holds [MARKER], otherwise `SP` plus four random hex digits
     * that no source holds either. [isUsed] answers "does any content of the archive contain this
     * text" and is asked for [MARKER] first, then for each nonce candidate (`{{SP7f3a`).
     */
    fun choosePrefix(isUsed: (String) -> Boolean): String {
        if (!isUsed(MARKER)) return DEFAULT_PREFIX
        while (true) {
            val candidate = DEFAULT_PREFIX + "%04x".format(random.nextInt(0x10000))
            if (!isUsed("{{$candidate")) return candidate
        }
    }

    fun requireValidPrefix(prefix: String) {
        require(PREFIX_SHAPE.matches(prefix)) { "Invalid token prefix: $prefix" }
    }
}
