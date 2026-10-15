package com.github.fabiopelliccia.sessionporter.cli

import com.github.fabiopelliccia.claudesessionsimportexport.core.ClaudePaths
import java.nio.file.Paths

/**
 * Turns a path as a person types it into the one the rest of the program works with.
 *
 * Git Bash hands `$PWD` over as `/c/Users/alice/work` and WSL names the same folder
 * `/mnt/c/Users/alice/work`; neither is a path Windows, or the working directory Claude Code recorded
 * (`C:\Users\alice\work`), would recognise, so on Windows both become `C:\Users\alice\work`. On other
 * systems a path is left as it is.
 */
internal object PathInput {

    /** `/c/Users/...` and `/c`. */
    private val GIT_BASH_DRIVE = Regex("^/([A-Za-z])(?:/(.*))?$")

    /** `/mnt/c/Users/...` (WSL) and `/cygdrive/c/Users/...` (Cygwin). */
    private val MOUNTED_DRIVE = Regex("^/(?:mnt|cygdrive)/([A-Za-z])(?:/(.*))?$")

    private val DRIVE = Regex("^[A-Za-z]:")

    /** [raw] with a Git Bash or WSL drive turned into a Windows one (only on Windows), nothing else changed. */
    fun toNativePath(raw: String, isWindows: Boolean): String {
        val path = raw.trim()
        if (!isWindows) return path
        val match = MOUNTED_DRIVE.matchEntire(path) ?: GIT_BASH_DRIVE.matchEntire(path) ?: return path
        val drive = match.groupValues[1].uppercase()
        val rest = match.groupValues[2].replace('/', '\\')
        return "$drive:\\$rest"
    }

    /**
     * A project folder as Claude Code records it: [toNativePath], then [ClaudePaths.normalizeProjectPath]. A path that
     * is not absolute (`.`, `work`) is taken from [workingDirectory], when there is one: Claude Code records the whole path.
     */
    fun normalize(raw: String, isWindows: Boolean, workingDirectory: String? = null): String {
        val native = toNativePath(raw, isWindows)
        val absolute = if (workingDirectory != null && !looksAbsolute(native)) {
            Paths.get(workingDirectory, native).normalize().toString()
        } else {
            native
        }
        return ClaudePaths.normalizeProjectPath(absolute)
    }

    /** Absolute in either style: a drive, or a leading separator (a POSIX root, a UNC share). */
    private fun looksAbsolute(path: String): Boolean = path.startsWith("/") || path.startsWith("\\") || DRIVE.containsMatchIn(path)
}
