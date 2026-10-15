package com.github.fabiopelliccia.claudesessionsimportexport.core

import java.nio.file.Path
import java.nio.file.Paths

/**
 * The anchors of the exporting machine, everything [PathAnonymizer] looks for:
 *
 * - [home], the home of the account whose sessions are exported ([PathKind.HOME]);
 * - [otherHomes], more homes that stand for the same thing: the home of the account that runs the
 *   export when the sessions belong to another one (`--home C:\Users\alice\.claude` run by `svc-build`),
 *   or the Windows home of a user whose Claude home is in WSL ([PathKind.HOME]);
 * - [claudeHome], the Claude Code home, wherever it is ([PathKind.CLAUDE_HOME]). Below a home it is
 *   longer than the home, so it wins there: a path into it is rendered on import as the Claude home of
 *   the destination, which is not `<destination home>\.claude` when that one is relocated;
 * - [roots], the project root of each session to export, which may be anywhere ([PathKind.ROOT]);
 * - [shortHomes], the 8.3 spellings of the home a Windows temp folder can carry (`C:\Users\ALICE~1`);
 * - [userName], the leaf of the home, searched on its own for the residual report, and [otherNames],
 *   more names of the same person: the OS account name (`user.name`), the leaf of each of [otherHomes].
 *
 * Tokens carry no index, so every root of one identity renders back to the same destination folder:
 * build one identity (and one [PathAnonymizer]) per session, with that session's single root.
 *
 * Values are normalized like [ClaudePaths.normalizeProjectPath] does (the separator of the path's own
 * style, no trailing separator). A relative path is not an anchor and is ignored.
 * [detect] reads the running machine; the constructor takes explicit values, which is what tests use
 * since the real home is never a stable fixture.
 */
class SourceIdentity(
    home: String,
    claudeHome: String? = null,
    roots: List<String> = emptyList(),
    shortHomes: List<String> = emptyList(),
    userName: String? = null,
    otherHomes: List<String> = emptyList(),
    otherNames: List<String> = emptyList(),
) {

    val home: String = ClaudePaths.normalizeProjectPath(home)

    val roots: List<String> = roots.map(ClaudePaths::normalizeProjectPath).distinct()

    val claudeHome: String? = claudeHome?.let(ClaudePaths::normalizeProjectPath)

    val shortHomes: List<String> = shortHomes.map(ClaudePaths::normalizeProjectPath).distinct()

    val os: PathOs = PathOs.of(this.home)

    val userName: String = userName ?: AnchorPath.parse(this.home)?.leaf ?: this.home

    val otherHomes: List<String> = otherHomes.map(ClaudePaths::normalizeProjectPath).distinct()
        .filter { other -> !sameFolder(other, this.home) }

    /** The other names a person goes by, as they were given (no blank one, no repetition). */
    val otherNames: List<String> = otherNames.map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    /** The home and every other home, the primary one first. */
    val allHomes: List<String> = listOf(this.home) + this.otherHomes

    /**
     * Every name the residual check looks for on its own: [userName], the leaf of each other home and [otherNames],
     * the first spelling of a name that differs from another one only by case.
     */
    val allUserNames: List<String> =
        (listOf(this.userName) + this.otherHomes.mapNotNull { AnchorPath.parse(it)?.leaf } + this.otherNames)
            .distinctBy { it.lowercase() }

    /** Whether [name] is one of [allUserNames], whatever the case. */
    fun isUserName(name: String): Boolean = allUserNames.any { it.equals(name, ignoreCase = true) }

    /**
     * The first six letters and digits of the user name in capitals: the stem Windows gives the 8.3
     * name of a longer folder (`ALICE~1`, `ZOMLLE~1`). Empty when there is none.
     *
     * Windows reads a dotted name as base and extension: `alice.smith` becomes `ALICE~1.SMI`, so the
     * stem is taken from the part before the last dot and [shortExtension] is `SMI`.
     */
    val shortStem: String

    /** The three letters and digits Windows keeps of the part after the last dot (`SMI` for `alice.smith`), empty without a dot. */
    val shortExtension: String

    /**
     * Every stem an 8.3 name of the home may start with: [shortStem], and the first six letters and
     * digits of the whole name with its dots left out (`ALICES` for `alice.smith`), which is not what
     * Windows does but is accepted anyway, since leaving a home behind costs more than matching too much.
     */
    val shortStems: List<String>

    init {
        val short = ShortName.of(this.userName)
        shortStem = short.stem
        shortExtension = short.extension
        shortStems = short.stems
    }

    /** The 8.3 name of a folder called [name]: the stem, the extension of a dotted name, and every stem it may start with. */
    internal class ShortName(val stem: String, val extension: String, val stems: List<String>) {

        companion object {
            private const val SHORT_STEM_LENGTH = 6
            private const val SHORT_EXTENSION_LENGTH = 3

            fun of(name: String): ShortName {
                val lastDot = name.lastIndexOf('.')
                val hasExtension = lastDot in 1 until name.length - 1
                val stem = lettersAndDigitsInCapitals(if (hasExtension) name.substring(0, lastDot) else name).take(SHORT_STEM_LENGTH)
                val extension = if (hasExtension) lettersAndDigitsInCapitals(name.substring(lastDot + 1)).take(SHORT_EXTENSION_LENGTH) else ""
                val wholeNameStem = lettersAndDigitsInCapitals(name).take(SHORT_STEM_LENGTH)
                // Windows keeps a hyphen or an underscore in an 8.3 name (`svc-build` is `SVC-BU~1`).
                val stemWithPunctuation = lettersDigitsAndDashesInCapitals(if (hasExtension) name.substring(0, lastDot) else name).take(SHORT_STEM_LENGTH)
                return ShortName(stem, extension, listOf(stem, stemWithPunctuation, wholeNameStem).filter { it.isNotEmpty() }.distinct())
            }

            private fun lettersAndDigitsInCapitals(text: String): String =
                text.uppercase().filter { it in 'A'..'Z' || it in '0'..'9' }

            private fun lettersDigitsAndDashesInCapitals(text: String): String =
                text.uppercase().filter { it in 'A'..'Z' || it in '0'..'9' || it == '-' || it == '_' }
        }
    }

    fun withRoots(roots: List<String>): SourceIdentity =
        SourceIdentity(home, claudeHome, roots, shortHomes, userName, otherHomes, otherNames)

    companion object {

        /** The folder every Claude Code home is called, which is how the home of its owner is found. */
        private const val CLAUDE_FOLDER = ".claude"

        /** `\\wsl.localhost\<distro>\rest` and `\\wsl$\<distro>\rest`: how Windows sees the file system of a WSL distribution. */
        private val WSL_SHARE = Regex("""^\\\\wsl(?:\.localhost|\$)\\([^\\]+)(\\.*)?$""", RegexOption.IGNORE_CASE)

        private val WSL_SERVERS = listOf("wsl.localhost", "wsl$")

        private fun sameFolder(first: String, second: String): Boolean {
            val firstPath = AnchorPath.parse(first) ?: return first.equals(second, ignoreCase = true)
            val secondPath = AnchorPath.parse(second) ?: return false
            return firstPath.key == secondPath.key
        }

        /**
         * The running machine, as far as it is the owner of what is exported: `user.home` and `user.name`, the
         * given Claude home, and the 8.3 spellings found by [shortFormsOf] among `TEMP`, `TMP` and `java.io.tmpdir`.
         *
         * The sessions belong to whoever owns [claudeHome], which is not always the account that runs the export:
         * `--home C:\Users\alice\.claude` run by `svc-build`, or a Claude home inside WSL
         * (`\\wsl.localhost\Ubuntu\home\alice\.claude`, whose paths are `/home/alice/...`). When the Claude home is
         * called `.claude` and lies in a home that is not [home], that home becomes the primary one and [home] an
         * additional one: both are anonymized, and the names of both counted. Parameters other than [claudeHome]
         * exist for tests.
         */
        fun detect(
            claudeHome: Path = ClaudePaths.resolveHome(),
            roots: List<String> = emptyList(),
            home: String = System.getProperty("user.home"),
            tempFolders: List<String> = defaultTempFolders(),
            realPathOf: (String) -> String? = ::realPath,
            accountName: String? = System.getProperty("user.name"),
        ): SourceIdentity {
            val share = parseWslShare(claudeHome.toString())
            // What the paths of the sessions say is what the distribution says: `/home/alice/.claude`.
            val claudeHomeAsPaths = share?.posixPath ?: claudeHome.toString()
            val machineHome = ClaudePaths.normalizeProjectPath(home)
            val owner = homeContaining(claudeHomeAsPaths)
            val ownedByAnotherHome = owner != null && !sameFolder(owner, machineHome)
            // The same home as Windows spells it when it reaches into the distribution: `\\wsl.localhost\Ubuntu\home\alice`.
            val homesAsShares = if (share != null && owner != null) share.windowsSpellingsOf(owner) else emptyList()
            return SourceIdentity(
                home = if (owner != null && ownedByAnotherHome) owner else machineHome,
                claudeHome = claudeHomeAsPaths,
                roots = roots,
                shortHomes = shortFormsOf(home, tempFolders, realPathOf),
                otherHomes = (if (ownedByAnotherHome) listOf(machineHome) else emptyList()) + homesAsShares,
                otherNames = listOfNotNull(accountName),
            )
        }

        /**
         * The folder that holds a Claude home called `.claude` (`C:\Users\alice` for `C:\Users\alice\.claude`), or `null`
         * for any other Claude home. At least two folder names are needed: `D:\tools` is no home of a person.
         */
        private fun homeContaining(claudeHome: String): String? {
            val path = AnchorPath.parse(ClaudePaths.normalizeProjectPath(claudeHome)) ?: return null
            if (!path.leaf.equals(CLAUDE_FOLDER, ignoreCase = true)) return null
            return path.parent?.takeIf { it.segments.size >= 2 }?.native
        }

        /** A path inside a WSL distribution seen from Windows: the [distribution] it is in and the [posixPath] it has there. */
        internal class WslShare(val distribution: String, val posixPath: String) {

            /** [posixFolder] (a folder of the distribution) spelled as a Windows share, with both names of the server. */
            fun windowsSpellingsOf(posixFolder: String): List<String> =
                WSL_SERVERS.map { server -> "\\\\$server\\$distribution" + posixFolder.replace('/', '\\') }
        }

        /** The [WslShare] [path] names, or `null` when it is not a share of a WSL distribution. */
        internal fun parseWslShare(path: String): WslShare? {
            val unified = path.trim().replace('/', '\\')
            val match = WSL_SHARE.matchEntire(unified) ?: return null
            val rest = match.groupValues[2]
            return WslShare(match.groupValues[1], if (rest.isEmpty()) "/" else rest.replace('\\', '/'))
        }

        /**
         * The 8.3 form of [home]: a temp folder whose real path lies below the home but whose raw
         * text does not start with it is spelled with the short name (`C:\Users\ALICE~1\AppData\Local\Temp`
         * for `C:\Users\alice\AppData\Local\Temp`), and the first folders of the raw text, as many as the
         * home has, are the short home. [realPathOf] returns the resolved path or `null`.
         */
        fun shortFormsOf(home: String, tempFolders: List<String>, realPathOf: (String) -> String?): List<String> {
            val homePath = AnchorPath.parse(ClaudePaths.normalizeProjectPath(home)) ?: return emptyList()
            if (homePath.drive == null) return emptyList()
            val found = LinkedHashMap<String, String>()
            for (raw in tempFolders) {
                if (raw.isBlank()) continue
                val rawPath = AnchorPath.parse(ClaudePaths.normalizeProjectPath(raw)) ?: continue
                if (rawPath.startsWith(homePath)) continue
                if (rawPath.drive == null || rawPath.segments.size < homePath.segments.size) continue
                val real = realPathOf(raw)?.let { AnchorPath.parse(ClaudePaths.normalizeProjectPath(it)) } ?: continue
                if (!real.startsWith(homePath)) continue
                val short = "${rawPath.drive}:\\" + rawPath.segments.take(homePath.segments.size).joinToString("\\")
                found.putIfAbsent(short.lowercase(), short)
            }
            return found.values.toList()
        }

        private fun defaultTempFolders(): List<String> =
            listOfNotNull(System.getenv("TEMP"), System.getenv("TMP"), System.getProperty("java.io.tmpdir"))

        private fun realPath(path: String): String? = runCatching { Paths.get(path).toRealPath().toString() }.getOrNull()
    }
}
