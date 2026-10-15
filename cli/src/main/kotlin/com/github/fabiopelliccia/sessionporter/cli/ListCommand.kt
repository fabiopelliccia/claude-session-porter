package com.github.fabiopelliccia.sessionporter.cli

import com.github.fabiopelliccia.claudesessionsimportexport.core.SessionInfo
import com.github.fabiopelliccia.claudesessionsimportexport.core.SessionScanner

/** `list [--project <path>] [--since <ISO|7d>] [--limit N]`: the sessions of the Claude home, the most recent first. */
internal class ListCommand(private val context: CommandContext) {

    fun run(): CommandResult {
        val line = context.line
        val home = context.requireHome()
        val project = line.value("--project")?.let(context::projectPath)
        val since = line.value("--since")?.let { SessionViews.parseSince(it, context.environment.clock) }
        val limit = line.value("--limit")?.let { SessionViews.positiveNumber("--limit", it) }

        var sessions = SessionScanner.listSessions(home)
        context.log.info("${sessions.size} sessions under $home")
        if (project != null) sessions = sessions.filter { SessionViews.isInProject(it, project) }
        if (since != null) sessions = sessions.filter { SessionViews.isSince(it, since) }
        val matching = sessions.size
        if (limit != null) sessions = sessions.take(limit)

        val data = linkedMapOf<String, Any?>(
            "home" to home.toAbsolutePath().toString(),
            "totalMatching" to matching,
            "sessions" to sessions.map(SessionViews::toJson),
        )
        return CommandResult(data, humanLines(sessions, matching))
    }

    private fun humanLines(sessions: List<SessionInfo>, matching: Int): List<String> {
        if (sessions.isEmpty()) return listOf(CliBundle.message("list.empty"))
        val headers = listOf(
            CliBundle.message("table.lastActivity"), CliBundle.message("table.messages"), CliBundle.message("table.project"),
            CliBundle.message("table.id"), CliBundle.message("table.title"),
        )
        val rows = sessions.map {
            listOf(
                SessionViews.shortTime(it.lastTimestamp), it.messageCount.toString(), SessionViews.projectNameOf(it), it.id,
                SessionViews.cell(it.displayName),
            )
        }
        val summary = if (sessions.size < matching) CliBundle.message("list.shown", sessions.size, matching) else CliBundle.message("list.total", matching)
        return TextTable.render(headers, rows) + "" + summary
    }
}
