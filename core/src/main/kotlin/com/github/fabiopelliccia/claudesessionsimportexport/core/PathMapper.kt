package com.github.fabiopelliccia.claudesessionsimportexport.core

/**
 * Translates the absolute paths a transcript recorded on the source machine to the folder chosen
 * on this one.
 *
 * A real session does not stick to one directory: it records the project root, directories below
 * it, and - when the session is moved to another project mid-way - a completely unrelated root.
 * Working directories and file paths are then treated differently, see [mapCwd] and [mapFile].
 *
 * The recorded roots are [sourceRoots]: the project folder of the source machine, or, for an
 * anonymized archive (format 2), the `ROOT` token in each of its spellings, which is what a transcript
 * holds in its place. Files that lie elsewhere but are still known by a token - in the user's home, in
 * the Claude home - are mapped by [extraFileAnchors] (recorded prefix to destination folder), for
 * files only: a working directory outside the root still falls back to the target.
 *
 * Both the target and any kept remainder are written with the separator of the target's own style
 * (see [ClaudePaths.normalizeProjectPath]), so a folder picked in IntelliJ as `C:/Users/...` ends up
 * as `C:\Users\...`, the way Claude Code records it.
 */
class PathMapper(
    private val sourceRoots: List<String>,
    target: String,
    private val extraFileAnchors: Map<String, String> = emptyMap(),
) {

    /** A format 1 archive: one source root, possibly unknown. */
    constructor(sourceRoot: String?, target: String) : this(listOfNotNull(sourceRoot), target)

    val target: String = ClaudePaths.normalizeProjectPath(target)

    private val separator = if (ClaudePaths.isWindowsStyle(this.target)) '\\' else '/'

    /**
     * A working directory below a source root keeps its relative remainder; anything else is
     * replaced outright, because it names a folder that only exists on the machine it came from.
     */
    fun mapCwd(recorded: String): String = descendantOf(recorded, sourceRoots, this.target) ?: target

    /**
     * A file below a source root keeps its relative remainder, and so does one below an extra anchor
     * (relative to that anchor's destination). A file anywhere else is left as recorded (`null`):
     * unlike a working directory it cannot fall back to the project root, because a checkpoint
     * restoring it there would write a file over a folder.
     */
    fun mapFile(recorded: String): String? {
        descendantOf(recorded, sourceRoots, target)?.let { return it }
        for ((anchor, destination) in extraFileAnchors) {
            descendantOf(recorded, listOf(anchor), ClaudePaths.normalizeProjectPath(destination))?.let { return it }
        }
        return null
    }

    private fun descendantOf(recorded: String, roots: List<String>, destination: String): String? {
        for (root in roots) {
            if (root.isEmpty() || !recorded.startsWith(root, ignoreCase = true)) continue
            val rest = recorded.substring(root.length)
            // Only a separator makes it a descendant: `...\Demo` must not swallow `...\Demo2`.
            if (rest.isNotEmpty() && !rest.startsWith('\\') && !rest.startsWith('/')) continue
            return destination + rest.replace('/', separator).replace('\\', separator)
        }
        return null
    }
}
