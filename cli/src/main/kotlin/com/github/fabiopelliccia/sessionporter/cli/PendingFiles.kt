package com.github.fabiopelliccia.sessionporter.cli

import java.nio.file.Files
import java.nio.file.Path

/** Thrown inside a command that finds out, at the last step, that the process is being stopped. Nothing prints it. */
internal class CommandInterrupted : RuntimeException("interrupted")

/**
 * The temporary files and folders of one run, so that an interrupted run can take them away.
 *
 * A command registers a file with [track] as soon as it creates it and removes it with [discard] when it is
 * done with it. When the process is stopped (Ctrl+C, SIGTERM) [abandon] removes whatever is still registered.
 * The last step of a command, the one that makes its result visible, runs inside [commit]: it either happens
 * completely before the interruption is handled, or does not happen at all.
 */
internal class PendingFiles {

    private val lock = Any()
    private val paths = linkedSetOf<Path>()
    private var abandoned = false

    /** Registers [path], a file or a folder with its content, for removal. Returns it. */
    fun track(path: Path): Path = synchronized(lock) {
        paths.add(path)
        path
    }

    /** Removes [path] now, if it is still there, and forgets it. */
    fun discard(path: Path) = synchronized(lock) {
        delete(path)
        paths.remove(path)
    }

    /** Runs [action], which makes the result of the command visible; refuses to start once the run is [abandon]ed. */
    fun <T> commit(action: () -> T): T = synchronized(lock) {
        if (abandoned) throw CommandInterrupted()
        action()
    }

    /** Removes everything that is registered, and refuses every later [commit]. Never throws. */
    fun abandon() = synchronized(lock) {
        abandoned = true
        paths.forEach(::delete)
        paths.clear()
    }

    private fun delete(path: Path) {
        runCatching {
            if (Files.isDirectory(path)) path.toFile().deleteRecursively() else Files.deleteIfExists(path)
        }
    }
}
