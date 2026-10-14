package com.github.fabiopelliccia.claudesessionsimportexport.core

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Strips the exporting user's own identity and Claude Code's own scratch folder out of a transcript,
 * at export time, so the archive itself never carries them - not only the copy an import later
 * produces from it. Applied to every `.jsonl` file an export writes, the main transcript and any
 * subagent transcript under the auxiliary folder alike, since both are written in the same format.
 *
 * Claude Code writes two kinds of `attachment` line that carry this, on the exporter's behalf, never
 * as something the user typed:
 *
 * - a `session_context` line's `context.userEmail` and `context.gitStatus` - the account's email and
 *   the local `git status` output, which includes the configured `user.name`. Both are also mirrored,
 *   verbatim, in the line's own `rendered` cache (the pre-rendered system-reminder block Claude Code
 *   replays to the model instead of re-rendering the attachment), which is scrubbed of the exact same
 *   text - never of anything else in that block;
 * - an `environment` line's `snapshot.scratchpadDirectory` - Claude Code's own per-session temp
 *   folder, always under the OS temp directory and so always naming the account that ran it, and of
 *   no use on another machine, where a fresh one is created regardless of what this one said.
 *
 * Nothing else is touched: an `environment` line's `workingDirectory` and `additionalWorkingDirectories`
 * are the real project folders, not exporter trivia, so they are instead remapped like `cwd` when the
 * session is attached to a folder on import, in [TranscriptRewriter]. Everything below `message` and
 * `toolUseResult`, and any other attachment's own content, is what the user and Claude said to each
 * other and is never looked at, even when it happens to repeat one of these same values.
 */
object SessionRedactor {

    /** The `attachment.context` keys of a `session_context` line: who is asking, and from which repo. */
    val IDENTITY_KEYS = setOf("userEmail", "gitStatus")

    /** The `attachment.snapshot` key of an `environment` line, see the class doc. */
    const val SCRATCHPAD_KEY = "scratchpadDirectory"

    const val PLACEHOLDER = "[redacted for export: identifying account information]"

    private const val ATTACHMENT = "attachment"
    private const val SESSION_CONTEXT_TYPE = "session_context"
    private const val ENVIRONMENT_TYPE = "environment"
    private const val CONTEXT = "context"
    private const val SNAPSHOT = "snapshot"
    private const val RENDERED = "rendered"
    private const val CONTENT = "content"

    // JSONL is one compact object per line, the same settings TranscriptRewriter re-emits a line
    // with, so a line this pass changes looks exactly as Claude Code would have written it.
    private val gson = GsonBuilder().serializeNulls().disableHtmlEscaping().create()

    /** What a redaction pass did: counts only, never a value of the transcript. */
    class Stats {
        var redactedIdentityLines = 0
        var redactedScratchpadPaths = 0
    }

    /**
     * Redacts one transcript, whole. Splitting on `\n` alone keeps a `\r` on its own line and turns
     * the trailing newline into a final empty element, so joining back reproduces the original
     * layout exactly - a line neither known shape matches is kept byte for byte, unparsable lines
     * included.
     */
    fun redact(text: String, stats: Stats = Stats()): String =
        text.split('\n').joinToString("\n") { rawLine ->
            val line = rawLine.removeSuffix("\r")
            if (line.isBlank()) return@joinToString rawLine
            val obj = runCatching { JsonParser.parseString(line) }.getOrNull()
                ?.takeIf { it.isJsonObject }?.asJsonObject ?: return@joinToString rawLine
            if (!redactLine(obj, stats)) return@joinToString rawLine
            gson.toJson(obj) + rawLine.substring(line.length)
        }

    private fun redactLine(obj: JsonObject, stats: Stats): Boolean {
        val attachment = obj.get(ATTACHMENT)?.takeIf { it.isJsonObject }?.asJsonObject ?: return false
        return when (attachment.stringOrNull("type")) {
            SESSION_CONTEXT_TYPE -> redactSessionContext(obj, attachment, stats)
            ENVIRONMENT_TYPE -> redactScratchpad(attachment, stats)
            else -> false
        }
    }

    private fun redactSessionContext(obj: JsonObject, attachment: JsonObject, stats: Stats): Boolean {
        val context = attachment.get(CONTEXT)?.takeIf { it.isJsonObject }?.asJsonObject ?: return false
        val values = IDENTITY_KEYS.mapNotNull { key -> context.stringOrNull(key) }
        if (values.isEmpty()) return false
        for (key in IDENTITY_KEYS) context.remove(key)

        obj.get(RENDERED)?.takeIf { it.isJsonArray }?.asJsonArray?.forEach { entry ->
            val renderedLine = entry.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
            val original = renderedLine.stringOrNull(CONTENT) ?: return@forEach
            val scrubbed = values.fold(original) { text, value -> text.replace(value, PLACEHOLDER) }
            if (scrubbed != original) renderedLine.addProperty(CONTENT, scrubbed)
        }
        stats.redactedIdentityLines++
        return true
    }

    private fun redactScratchpad(attachment: JsonObject, stats: Stats): Boolean {
        val snapshot = attachment.get(SNAPSHOT)?.takeIf { it.isJsonObject }?.asJsonObject ?: return false
        snapshot.stringOrNull(SCRATCHPAD_KEY) ?: return false
        snapshot.addProperty(SCRATCHPAD_KEY, PLACEHOLDER)
        stats.redactedScratchpadPaths++
        return true
    }

    private fun JsonObject.stringOrNull(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
}
