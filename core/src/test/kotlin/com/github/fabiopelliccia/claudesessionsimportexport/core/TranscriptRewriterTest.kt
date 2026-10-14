package com.github.fabiopelliccia.claudesessionsimportexport.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration

class TranscriptRewriterTest {

    private val rewriter = TranscriptRewriter(
        originalId = "old-id",
        rewriteToId = "new-id",
        mapper = PathMapper("C:\\src\\Demo", "D:/Work/Demo"),
        delta = Duration.ofHours(1),
    )

    private fun rewrite(vararg lines: String): List<String> =
        rewriter.rewrite(lines.joinToString("\n") + "\n").removeSuffix("\n").split("\n")

    @Test
    fun `the keys an import may rewrite are a contract`() {
        // Widening one of these lists means risking a rewrite of something that belongs to the
        // conversation: it has to come with a test proving it does not.
        assertEquals(setOf("sessionId", "session_id"), TranscriptRewriter.ID_KEYS)
        assertEquals(setOf("cwd"), TranscriptRewriter.CWD_KEYS)
        assertEquals(setOf("realParentDir", "trackingPath"), TranscriptRewriter.FILE_PATH_KEYS)
        assertEquals(setOf("timestamp", "backupTime"), TranscriptRewriter.ISO_TIMESTAMP_KEYS)
        assertEquals(setOf("startTime"), TranscriptRewriter.EPOCH_MILLIS_KEYS)
    }

    @Test
    fun `the file history of a snapshot follows the session to the new folder`() {
        val (written) = rewrite(
            """{"type":"file-history-snapshot","messageId":"m-1","snapshot":{"messageId":"m-1","trackedFileBackups":{"C:\\src\\Demo\\a.kt":{"backupFileName":"abc@v1","version":1,"backupTime":"2026-01-01T10:00:00.123Z","realParentDir":"C:\\src\\Demo"},"C:\\Temp\\x.py":{"backupFileName":null,"version":1,"backupTime":"2026-01-01T10:00:00Z","realParentDir":"C:\\Temp"}},"timestamp":"2026-01-01T10:00:00.123Z"},"isSnapshotUpdate":false}""",
        )
        // A file below the project root is remapped; one elsewhere keeps its path, since falling
        // back to the project root would make a checkpoint write a file over a folder.
        assertEquals(
            """{"type":"file-history-snapshot","messageId":"m-1","snapshot":{"messageId":"m-1","trackedFileBackups":{"D:\\Work\\Demo\\a.kt":{"backupFileName":"abc@v1","version":1,"backupTime":"2026-01-01T11:00:00.123Z","realParentDir":"D:\\Work\\Demo"},"C:\\Temp\\x.py":{"backupFileName":null,"version":1,"backupTime":"2026-01-01T11:00:00Z","realParentDir":"C:\\Temp"}},"timestamp":"2026-01-01T11:00:00.123Z"},"isSnapshotUpdate":false}""",
            written,
        )
    }

    @Test
    fun `a file history delta, an attachment and the cost state are adapted too`() {
        val written = rewrite(
            """{"type":"file-history-delta","messageId":"m-2","trackingPath":"C:\\src\\Demo\\b.kt","backup":{"backupFileName":null,"version":1,"backupTime":"2026-01-01T10:00:01.000Z","realParentDir":"C:\\src\\Demo"},"timestamp":"2026-01-01T10:00:01.000Z"}""",
            """{"type":"attachment","attachment":{"type":"session_context"},"timestamp":"2026-01-01T10:00:02.000Z","session_id":"old-id"}""",
            """{"type":"cost-state","sessionId":"old-id","totalCostUSD":0,"startTime":1767261600000}""",
        )
        assertEquals(
            listOf(
                """{"type":"file-history-delta","messageId":"m-2","trackingPath":"D:\\Work\\Demo\\b.kt","backup":{"backupFileName":null,"version":1,"backupTime":"2026-01-01T11:00:01.000Z","realParentDir":"D:\\Work\\Demo"},"timestamp":"2026-01-01T11:00:01.000Z"}""",
                """{"type":"attachment","attachment":{"type":"session_context"},"timestamp":"2026-01-01T11:00:02.000Z","session_id":"new-id"}""",
                """{"type":"cost-state","sessionId":"new-id","totalCostUSD":0,"startTime":1767265200000}""",
            ),
            written,
        )
    }

    @Test
    fun `an environment attachment's working directory follows cwd, the extra ones follow files`() {
        val (written) = rewrite(
            """{"type":"attachment","attachment":{"type":"environment","snapshot":{"workingDirectory":"C:\\src\\Demo\\sub","additionalWorkingDirectories":["C:\\src\\Demo\\extra","C:\\Unrelated\\Other"],"scratchpadDirectory":"C:\\Temp\\scratch","platform":"win32"}}}""",
        )
        // Nested under the root: both keep their relative remainder. Outside it: workingDirectory
        // still falls back to the attached folder (same rule as top-level cwd), but the additional
        // one is left exactly as recorded - it may be an entirely unrelated project.
        assertEquals(
            """{"type":"attachment","attachment":{"type":"environment","snapshot":{"workingDirectory":"D:\\Work\\Demo\\sub","additionalWorkingDirectories":["D:\\Work\\Demo\\extra","C:\\Unrelated\\Other"],"scratchpadDirectory":"C:\\Temp\\scratch","platform":"win32"}}}""",
            written,
        )
    }

    @Test
    fun `an environment attachment with no working directory of its own is left untouched`() {
        val (written) = rewrite(
            """{"type":"attachment","attachment":{"type":"environment","snapshot":{"scratchpadDirectory":"C:\\Temp\\scratch"}}}""",
        )
        assertEquals(
            """{"type":"attachment","attachment":{"type":"environment","snapshot":{"scratchpadDirectory":"C:\\Temp\\scratch"}}}""",
            written,
        )
    }

    @Test
    fun `what the user and Claude said to each other is never touched`() {
        // The same kinds of values the rewriter changes elsewhere - a path below the root, an ISO
        // instant, the session id - but inside `message` and `toolUseResult`, where they are content.
        val (written) = rewrite(
            """{"type":"user","message":{"role":"user","content":"see C:\\src\\Demo\\a.kt at 2026-01-01T10:00:00.000Z, session old-id","cwd":"C:\\src\\Demo"},"toolUseResult":{"filePath":"C:\\src\\Demo\\a.kt","cwd":"C:\\src\\Demo","timestamp":"2026-01-01T10:00:00.000Z","sessionId":"old-id"},"cwd":"C:\\src\\Demo","sessionId":"old-id","timestamp":"2026-01-01T10:00:00.000Z"}""",
        )
        assertEquals(
            """{"type":"user","message":{"role":"user","content":"see C:\\src\\Demo\\a.kt at 2026-01-01T10:00:00.000Z, session old-id","cwd":"C:\\src\\Demo"},"toolUseResult":{"filePath":"C:\\src\\Demo\\a.kt","cwd":"C:\\src\\Demo","timestamp":"2026-01-01T10:00:00.000Z","sessionId":"old-id"},"cwd":"D:\\Work\\Demo","sessionId":"new-id","timestamp":"2026-01-01T11:00:00.000Z"}""",
            written,
        )
    }

    @Test
    fun `unparsable lines and line endings are kept as they are`() {
        val text = "not json at all\r\n{\"type\":\"summary\",\"summary\":\"x\"}\r\n\r\n"
        val stats = TranscriptRewriter.Stats()
        assertEquals(text, rewriter.rewrite(text, stats))
        assertEquals(1, stats.unparsableLines)
        assertEquals(0, stats.rewrittenLines)
    }

    @Test
    fun `an identity rewrite returns the text unchanged`() {
        val identity = TranscriptRewriter("id", null, null, Duration.ZERO)
        assertTrue(identity.isIdentity)
        val text = """{"cwd":"C:\\x","timestamp":"2026-01-01T10:00:00.000Z"}"""
        assertEquals(text, identity.rewrite(text))
    }
}
