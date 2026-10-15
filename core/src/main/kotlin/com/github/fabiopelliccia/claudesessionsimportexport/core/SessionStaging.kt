package com.github.fabiopelliccia.claudesessionsimportexport.core

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile

/**
 * The place where one session of an import is built before anything of the Claude home is touched.
 *
 * The transcript, the auxiliary folder and the file history are written into a folder of their own
 * inside the Claude home (so a move never leaves the volume), and only when all of them are complete
 * they are moved to their final place. A session that fails while it is being built - a corrupt entry
 * of the archive, a full disk - therefore leaves no half-written session behind, and a local copy
 * that was to be replaced is still there.
 *
 * The move itself can fail too (a file another program has open, for instance), so the local copy
 * is not deleted before it: it is put aside inside the staging folder, and put back by [rollback]
 * if the new files cannot be moved in. [close] deletes the staging folder, and with it the copy
 * that was put aside when everything went well.
 *
 * A folder that lies where a staged folder is moved to is put aside first: an auxiliary folder left
 * behind by a transcript that was deleted, for example. It is the same session's data and the
 * archive's version replaces it.
 */
internal class SessionStaging(claudeHome: Path) : AutoCloseable {

    /** The staging folder: a sibling of `projects/` and `file-history/`, which Claude Code never reads. */
    val root: Path = Files.createTempDirectory(Files.createDirectories(claudeHome), STAGING_PREFIX)

    val transcript: Path = root.resolve("transcript.jsonl")
    val auxDir: Path = root.resolve("aux")
    val fileHistoryDir: Path = root.resolve("file-history")

    private val putAside = root.resolve("replaced")
    private var asideCount = 0

    /** What has been done to the Claude home so far, as the steps that undo it, the last one first. */
    private val undoSteps = ArrayDeque<() -> Unit>()

    /** Moves [original] (a file or a folder, if it exists) out of the way, inside the staging folder. */
    fun setAside(original: Path) {
        if (!Files.exists(original)) return
        Files.createDirectories(putAside)
        val aside = putAside.resolve((asideCount++).toString())
        Files.move(original, aside)
        undoSteps.addFirst { Files.move(aside, original) }
    }

    /**
     * Moves the staged [source] (a file or a folder, if it exists) to [target], the place it is meant for.
     * A folder replaces the folder that is already there; a file never replaces anything, so a transcript
     * the scan did not see (a folder called like it, say) makes the session fail instead of being overwritten.
     */
    fun publish(source: Path, target: Path) {
        if (!Files.exists(source)) return
        if (source.isDirectory() && target.isDirectory()) setAside(target)
        Files.createDirectories(target.parent)
        Files.move(source, target)
        undoSteps.addFirst { deleteTree(target) }
    }

    /** Undoes every move done so far: the files that were moved in go, the ones put aside come back. */
    fun rollback() {
        while (undoSteps.isNotEmpty()) {
            val step = undoSteps.removeFirst()
            try {
                step()
            } catch (e: IOException) {
                // One step failing must not keep the others from running: what can be restored is restored.
            }
        }
    }

    override fun close() = deleteTree(root)

    companion object {
        const val STAGING_PREFIX = ".session-porter-staging-"

        /** Deletes [path], a file or a whole folder; nothing when it is not there. */
        fun deleteTree(path: Path) {
            if (path.isDirectory()) {
                Files.walk(path).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
            } else if (path.isRegularFile() || Files.exists(path)) {
                Files.deleteIfExists(path)
            }
        }
    }
}
