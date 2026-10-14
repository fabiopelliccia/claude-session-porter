package com.github.fabiopelliccia.claudesessionsimportexport.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionRedactorTest {

    private fun redact(vararg lines: String): List<String> {
        val stats = SessionRedactor.Stats()
        val result = SessionRedactor.redact(lines.joinToString("\n") + "\n", stats)
        assertTrue("must still end with a newline", result.endsWith("\n"))
        return result.removeSuffix("\n").split("\n")
    }

    @Test
    fun `a session_context attachment loses its identity fields but nothing else`() {
        val line = """{"type":"attachment","attachment":{"type":"session_context","context":{"userEmail":"user@example.com is the email","gitStatus":"Git user: Jane Doe\nOther status text"}},"uuid":"u-1","timestamp":"2026-01-01T00:00:00.000Z","cwd":"C:\\src\\Demo","sessionId":"s-1"}"""
        val (written) = redact(line)
        val obj = com.google.gson.JsonParser.parseString(written).asJsonObject
        val context = obj.getAsJsonObject("attachment").getAsJsonObject("context")
        assertFalse(context.has("userEmail"))
        assertFalse(context.has("gitStatus"))
        // Fields outside the two identity keys are untouched.
        assertEquals("C:\\src\\Demo", obj.get("cwd").asString)
        assertEquals("s-1", obj.get("sessionId").asString)
    }

    @Test
    fun `the same identity text mirrored in the rendered cache is scrubbed, nothing else in it`() {
        val line = """{"type":"attachment","attachment":{"type":"session_context","context":{"userEmail":"user@example.com is the email"}},"rendered":[{"content":"<system-reminder>\nbefore\nuser@example.com is the email\nafter\n</system-reminder>"}]}"""
        val (written) = redact(line)
        val rendered = com.google.gson.JsonParser.parseString(written).asJsonObject
            .getAsJsonArray("rendered").single().asJsonObject.get("content").asString
        assertFalse(rendered.contains("user@example.com"))
        assertTrue("surrounding text must survive", rendered.contains("before"))
        assertTrue("surrounding text must survive", rendered.contains("after"))
        assertTrue("still a system-reminder block", rendered.contains("<system-reminder>"))
    }

    @Test
    fun `an environment attachment loses only its scratchpad directory`() {
        val line = """{"type":"attachment","attachment":{"type":"environment","snapshot":{"workingDirectory":"C:\\Users\\fabio\\Demo","additionalWorkingDirectories":["C:\\Users\\fabio\\Other"],"scratchpadDirectory":"C:\\Users\\fabio\\AppData\\Local\\Temp\\claude\\x\\scratchpad","platform":"win32"}}}"""
        val (written) = redact(line)
        val snapshot = com.google.gson.JsonParser.parseString(written).asJsonObject
            .getAsJsonObject("attachment").getAsJsonObject("snapshot")
        assertFalse(snapshot.get("scratchpadDirectory").asString.contains("fabio"))
        // The project folders are left for TranscriptRewriter to remap on import, not touched here.
        assertEquals("C:\\Users\\fabio\\Demo", snapshot.get("workingDirectory").asString)
        assertEquals("C:\\Users\\fabio\\Other", snapshot.getAsJsonArray("additionalWorkingDirectories").single().asString)
        assertEquals("win32", snapshot.get("platform").asString)
    }

    @Test
    fun `lines with neither attachment shape are kept byte for byte`() {
        val lines = arrayOf(
            """{"type":"user","message":{"role":"user","content":"hello"},"cwd":"C:\\Demo"}""",
            """{"type":"attachment","attachment":{"type":"edited_text_file","path":"a.kt"}}""",
            """{"type":"attachment","attachment":{"type":"session_context","context":{}}}""",
            """{"type":"attachment","attachment":{"type":"environment","snapshot":{"workingDirectory":"C:\\Demo"}}}""",
            "not json at all",
        )
        assertEquals(lines.toList(), redact(*lines))
    }

    @Test
    fun `line endings, blank lines and stats are correct`() {
        val stats = SessionRedactor.Stats()
        val text = "{\"type\":\"attachment\",\"attachment\":{\"type\":\"session_context\",\"context\":{\"userEmail\":\"x\"}}}\r\n" +
            "\r\n" +
            "{\"type\":\"attachment\",\"attachment\":{\"type\":\"environment\",\"snapshot\":{\"scratchpadDirectory\":\"y\"}}}\r\n"
        val result = SessionRedactor.redact(text, stats)
        assertEquals(1, stats.redactedIdentityLines)
        assertEquals(1, stats.redactedScratchpadPaths)
        // \r\n endings and the blank line survive untouched.
        assertTrue(result.contains("\r\n\r\n"))
        assertTrue(result.endsWith("\r\n"))
    }
}
