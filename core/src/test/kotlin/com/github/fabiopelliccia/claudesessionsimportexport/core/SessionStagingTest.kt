package com.github.fabiopelliccia.claudesessionsimportexport.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path

class SessionStagingTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val home: Path get() = tmp.root.toPath().resolve(".claude")

    @Test
    fun `rollback puts back what was set aside and removes what was moved in`() {
        val projectDir = Files.createDirectories(home.resolve("projects").resolve("proj"))
        val transcript = projectDir.resolve("s.jsonl")
        Files.writeString(transcript, "old transcript")
        val aux = Files.createDirectories(projectDir.resolve("s"))
        Files.writeString(aux.resolve("old.txt"), "old aux")

        SessionStaging(home).use { staging ->
            Files.writeString(staging.transcript, "new transcript")
            Files.createDirectories(staging.auxDir)
            Files.writeString(staging.auxDir.resolve("new.txt"), "new aux")

            staging.setAside(transcript)
            staging.setAside(aux)
            staging.publish(staging.transcript, transcript)
            staging.publish(staging.auxDir, aux)
            assertEquals("new transcript", Files.readString(transcript))
            assertTrue(Files.exists(aux.resolve("new.txt")))

            staging.rollback()
        }

        assertEquals("old transcript", Files.readString(transcript))
        assertEquals("old aux", Files.readString(aux.resolve("old.txt")))
        assertFalse(Files.exists(aux.resolve("new.txt")))
    }

    @Test
    fun `a folder replaces the folder that was left there, and closing deletes the staging folder with the old one`() {
        val leftover = Files.createDirectories(home.resolve("projects").resolve("proj").resolve("s"))
        Files.writeString(leftover.resolve("leftover.txt"), "from a deleted transcript")

        val stagingRoot: Path
        SessionStaging(home).use { staging ->
            stagingRoot = staging.root
            Files.createDirectories(staging.auxDir)
            Files.writeString(staging.auxDir.resolve("new.txt"), "new")
            staging.publish(staging.auxDir, leftover)
        }

        assertEquals(listOf("new.txt"), Files.list(leftover).use { files -> files.map { it.fileName.toString() }.toList() })
        assertFalse(Files.exists(stagingRoot))
        // Nothing but the two folders Claude Code owns is left in the home.
        assertEquals(listOf("projects"), Files.list(home).use { files -> files.map { it.fileName.toString() }.toList() })
    }

    @Test
    fun `a file never replaces a file or a folder that is already there`() {
        val target = Files.createDirectories(home.resolve("projects").resolve("proj").resolve("s.jsonl"))
        Files.writeString(target.resolve("not-a-transcript"), "x")

        SessionStaging(home).use { staging ->
            Files.writeString(staging.transcript, "new transcript")
            val error = runCatching { staging.publish(staging.transcript, target) }.exceptionOrNull()
            assertTrue(error.toString(), error is java.io.IOException)
        }
        assertEquals("x", Files.readString(target.resolve("not-a-transcript")))
    }
}
