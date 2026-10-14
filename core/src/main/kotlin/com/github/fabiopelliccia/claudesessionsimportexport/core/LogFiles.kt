package com.github.fabiopelliccia.claudesessionsimportexport.core

import java.nio.file.Files
import java.nio.file.Path

/** Housekeeping of the log folder, shared by every front end. */
object LogFiles {

    /**
     * Keeps a log folder from growing forever: of the `<prefix>-*.log` files in [dir] only the [keep]
     * most recent survive. Files are named `<prefix>-yyyyMMdd-HHmmss.log`, so the name order is the
     * chronological one. Never throws: a missing folder or an undeletable file is simply left alone.
     */
    fun rotate(dir: Path, prefix: String, keep: Int) {
        runCatching {
            if (!Files.isDirectory(dir)) return
            val files = Files.newDirectoryStream(dir, "$prefix-*.log").use { it.toList() }
                .sortedBy { it.fileName.toString() }
            if (files.size > keep) {
                files.take(files.size - keep).forEach { runCatching { Files.deleteIfExists(it) } }
            }
        }
    }
}
