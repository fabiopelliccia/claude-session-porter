package com.github.fabiopelliccia.sessionporter.cli

import com.github.fabiopelliccia.claudesessionsimportexport.core.SessionScanner
import java.nio.file.Files

/**
 * `inspect <zip>`: what an archive holds - its format, who wrote it, its sessions - and which of those
 * sessions this machine already has, which the import asks about (the conflict policy).
 */
internal class InspectCommand(private val context: CommandContext) {

    fun run(): CommandResult {
        val archive = ArchiveAccess.open(context, context.line.positionals.single())
        val manifest = archive.manifest
        // Only the file names of the local transcripts are read: whether an id is there is all that is needed.
        val localIds = if (Files.isDirectory(context.home)) SessionScanner.existingTranscripts(context.home).keys else emptySet()

        val sessions = manifest.sessions.map { session ->
            // A format 2 archive holds tokens where the source paths were: its sessions are told apart by the name of the project.
            val projectPath = session.projectPath.takeUnless { manifest.isAnonymized }
            val projectName = session.projectName ?: SessionViews.leafOf(projectPath)
            linkedMapOf(
                "id" to session.id,
                "title" to session.displayName,
                "projectPath" to projectPath,
                "projectName" to projectName,
                "firstTimestamp" to session.firstTimestamp,
                "lastTimestamp" to session.lastTimestamp,
                "messageCount" to session.messageCount,
                "sizeBytes" to session.sizeBytes,
                "gitBranch" to session.gitBranch,
                "hasAuxData" to session.hasAuxData,
                "hasFileHistory" to session.hasFileHistory,
                "presentLocally" to (session.id in localIds),
            )
        }
        val alreadyPresent = sessions.filter { it["presentLocally"] == true }.map { it["id"] }

        val anonymization = manifest.anonymization?.takeIf { manifest.isAnonymized }
        val data = linkedMapOf<String, Any?>(
            "archive" to archive.path.toAbsolutePath().toString(),
            "formatVersion" to manifest.formatVersion,
            "anonymized" to manifest.isAnonymized,
            "exportedAt" to manifest.exportedAt,
            "producer" to manifest.producer,
            "producerComponent" to manifest.producerComponent,
            "producerVersion" to manifest.producerVersion,
            "sourceOs" to anonymization?.sourceOs,
            "sessions" to sessions,
            "alreadyPresentIds" to alreadyPresent,
        )
        return CommandResult(data, humanLines(manifest.formatVersion, manifest.isAnonymized, manifest.producer, manifest.producerComponent, manifest.producerVersion, sessions))
    }

    private fun humanLines(
        formatVersion: Int,
        anonymized: Boolean,
        producer: String?,
        component: String?,
        componentVersion: String?,
        sessions: List<Map<String, Any?>>,
    ): List<String> {
        val written = listOfNotNull(producer, component?.let { "$it ${componentVersion ?: ""}".trim() }).joinToString(" / ")
        val lines = mutableListOf(
            CliBundle.message("inspect.format", formatVersion, CliBundle.message(if (anonymized) "inspect.anonymized" else "inspect.notAnonymized")),
            CliBundle.message("inspect.producer", written.ifEmpty { "?" }),
            "",
        )
        if (sessions.isEmpty()) return lines + CliBundle.message("inspect.empty")
        val headers = listOf(
            CliBundle.message("table.lastActivity"), CliBundle.message("table.messages"), CliBundle.message("table.project"),
            CliBundle.message("table.id"), CliBundle.message("table.title"), CliBundle.message("table.local"),
        )
        val rows = sessions.map {
            listOf(
                SessionViews.shortTime(it["lastTimestamp"] as String?), it["messageCount"].toString(), (it["projectName"] as String?) ?: "-",
                it["id"] as String, SessionViews.cell(it["title"] as String),
                CliBundle.message(if (it["presentLocally"] == true) "table.yes" else "table.no"),
            )
        }
        return lines + TextTable.render(headers, rows)
    }
}
