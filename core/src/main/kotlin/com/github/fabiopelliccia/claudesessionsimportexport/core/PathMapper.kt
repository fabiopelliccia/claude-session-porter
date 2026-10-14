package com.github.fabiopelliccia.claudesessionsimportexport.core

/**
 * Translates the absolute paths a transcript recorded on the source machine to the folder chosen
 * on this one.
 *
 * A real session does not stick to one directory: it records the project root, directories below
 * it, and - when the session is moved to another project mid-way - a completely unrelated root.
 * Working directories and file paths are then treated differently, see [mapCwd] and [mapFile].
 *
 * Both the target and any kept remainder are written with the separator of the target's own style
 * (see [ClaudePaths.normalizeProjectPath]), so a folder picked in IntelliJ as `C:/Users/...` ends up
 * as `C:\Users\...`, the way Claude Code records it.
 */
class PathMapper(private val sourceRoot: String?, target: String) {

    val target: String = ClaudePaths.normalizeProjectPath(target)

    private val separator = if (ClaudePaths.isWindowsStyle(this.target)) '\\' else '/'

    /**
     * A working directory below [sourceRoot] keeps its relative remainder; anything else is
     * replaced outright, because it names a folder that only exists on the machine it came from.
     */
    fun mapCwd(recorded: String): String = descendantOf(recorded) ?: target

    /**
     * A file below [sourceRoot] keeps its relative remainder. A file anywhere else is left as
     * recorded (`null`): unlike a working directory it cannot fall back to the project root,
     * because a checkpoint restoring it there would write a file over a folder.
     */
    fun mapFile(recorded: String): String? = descendantOf(recorded)

    private fun descendantOf(recorded: String): String? {
        if (sourceRoot.isNullOrEmpty() || !recorded.startsWith(sourceRoot, ignoreCase = true)) return null
        val rest = recorded.substring(sourceRoot.length)
        // Only a separator makes it a descendant: `...\Demo` must not swallow `...\Demo2`.
        if (rest.isNotEmpty() && !rest.startsWith('\\') && !rest.startsWith('/')) return null
        return target + rest.replace('/', separator).replace('\\', separator)
    }
}
