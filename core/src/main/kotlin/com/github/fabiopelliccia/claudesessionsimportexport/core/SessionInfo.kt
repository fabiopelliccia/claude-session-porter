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
    /**
     * Name of the project folder (the leaf of its root), written by a format 2 export because
     * [projectPath] is then an anonymization token that says nothing about the project; `null` for
     * a local session and for an archive that predates it.
     */
    val projectName: String? = null,
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
    /** Path anchors replaced by a token, per kind (`HOME`, `ROOT`, `CLAUDE_HOME`), see [PathAnonymizer]. */
    val replacedByKind: Map<String, Int> = emptyMap(),
    /** The same replacements per spelling (`win`, `fwd`, `esc1`, `enc`...). */
    val replacedByStyle: Map<String, Int> = emptyMap(),
    /**
     * Whole-word occurrences of the user name that are not part of a path and were therefore left
     * in the archive (a branch called `users/alice/fix`, a `alice@host`); [residualEntries] names
     * the archive entries that hold them. Reported, never rewritten.
     */
    val residualUserNameHits: Int = 0,
    val residualEntries: List<String> = emptyList(),
    /**
     * Entries of the archive that are not text (or too large to be read whole) and were copied as they were, in which an
     * anchor or the bare name of the user was found, by entry name. Nothing can be removed from them.
     */
    val binaryEntriesWithHits: List<String> = emptyList(),
    /** Transcripts whose last line was still being written and was left out of the archive. */
    val partialLinesDropped: Int = 0,
    /** Object keys that two anonymized names would have merged: the later one got a `~2`-style counter, never its original name. */
    val keyCollisions: Int = 0,
    /**
     * Occurrences of the account email and git user name that were replaced outside the `session_context` lines the
     * redactor handles (a `git log` in a tool result, a trailer in a saved file), see [AccountIdentityScrubber].
     */
    val identityValuesScrubbed: Int = 0,
) {
    /**
     * Whether something of the exporting user may be left in the archive: a bare user name outside any path
     * ([residualUserNameHits]) or an anchor in a file that was only copied ([binaryEntriesWithHits]). This is what
     * a strict privacy check fails on.
     */
    val hasPrivacyFindings: Boolean get() = residualUserNameHits > 0 || binaryEntriesWithHits.isNotEmpty()
}

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
