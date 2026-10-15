package com.github.fabiopelliccia.sessionporter.cli

import com.github.fabiopelliccia.claudesessionsimportexport.core.ClaudePaths
import com.github.fabiopelliccia.claudesessionsimportexport.core.SessionInfo
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

/** How a session is shown, in JSON and in the plain tables, and how the sessions of a home are filtered. */
internal object SessionViews {

    /** The last word of a path, whichever separator it uses; `null` for a path without one. */
    fun leafOf(path: String?): String? =
        path?.trimEnd('\\', '/')?.substringAfterLast('\\')?.substringAfterLast('/')?.takeIf { it.isNotEmpty() }

    /** The name of the project of a session of this machine: the leaf of its folder, else the name of Claude's project folder. */
    fun projectNameOf(session: SessionInfo): String = session.projectName ?: leafOf(session.projectPath) ?: session.projectFolderName

    /** One session of `list`, with the keys the plugin skills read. */
    fun toJson(session: SessionInfo): Map<String, Any?> = linkedMapOf(
        "id" to session.id,
        "title" to session.displayName,
        "projectPath" to session.projectPath,
        "projectName" to projectNameOf(session),
        "projectFolderName" to session.projectFolderName,
        "firstTimestamp" to session.firstTimestamp,
        "lastTimestamp" to session.lastTimestamp,
        "messageCount" to session.messageCount,
        "sizeBytes" to session.sizeBytes,
        "gitBranch" to session.gitBranch,
        "hasAuxData" to session.hasAuxData,
        "hasFileHistory" to session.hasFileHistory,
    )

    /** `2024-03-10 09:00` out of an ISO timestamp, `-` for none. */
    fun shortTime(timestamp: String?): String = timestamp?.take(16)?.replace('T', ' ') ?: "-"

    /** [title] cut to [limit] characters for a table cell. */
    fun cell(title: String, limit: Int = 60): String = if (title.length > limit) title.take(limit - 1).trimEnd() + "…" else title

    /** Whether [session] was recorded in [project], a path as [PathInput.normalize] returns it (or the folder Claude Code made of it). */
    fun isInProject(session: SessionInfo, project: String): Boolean {
        val ignoreCase = ClaudePaths.isWindowsStyle(project)
        if (session.projectFolderName.equals(ClaudePaths.encodeProjectPath(project), ignoreCase)) return true
        val recorded = session.projectPath?.let(ClaudePaths::normalizeProjectPath) ?: return false
        return recorded.equals(project, ignoreCase)
    }

    /**
     * The moment `--since` stands for: `7d`, `12h`, `2w` before now, or an ISO instant, offset date-time,
     * local date-time (this machine's time zone) or date.
     */
    fun parseSince(text: String, clock: Clock): Instant {
        val relative = Regex("^(\\d+)([hdw])$", RegexOption.IGNORE_CASE).matchEntire(text.trim())
        if (relative != null) {
            val amount = relative.groupValues[1].toLongOrNull() ?: throw invalidSince(text)
            val duration = when (relative.groupValues[2].lowercase()) {
                "h" -> Duration.ofHours(amount)
                "d" -> Duration.ofDays(amount)
                else -> Duration.ofDays(amount * 7)
            }
            return clock.instant().minus(duration)
        }
        val zone = clock.zone
        return try {
            Instant.parse(text.trim())
        } catch (_: DateTimeParseException) {
            parseOrNull { OffsetDateTime.parse(text.trim()).toInstant() }
                ?: parseOrNull { LocalDateTime.parse(text.trim()).atZone(zone).toInstant() }
                ?: parseOrNull { LocalDate.parse(text.trim()).atStartOfDay(zone).toInstant() }
                ?: throw invalidSince(text)
        }
    }

    private fun parseOrNull(parse: () -> Instant): Instant? = try {
        parse()
    } catch (_: DateTimeParseException) {
        null
    }

    private fun invalidSince(text: String) = usageError("invalidSince", "error.usage.invalidSince", text)

    /** Whether the last activity of [session] is at or after [since]; a session without a readable timestamp is not. */
    fun isSince(session: SessionInfo, since: Instant): Boolean {
        val last = session.lastTimestamp ?: return false
        return try {
            !Instant.parse(last).isBefore(since)
        } catch (_: DateTimeParseException) {
            false
        }
    }

    /** A whole number of at least one, for the options that count things. */
    fun positiveNumber(option: String, text: String): Int =
        text.trim().toIntOrNull()?.takeIf { it > 0 } ?: throw usageError("invalidNumber", "error.usage.invalidNumber", option, text)
}
