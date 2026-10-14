package com.github.fabiopelliccia.claudesessionsimportexport.core

/**
 * Everything the plugin knows about one local Claude Code session without parsing the full
 * transcript - enough to list it, sort it and decide where to write it back on import.
 *
 * It is also the per-session entry of the archive manifest, so a field added here must stay
 * optional: an archive written before it existed simply leaves it unset.
 */
data class SessionInfo(
    val id: String,
    /** Folder name under `<home>/projects/`, e.g. `C--Users-pelliccia-IdeaProjects-Foo`. */
    val projectFolderName: String,
    /** Original working directory the session was recorded from, read back from the transcript. */
    val projectPath: String?,
    val firstTimestamp: String?,
    val lastTimestamp: String?,
    /** Number of `user` and `assistant` lines, sidechains included. */
    val messageCount: Int,
    val sizeBytes: Long,
    /** Short human-readable label picked by [SessionTitle]; `null` when the transcript offers none. */
    val summary: String?,
    /** Whether a sibling `<sessionId>/` auxiliary folder exists next to the transcript. */
    val hasAuxData: Boolean,
    /** Git branch the session was recorded on, when Claude Code wrote one. */
    val gitBranch: String? = null,
    /** Whether `<home>/file-history/<sessionId>/` - the backups behind checkpoints and `/rewind` - exists. */
    val hasFileHistory: Boolean = false,
) {
    val displayName: String
        get() = summary?.takeIf { it.isNotBlank() } ?: id
}

/** What the import does with a session whose id already exists anywhere under `<home>/projects/`. */
enum class ConflictPolicy(val messageKey: String) {
    /** Leaves the local session untouched and does not import the archived one. */
    SKIP("conflict.skip"),

    /** Removes the local session - transcript, auxiliary folder and file history - and imports the archived one in its place. */
    REPLACE("conflict.replace"),

    /** Imports the archived session under a freshly generated id, next to the local one. The default. */
    DUPLICATE("conflict.duplicate");

    val label: String get() = ClaudeSessionsBundle.message(messageKey)
}

fun interface TransferProgress {
    fun report(fraction: Double, text: String)

    companion object {
        val NOOP = TransferProgress { _, _ -> }
    }
}

/**
 * One imported session, as re-read from disk after it was written: the notification and the
 * diagnosis describe what Claude Code will actually find, never what the import meant to write.
 */
data class ImportedSession(
    val sourceId: String,
    val writtenId: String,
    val projectFolderName: String,
    /** What actually happened to this session: "new", "replaced" or "duplicated". */
    val action: String,
    val displayName: String = writtenId,
    /** First working directory recorded in the written transcript. */
    val cwd: String? = null,
    val messageCount: Int = 0,
    /** Number of files restored under `<home>/file-history/<writtenId>/`. */
    val fileHistoryFiles: Int = 0,
)

data class ImportFailure(val sessionId: String, val message: String)

data class ImportOutcome(
    val imported: List<ImportedSession>,
    val skipped: List<String>,
    val warnings: List<String>,
    val failures: List<ImportFailure> = emptyList(),
    /** One entry per failed check of the visibility diagnosis, see [ImportDiagnostics]. */
    val failedChecks: List<FailedCheck> = emptyList(),
)

/** A `KO` of the visibility diagnosis: [number] is the check number listed in `documentation/TASK.md`. */
data class FailedCheck(val sessionId: String, val number: Int, val detail: String) {
    override fun toString(): String = "KO  $sessionId  #$number  $detail"
}

data class ExportOutcome(
    val exportedCount: Int,
    val warnings: List<String>,
    /** Files that could not be read: the archive restores those sessions incomplete. */
    val unreadableFiles: Int = 0,
    /** `attachment` lines whose embedded account email and git identity were removed, see [SessionRedactor]. */
    val redactedIdentityLines: Int = 0,
    /** `attachment` lines whose own-machine scratch folder path was removed, see [SessionRedactor]. */
    val redactedScratchpadPaths: Int = 0,
)

/** Facts about the running IDE the import log records; supplied by `ui/`, which may read them. */
data class ImportEnvironment(
    val pluginVersion: String? = null,
    val ideBuild: String? = null,
    val productName: String? = null,
)

/**
 * Which tool and version wrote an archive, recorded in the manifest as the optional fields
 * `producerComponent` (`intellij-plugin`, `cli`) and `producerVersion`; a `null` field is omitted.
 */
data class ProducerInfo(
    val component: String? = null,
    val version: String? = null,
)
