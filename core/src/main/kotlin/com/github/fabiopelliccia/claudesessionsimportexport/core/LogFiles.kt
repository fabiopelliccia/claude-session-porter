package com.github.fabiopelliccia.claudesessionsimportexport.core

import java.nio.file.Files
import java.nio.file.Path

/** Housekeeping of the log folder, shared by every front end. */
object LogFiles {

    /**
     * Keeps a log folder from growing forever: of the logs of [prefix] in [dir] only the [keep] most recent
     * survive. A log is a file named `<prefix>-yyyyMMdd-HHmmss.log`, or `<prefix>-yyyyMMdd-HHmmss-2.log`,
     * `-3.log`... when several were written in the same second. Any other file of the folder is somebody
     * else's and is never touched, whatever its name looks like. Never throws: a missing folder or an
     * undeletable file is simply left alone.
     */
    fun rotate(dir: Path, prefix: String, keep: Int) {
        runCatching {
            if (!Files.isDirectory(dir)) return
            val namePattern = Regex("${Regex.escape(prefix)}-(\\d{8}-\\d{6})(?:-(\\d+))?\\.log")
            val logs = Files.newDirectoryStream(dir, "$prefix-*.log").use { it.toList() }
                .mapNotNull { file ->
                    val match = namePattern.matchEntire(file.fileName.toString()) ?: return@mapNotNull null
                    // The time stamp orders the logs; of the same second, the one with the higher counter is the later.
                    val sortKey = match.groupValues[1] + "#" + (match.groupValues[2].toIntOrNull() ?: 1).toString().padStart(9, '0')
                    sortKey to file
                }
                .sortedBy { it.first }
            if (logs.size > keep) {
                logs.take(logs.size - keep).forEach { runCatching { Files.deleteIfExists(it.second) } }
            }
        }
    }
}
