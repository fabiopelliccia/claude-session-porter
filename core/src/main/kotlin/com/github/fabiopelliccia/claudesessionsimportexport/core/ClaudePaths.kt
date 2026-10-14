package com.github.fabiopelliccia.claudesessionsimportexport.core

import java.nio.file.Path
import java.nio.file.Paths

/**
 * Resolves the local Claude Code home directory and the layout Claude Code uses underneath it.
 *
 * Claude Code keeps every session transcript as a JSONL file at
 * `<home>/projects/<encodedProjectPath>/<sessionId>.jsonl`, with an optional sibling directory
 * `<home>/projects/<encodedProjectPath>/<sessionId>/` holding auxiliary data referenced from the
 * transcript (subagent runs, tool results, ...). The backups that make checkpoints and `/rewind`
 * work live apart from both, under `<home>/file-history/<sessionId>/`. All three are treated as one
 * unit by this plugin.
 */
object ClaudePaths {

    /** Same override Claude Code itself honors, so a relocated home is picked up automatically. */
    const val ENV_VAR = "CLAUDE_CONFIG_DIR"

    /** System property override, used by `runIde` to keep the sandbox away from the real home. */
    const val SYSTEM_PROPERTY = "claude.home"

    private val WINDOWS_DRIVE = Regex("^[A-Za-z]:")

    fun resolveHome(): Path {
        val override = System.getProperty(SYSTEM_PROPERTY) ?: System.getenv(ENV_VAR)
        if (!override.isNullOrBlank()) {
            return Paths.get(override)
        }
        return Paths.get(System.getProperty("user.home"), ".claude")
    }

    fun projectsDir(home: Path = resolveHome()): Path = home.resolve("projects")

    fun fileHistoryDir(home: Path = resolveHome()): Path = home.resolve("file-history")

    /**
     * Best-effort replication of the folder-naming scheme Claude Code applies to a project's
     * working directory: every character outside `[A-Za-z0-9]` becomes `-`. Letters outside ASCII
     * (`è`, `ü`, ...) are replaced too, exactly as Claude Code does, so a project path containing
     * them still maps to the folder Claude Code looks in. An existing folder found by
     * [SessionScanner] is always preferred over a freshly computed one.
     */
    fun encodeProjectPath(projectPath: String): String =
        projectPath.map { if (it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9') it else '-' }.joinToString("")

    /** A drive letter or a backslash means the path is spelled the Windows way. */
    fun isWindowsStyle(path: String): Boolean = path.contains('\\') || WINDOWS_DRIVE.containsMatchIn(path)

    /**
     * The form Claude Code records a working directory in: the separator of the path's own style
     * (IntelliJ hands a Windows folder over as `C:/Users/...`, Claude Code writes `C:\Users\...`)
     * and no trailing separator, except for a bare root such as `C:\` or `/`.
     */
    fun normalizeProjectPath(path: String): String {
        val separator = if (isWindowsStyle(path)) '\\' else '/'
        val unified = path.trim().replace('/', separator).replace('\\', separator)
        val minimumLength = if (WINDOWS_DRIVE.containsMatchIn(unified)) 3 else 1
        var end = unified.length
        while (end > minimumLength && unified[end - 1] == separator) end--
        return unified.substring(0, end)
    }
}
