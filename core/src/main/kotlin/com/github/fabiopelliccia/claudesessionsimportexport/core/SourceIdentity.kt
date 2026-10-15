package com.github.fabiopelliccia.claudesessionsimportexport.core

import java.nio.file.Path
import java.nio.file.Paths

/**
 * The anchors of the exporting machine, everything [PathAnonymizer] looks for:
 *
 * - [home], the `user.home` of the account ([PathKind.HOME]);
 * - [claudeHome], the Claude Code home, kept only when it is not below [home] (otherwise it is already
 *   covered, and `null`) ([PathKind.CLAUDE_HOME]);
 * - [roots], the project root of each session to export, which may be anywhere ([PathKind.ROOT]);
 * - [shortHomes], the 8.3 spellings of the home a Windows temp folder can carry (`C:\Users\ALICE~1`);
 * - [userName], the leaf of the home, searched on its own for the residual report.
 *
 * Tokens carry no index, so every root of one identity renders back to the same destination folder:
 * build one identity (and one [PathAnonymizer]) per session, with that session's single root.
 *
 * Values are normalized like [ClaudePaths.normalizeProjectPath] does (the separator of the path's own
 * style, no trailing separator). A relative path or a UNC share is not an anchor and is ignored.
 * [detect] reads the running machine; the constructor takes explicit values, which is what tests use
 * since the real home is never a stable fixture.
 */
class SourceIdentity(
    home: String,
    claudeHome: String? = null,
    roots: List<String> = emptyList(),
    shortHomes: List<String> = emptyList(),
    userName: String? = null,
) {

    val home: String = ClaudePaths.normalizeProjectPath(home)

    val roots: List<String> = roots.map(ClaudePaths::normalizeProjectPath).distinct()

    val claudeHome: String? = claudeHome?.let(ClaudePaths::normalizeProjectPath)?.takeUnless { candidate ->
        val inside = AnchorPath.parse(candidate)
        val homePath = AnchorPath.parse(this.home)
        inside != null && homePath != null && inside.startsWith(homePath)
    }

    val shortHomes: List<String> = shortHomes.map(ClaudePaths::normalizeProjectPath).distinct()

    val os: PathOs = PathOs.of(this.home)

    val userName: String = userName ?: AnchorPath.parse(this.home)?.leaf ?: this.home

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
        val name = this.userName
        val lastDot = name.lastIndexOf('.')
        val hasExtension = lastDot in 1 until name.length - 1
        shortStem = lettersAndDigitsInCapitals(if (hasExtension) name.substring(0, lastDot) else name).take(SHORT_STEM_LENGTH)
        shortExtension = if (hasExtension) lettersAndDigitsInCapitals(name.substring(lastDot + 1)).take(SHORT_EXTENSION_LENGTH) else ""
        val wholeNameStem = lettersAndDigitsInCapitals(name).take(SHORT_STEM_LENGTH)
        shortStems = listOf(shortStem, wholeNameStem).filter { it.isNotEmpty() }.distinct()
    }

    fun withRoots(roots: List<String>): SourceIdentity =
        SourceIdentity(home, claudeHome, roots, shortHomes, userName)

    companion object {

        private const val SHORT_STEM_LENGTH = 6
        private const val SHORT_EXTENSION_LENGTH = 3

        private fun lettersAndDigitsInCapitals(text: String): String =
            text.uppercase().filter { it in 'A'..'Z' || it in '0'..'9' }

        /**
         * The running machine: `user.home`, the given Claude home, and the 8.3 spellings found by
         * [shortFormsOf] among `TEMP`, `TMP` and `java.io.tmpdir`. Parameters other than [claudeHome]
         * exist for tests.
         */
        fun detect(
            claudeHome: Path = ClaudePaths.resolveHome(),
            roots: List<String> = emptyList(),
            home: String = System.getProperty("user.home"),
            tempFolders: List<String> = defaultTempFolders(),
            realPathOf: (String) -> String? = ::realPath,
        ): SourceIdentity = SourceIdentity(
            home = home,
            claudeHome = claudeHome.toString(),
            roots = roots,
            shortHomes = shortFormsOf(home, tempFolders, realPathOf),
        )

        /**
         * The 8.3 form of [home]: a temp folder whose real path lies below the home but whose raw
         * text does not start with it is spelled with the short name (`C:\Users\ALICE~1\AppData\Local\Temp`
         * for `C:\Users\alice\AppData\Local\Temp`), and the first folders of the raw text, as many as the
         * home has, are the short home. [realPathOf] returns the resolved path or `null`.
         */
        fun shortFormsOf(home: String, tempFolders: List<String>, realPathOf: (String) -> String?): List<String> {
            val homePath = AnchorPath.parse(ClaudePaths.normalizeProjectPath(home)) ?: return emptyList()
            if (!homePath.isWindows) return emptyList()
            val found = LinkedHashMap<String, String>()
            for (raw in tempFolders) {
                if (raw.isBlank()) continue
                val rawPath = AnchorPath.parse(ClaudePaths.normalizeProjectPath(raw)) ?: continue
                if (rawPath.startsWith(homePath)) continue
                if (!rawPath.isWindows || rawPath.segments.size < homePath.segments.size) continue
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
