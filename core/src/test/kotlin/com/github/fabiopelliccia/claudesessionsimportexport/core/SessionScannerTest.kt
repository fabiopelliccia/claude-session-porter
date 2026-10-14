package com.github.fabiopelliccia.claudesessionsimportexport.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files

class SessionScannerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `reads project cwd, summary and message count from a transcript`() {
        val home = tmp.newFolder(".claude").toPath()
        val projectDir = home.resolve("projects").resolve("C--Users-fabio-demo")
        Files.createDirectories(projectDir)

        val transcript = projectDir.resolve("session-1.jsonl")
        Files.write(
            transcript,
            listOf(
                """{"type":"mode","mode":"normal","sessionId":"session-1"}""",
                """{"type":"summary","summary":"Fix login bug","sessionId":"session-1"}""",
                """{"type":"user","isMeta":false,"message":{"role":"user","content":"Hello there"},"timestamp":"2026-01-01T10:00:00.000Z","cwd":"C:\\Users\\fabio\\demo","sessionId":"session-1"}""",
                """{"type":"assistant","message":{"role":"assistant","content":[{"type":"text","text":"Hi!"}]},"timestamp":"2026-01-01T10:00:05.000Z","sessionId":"session-1"}""",
            ),
        )

        val sessions = SessionScanner.listSessions(home)

        assertEquals(1, sessions.size)
        val session = sessions.single()
        assertEquals("session-1", session.id)
        assertEquals("C--Users-fabio-demo", session.projectFolderName)
        assertEquals("C:\\Users\\fabio\\demo", session.projectPath)
        assertEquals("Fix login bug", session.summary)
        assertEquals(2, session.messageCount)
        assertEquals("2026-01-01T10:00:00.000Z", session.firstTimestamp)
        assertEquals("2026-01-01T10:00:05.000Z", session.lastTimestamp)
    }

    @Test
    fun `falls back to a preview of the first user message when there is no summary line`() {
        val home = tmp.newFolder(".claude").toPath()
        val projectDir = home.resolve("projects").resolve("C--Users-fabio-demo")
        Files.createDirectories(projectDir)

        Files.write(
            projectDir.resolve("session-2.jsonl"),
            listOf(
                """{"type":"user","message":{"role":"user","content":"   What does this function do?   "},"timestamp":"2026-01-02T09:00:00.000Z","sessionId":"session-2"}""",
            ),
        )

        val session = SessionScanner.listSessions(home).single()
        assertEquals("What does this function do?", session.summary)
    }

    @Test
    fun `an empty home yields no sessions`() {
        val home = tmp.newFolder(".claude-missing").toPath().resolve("does-not-exist")
        assertTrue(SessionScanner.listSessions(home).isEmpty())
    }
}
