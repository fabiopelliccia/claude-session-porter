package com.github.fabiopelliccia.claudesessionsimportexport.core

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.nameWithoutExtension
import kotlin.streams.asSequence

/**
 * Reads the local Claude Code `projects/` tree and turns each `<sessionId>.jsonl` transcript into
 * a [SessionInfo], without ever holding a full transcript in memory - lines are streamed once.
 */
object SessionScanner {

    fun listSessions(home: Path = ClaudePaths.resolveHome()): List<SessionInfo> =
        transcripts(home)
            .map { describe(it, home) }
            .sortedByDescending { it.lastTimestamp ?: "" }

    /**
     * Every local transcript, grouped by session id, reading file names only. An id normally lives
     * in one project folder, but nothing stops two folders from holding the same one, and a
     * conflict has to see them all.
     */
    fun existingTranscripts(home: Path = ClaudePaths.resolveHome()): Map<String, List<Path>> =
        transcripts(home).groupBy { it.nameWithoutExtension }

    /** Reads a single transcript - the import uses it to re-read what it has just written. */
    fun describe(transcript: Path, home: Path = ClaudePaths.resolveHome()): SessionInfo {
        val projectDir = transcript.parent
        val id = transcript.nameWithoutExtension
        var firstTimestamp: String? = null
        var lastTimestamp: String? = null
        var cwd: String? = null
        var gitBranch: String? = null
        val title = SessionTitle()
        var messageCount = 0

        Files.newBufferedReader(transcript, StandardCharsets.UTF_8).use { reader ->
            reader.lineSequence().forEach { line ->
                if (line.isBlank()) return@forEach
                val obj = runCatching { JsonParser.parseString(line) }.getOrNull()
                    ?.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach

                obj.stringOrNull("timestamp")?.let { ts ->
                    if (firstTimestamp == null) firstTimestamp = ts
                    lastTimestamp = ts
                }
                if (cwd == null) cwd = obj.stringOrNull("cwd")
                obj.stringOrNull("gitBranch")?.takeIf { it.isNotBlank() }?.let { gitBranch = it }
                title.accept(obj)
                if (obj.stringOrNull("type") in setOf("user", "assistant")) messageCount++
            }
        }

        return SessionInfo(
            id = id,
            projectFolderName = projectDir.name,
            projectPath = cwd,
            firstTimestamp = firstTimestamp,
            lastTimestamp = lastTimestamp,
            messageCount = messageCount,
            sizeBytes = runCatching { Files.size(transcript) }.getOrDefault(0L),
            summary = title.value(),
            hasAuxData = projectDir.resolve(id).isDirectory(),
            gitBranch = gitBranch,
            hasFileHistory = ClaudePaths.fileHistoryDir(home).resolve(id).isDirectory(),
        )
    }

    private fun transcripts(home: Path): List<Path> {
        val projectsDir = ClaudePaths.projectsDir(home)
        if (!projectsDir.isDirectory()) return emptyList()
        return Files.list(projectsDir).use { projectDirs ->
            projectDirs.asSequence()
                .filter { it.isDirectory() }
                .flatMap { projectDir ->
                    Files.list(projectDir).use { entries ->
                        entries.asSequence().filter { it.isRegularFile() && it.extension == "jsonl" }.toList()
                    }
                }
                .toList()
        }
    }

    private fun JsonObject.stringOrNull(key: String): String? {
        val element: JsonElement = this.get(key) ?: return null
        return if (element.isJsonPrimitive) element.asString else null
    }
}
